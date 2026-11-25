package app.servicecalendar.auth

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import app.servicecalendar.backup.AccessTokenResult
import app.servicecalendar.backup.ConsentResult
import app.servicecalendar.backup.GoogleAccount
import app.servicecalendar.backup.GoogleAuthProvider
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject

/**
 * Authorization API has no notion of a current account, so after consent only account metadata (never tokens) is kept in private SharedPreferences:
 * the stable Google `sub` and the account email, the latter so background authorize calls use setAccount for that account.
 * The consent PendingIntent is launched through an ActivityResultLauncher registered in MainActivity.onCreate and released in onDestroy.
 */
class AndroidGoogleAuth(context: Context) : GoogleAuthProvider {

    private val appContext: Context = context.applicationContext

    private val client: AuthorizationClient by lazy { Identity.getAuthorizationClient(appContext) }

    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val host = AtomicReference<ConsentHost?>(null)

    private val pendingConsent = AtomicReference<CompletableDeferred<ActivityResult>?>(null)

    private val consentMutex = Mutex()

    private class ConsentHost(
        val lifecycle: Lifecycle,
        val launcher: ActivityResultLauncher<IntentSenderRequest>,
    )

    private class StoredAccount(val id: String, val accountName: String, val label: String)

    fun attach(activity: ComponentActivity) {
        val launcher = activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result -> onConsentActivityResult(result) }
        val attached = ConsentHost(activity.lifecycle, launcher)
        host.set(attached)
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                host.compareAndSet(attached, null)
            }
        })
    }

    private fun onConsentActivityResult(result: ActivityResult) {
        pendingConsent.getAndSet(null)?.complete(result)
    }

    override suspend fun currentAccount(): GoogleAccount? = safely(onFailure = { null }) {
        loadAccount()?.let { GoogleAccount(it.id, it.label) }
    }

    override suspend fun accessToken(): AccessTokenResult = safely(
        onFailure = { AccessTokenResult.Failure(retryable = false, diagnostic = it) },
    ) {
        fetchAccessToken()
    }.also { result ->
        if (result is AccessTokenResult.Failure) {
            Log.w(TAG, "accessToken: ${result.diagnostic} (retryable=${result.retryable})")
        }
    }

    private suspend fun fetchAccessToken(): AccessTokenResult {
        val stored = loadAccount() ?: return AccessTokenResult.NotSignedIn
        val result = when (val outcome = authorizeSilently(request(Account(stored.accountName, ACCOUNT_TYPE)))) {
            is Authorize.Done -> outcome.result
            is Authorize.Error -> return backgroundFailure(outcome)
        }
        return when {
            result.hasResolution() -> AccessTokenResult.NeedsUserAction
            !result.grantsDriveAppData() -> AccessTokenResult.NeedsUserAction
            result.accountId().let { it != null && it != stored.id } -> AccessTokenResult.NeedsUserAction
            else -> result.accessToken?.takeIf { it.isNotBlank() }?.let { AccessTokenResult.Token(it) }
                ?: AccessTokenResult.Failure(retryable = false, diagnostic = "no_token")
        }
    }

    override suspend fun invalidateAccessToken(accessToken: String) {
        safely(onFailure = { }) {
            val request = ClearTokenRequest.builder().setToken(accessToken).build()
            withTimeoutOrNull(CALL_TIMEOUT) { client.clearToken(request).await() }
        }
    }

    override suspend fun requestConsent(): ConsentResult {
        if (!consentMutex.tryLock()) return ConsentResult.Failure(retryable = true, diagnostic = "consent_in_progress")
        return try {
            safely(onFailure = { ConsentResult.Failure(retryable = false, diagnostic = it) }) { consent() }
                .also { result ->
                    if (result is ConsentResult.Failure) {
                        Log.w(TAG, "requestConsent: ${result.diagnostic} (retryable=${result.retryable})")
                    }
                }
        } finally {
            consentMutex.unlock()
        }
    }

    /** Local sign-out only: revokeAccess is deliberately not called because it revokes consent project-wide (shared with iOS) and needs network. */
    override suspend fun signOut() {
        safely(onFailure = { }) {
            withContext(Dispatchers.IO) { prefs.edit().clear().commit() }
        }
    }

    private suspend fun consent(): ConsentResult {
        val first = when (val outcome = authorize(request(account = null, prompt = AuthorizationRequest.Prompt.SELECT_ACCOUNT))) {
            is Authorize.Done -> outcome.result
            is Authorize.Error -> return consentFailure(outcome)
        }
        val final = if (first.hasResolution()) {
            when (val resolved = resolve(first)) {
                is Resolved.Done -> resolved.result
                is Resolved.Stop -> return resolved.result
            }
        } else {
            first
        }
        if (!final.grantsDriveAppData()) return ConsentResult.Cancelled
        val token = final.accessToken?.takeIf { it.isNotBlank() }
            ?: return ConsentResult.Failure(retryable = false, diagnostic = "no_token")
        val user = when (val info = fetchUserInfo(token)) {
            is UserInfo.Done -> info
            is UserInfo.Error -> return ConsentResult.Failure(info.retryable, info.diagnostic)
        }
        val id = user.sub
        val accountName = user.email
        val label = user.email
        val saved = withContext(Dispatchers.IO) {
            prefs.edit()
                .putString(KEY_ID, id)
                .putString(KEY_ACCOUNT_NAME, accountName)
                .putString(KEY_LABEL, label)
                .commit()
        }
        if (!saved) return ConsentResult.Failure(retryable = true, diagnostic = "prefs_write")
        return ConsentResult.Granted(GoogleAccount(id, label))
    }

    private sealed interface Resolved {
        class Done(val result: AuthorizationResult) : Resolved
        class Stop(val result: ConsentResult) : Resolved
    }

    private suspend fun resolve(first: AuthorizationResult): Resolved {
        val pendingIntent = first.pendingIntent
            ?: return Resolved.Stop(ConsentResult.Failure(retryable = false, diagnostic = "no_pending_intent"))
        val current = host.get()?.takeIf { it.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
            ?: return Resolved.Stop(ConsentResult.Failure(retryable = true, diagnostic = "no_activity"))
        val deferred = CompletableDeferred<ActivityResult>()
        pendingConsent.set(deferred)
        val activityResult = try {
            withContext(Dispatchers.Main.immediate) {
                current.launcher.launch(IntentSenderRequest.Builder(pendingIntent).build())
            }
            deferred.await()
        } finally {
            pendingConsent.compareAndSet(deferred, null)
        }
        val data = activityResult.data
        if (activityResult.resultCode != Activity.RESULT_OK || data == null) {
            // Configuration errors (e.g. package + SHA-1 not registered) also come back as a non-OK result; only a real cancel stays silent.
            val error = data?.let { consentErrorIn(it) }
            return Resolved.Stop(if (error == null) ConsentResult.Cancelled else consentFailure(error))
        }
        return try {
            Resolved.Done(client.getAuthorizationResultFromIntent(data))
        } catch (e: ApiException) {
            Resolved.Stop(consentFailure(Authorize.Error(e.statusCode, "api_${e.statusCode}")))
        }
    }

    private fun consentErrorIn(data: Intent): Authorize.Error? = try {
        client.getAuthorizationResultFromIntent(data)
        null
    } catch (e: ApiException) {
        Log.w(TAG, "consent: code ${e.statusCode}")
        Authorize.Error(e.statusCode, "api_${e.statusCode}")
    } catch (e: RuntimeException) {
        null
    }

    // authorize() returns no account ID without a web client ID, so `sub` and email come from OpenID userinfo using the freshly issued token.
    private sealed interface UserInfo {
        class Done(val sub: String, val email: String) : UserInfo
        class Error(val retryable: Boolean, val diagnostic: String) : UserInfo
    }

    private suspend fun fetchUserInfo(accessToken: String): UserInfo = withContext(Dispatchers.IO) {
        val connection = URL(USERINFO_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return@withContext UserInfo.Error(retryable = code >= 500 || code == 429, diagnostic = "userinfo_http_$code")
            }
            val body = connection.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            val json = JSONObject(body)
            val sub = json.optString("sub").takeIf { it.isNotBlank() }
                ?: return@withContext UserInfo.Error(retryable = false, diagnostic = "userinfo_no_sub")
            val email = json.optString("email").takeIf { it.isNotBlank() }
                ?: return@withContext UserInfo.Error(retryable = false, diagnostic = "userinfo_no_email")
            UserInfo.Done(sub, email)
        } catch (e: IOException) {
            UserInfo.Error(retryable = true, diagnostic = "userinfo_${e::class.simpleName}")
        } catch (e: JSONException) {
            UserInfo.Error(retryable = false, diagnostic = "userinfo_parse")
        } finally {
            connection.disconnect()
        }
    }

    private sealed interface Authorize {
        class Done(val result: AuthorizationResult) : Authorize
        class Error(val statusCode: Int?, val diagnostic: String) : Authorize
    }

    private fun request(account: Account?, prompt: Int = AuthorizationRequest.Prompt.NOT_SET): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(REQUESTED_SCOPES)
            .apply { if (account != null) setAccount(account) }
            .apply { if (prompt != AuthorizationRequest.Prompt.NOT_SET) setPrompt(prompt) }
            .build()

    private suspend fun authorize(request: AuthorizationRequest): Authorize = try {
        Authorize.Done(client.authorize(request).await())
    } catch (e: ApiException) {
        Log.w(TAG, "authorize: code ${e.statusCode}")
        Authorize.Error(e.statusCode, "api_${e.statusCode}")
    } catch (e: TaskCanceledException) {
        Authorize.Error(null, "canceled")
    }

    private suspend fun authorizeSilently(request: AuthorizationRequest): Authorize =
        withTimeoutOrNull(CALL_TIMEOUT) { authorize(request) } ?: Authorize.Error(null, "timeout")

    private fun backgroundFailure(error: Authorize.Error): AccessTokenResult = when (error.statusCode) {
        null -> AccessTokenResult.Failure(retryable = true, diagnostic = error.diagnostic)
        CommonStatusCodes.SIGN_IN_REQUIRED,
        CommonStatusCodes.RESOLUTION_REQUIRED,
        CommonStatusCodes.INVALID_ACCOUNT,
        CommonStatusCodes.CANCELED,
        -> AccessTokenResult.NeedsUserAction
        in TRANSIENT_STATUS_CODES -> AccessTokenResult.Failure(retryable = true, diagnostic = error.diagnostic)
        else -> AccessTokenResult.Failure(retryable = false, diagnostic = error.diagnostic)
    }

    private fun consentFailure(error: Authorize.Error): ConsentResult = when (error.statusCode) {
        CommonStatusCodes.CANCELED -> ConsentResult.Cancelled
        null, in TRANSIENT_STATUS_CODES -> ConsentResult.Failure(retryable = true, diagnostic = error.diagnostic)
        else -> ConsentResult.Failure(retryable = false, diagnostic = error.diagnostic)
    }

    private fun AuthorizationResult.grantsDriveAppData(): Boolean = DRIVE_APPDATA_SCOPE.scopeUri in grantedScopes

    @Suppress("DEPRECATION")
    private fun AuthorizationResult.accountId(): String? = toGoogleSignInAccount()?.id?.takeIf { it.isNotBlank() }

    private suspend fun loadAccount(): StoredAccount? = withContext(Dispatchers.IO) {
        val id = prefs.getString(KEY_ID, null)?.takeIf { it.isNotBlank() }
        val name = prefs.getString(KEY_ACCOUNT_NAME, null)?.takeIf { it.isNotBlank() }
        if (id == null || name == null) null else StoredAccount(id, name, prefs.getString(KEY_LABEL, null) ?: name)
    }

    private suspend fun <T> safely(onFailure: (diagnostic: String) -> T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        val diagnostic = e::class.simpleName ?: "exception"
        Log.w(TAG, "Google authorization error: $diagnostic")
        onFailure(diagnostic)
    }

    companion object {
        private const val TAG = "ServiceCalendarAuth"
        private const val PREFS_NAME = "google_account"
        private const val KEY_ID = "account_id"
        private const val KEY_ACCOUNT_NAME = "account_name"
        private const val KEY_LABEL = "account_label"
        private const val ACCOUNT_TYPE = "com.google"

        private const val USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo"
        private const val HTTP_TIMEOUT_MS = 20_000

        private val CALL_TIMEOUT = 60.seconds

        val DRIVE_APPDATA_SCOPE = Scope("https://www.googleapis.com/auth/drive.appdata")

        private val REQUESTED_SCOPES = listOf(DRIVE_APPDATA_SCOPE, Scope("openid"), Scope("email"))

        private val TRANSIENT_STATUS_CODES = setOf(
            CommonStatusCodes.NETWORK_ERROR,
            CommonStatusCodes.INTERNAL_ERROR,
            CommonStatusCodes.INTERRUPTED,
            CommonStatusCodes.TIMEOUT,
            CommonStatusCodes.API_NOT_CONNECTED,
            CommonStatusCodes.RECONNECTION_TIMED_OUT,
            CommonStatusCodes.RECONNECTION_TIMED_OUT_DURING_UPDATE,
        )

        private val DIRECT_EXECUTOR = Executor { it.run() }
    }

    private class TaskCanceledException : RuntimeException()

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener(DIRECT_EXECUTOR) { task ->
            val error = task.exception
            when {
                task.isCanceled -> continuation.resumeWithException(TaskCanceledException())
                error != null -> continuation.resumeWithException(error)
                else -> continuation.resume(task.result)
            }
        }
    }
}
