package app.servicecalendar.backup

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class GoogleAccount(
    val id: String,
    val displayLabel: String,
) {
    init {
        require(id.isNotBlank()) { "Account ID cannot be blank" }
    }

    override fun equals(other: Any?): Boolean =
        other is GoogleAccount && other.id == id && other.displayLabel == displayLabel

    override fun hashCode(): Int = 31 * id.hashCode() + displayLabel.hashCode()

    override fun toString(): String = "GoogleAccount(***)"
}

sealed class AccessTokenResult {
    class Token(val accessToken: String) : AccessTokenResult() {
        init {
            require(accessToken.isNotBlank()) { "Access token is blank" }
        }

        override fun toString(): String = "Token(***)"
    }

    data object NeedsUserAction : AccessTokenResult()

    data object NotSignedIn : AccessTokenResult()

    class Failure(val retryable: Boolean, val diagnostic: String) : AccessTokenResult() {
        override fun toString(): String = "Failure(retryable=$retryable, diagnostic=$diagnostic)"
    }
}

sealed class ConsentResult {
    class Granted(val account: GoogleAccount) : ConsentResult() {
        override fun toString(): String = "Granted(***)"
    }

    data object Cancelled : ConsentResult()

    class Failure(val retryable: Boolean, val diagnostic: String) : ConsentResult() {
        override fun toString(): String = "Failure(retryable=$retryable, diagnostic=$diagnostic)"
    }
}

/** Implementations must not cache tokens, log them, or show UI from currentAccount/accessToken/invalidateAccessToken/signOut (background runs call them). Only requestConsent may show UI. */
interface GoogleAuthProvider {
    suspend fun currentAccount(): GoogleAccount?

    suspend fun accessToken(): AccessTokenResult

    suspend fun invalidateAccessToken(accessToken: String)

    suspend fun requestConsent(): ConsentResult

    suspend fun signOut()
}

fun interface GoogleAccountCallback {
    fun onResult(account: GoogleAccount?)
}

fun interface AccessTokenCallback {
    fun onResult(result: AccessTokenResult)
}

fun interface ConsentCallback {
    fun onResult(result: ConsentResult)
}

fun interface AuthCompletionCallback {
    fun onComplete()
}

interface GoogleAuthBridge {
    fun currentAccount(callback: GoogleAccountCallback)

    fun fetchAccessToken(callback: AccessTokenCallback)

    fun invalidateAccessToken(accessToken: String, callback: AuthCompletionCallback)

    fun requestConsent(callback: ConsentCallback)

    fun signOut(callback: AuthCompletionCallback)
}

@OptIn(ExperimentalAtomicApi::class)
class BridgedGoogleAuthProvider internal constructor(
    private val bridge: GoogleAuthBridge,
    private val callTimeout: Duration,
) : GoogleAuthProvider {
    constructor(bridge: GoogleAuthBridge) : this(bridge, DEFAULT_CALL_TIMEOUT)

    override suspend fun currentAccount(): GoogleAccount? =
        withTimeoutOrNull(callTimeout) {
            awaitCallback<GoogleAccount?>(onFailure = { null }) { resume ->
                bridge.currentAccount { resume(it) }
            }
        }

    override suspend fun accessToken(): AccessTokenResult =
        withTimeoutOrNull(callTimeout) {
            awaitCallback<AccessTokenResult>(onFailure = { AccessTokenResult.Failure(false, it) }) { resume ->
                bridge.fetchAccessToken { resume(it) }
            }
        } ?: AccessTokenResult.Failure(retryable = true, diagnostic = "timeout")

    override suspend fun invalidateAccessToken(accessToken: String) {
        withTimeoutOrNull(callTimeout) {
            awaitCallback(onFailure = { }) { resume ->
                bridge.invalidateAccessToken(accessToken) { resume(Unit) }
            }
        }
    }

    override suspend fun requestConsent(): ConsentResult =
        awaitCallback<ConsentResult>(onFailure = { ConsentResult.Failure(false, it) }) { resume ->
            bridge.requestConsent { resume(it) }
        }

    override suspend fun signOut() {
        withTimeoutOrNull(callTimeout) {
            awaitCallback(onFailure = { }) { resume ->
                bridge.signOut { resume(Unit) }
            }
        }
    }

    private suspend fun <T> awaitCallback(
        onFailure: (diagnostic: String) -> T,
        start: (resume: (T) -> Unit) -> Unit,
    ): T = suspendCancellableCoroutine { continuation ->
        val answered = AtomicBoolean(false)
        val resume: (T) -> Unit = { value ->
            if (answered.compareAndSet(expectedValue = false, newValue = true)) continuation.resume(value)
        }
        try {
            start(resume)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            resume(onFailure(e::class.simpleName ?: "exception"))
        }
    }

    companion object {
        val DEFAULT_CALL_TIMEOUT: Duration = 60.seconds
    }
}
