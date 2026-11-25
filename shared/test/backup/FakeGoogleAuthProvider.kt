package app.servicecalendar.backup

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlinx.coroutines.CompletableDeferred

/**
 * A test [GoogleAuthProvider] for the coordinator and UI tests.
 *
 * - [account] is the current account (`null` means not connected).
 * - [tokenResult] is what [accessToken] returns; by default the [TOKEN] token, or
 *   [AccessTokenResult.NotSignedIn] when there is no account. [queueTokens] adds one-off answers that
 *   take priority over [tokenResult].
 * - [consentResult] answers [requestConsent]; `Granted` connects the given account.
 * - [holdConsent] keeps the consent "open" until the test completes it (a consent UI can stay up for minutes).
 * - [beforeTokenIssued] runs in [accessToken] before the token is issued (for example to switch the
 *   account in the middle of an operation).
 * - The call counters let tests verify that background work never asks for UI ([consentRequests]).
 */
@OptIn(ExperimentalAtomicApi::class)
class FakeGoogleAuthProvider(account: GoogleAccount? = ACCOUNT_A) : GoogleAuthProvider {
    private val currentAccount = AtomicReference(account)
    private val queuedTokens = AtomicReference<List<AccessTokenResult>>(emptyList())
    private val invalidatedTokens = AtomicReference<List<String>>(emptyList())
    private val tokenCallCount = AtomicInt(0)
    private val consentCallCount = AtomicInt(0)
    private val signOutCallCount = AtomicInt(0)

    var account: GoogleAccount?
        get() = currentAccount.load()
        set(value) = currentAccount.store(value)

    /** A fixed answer for [accessToken]; `null` means the [TOKEN] token for a connected account. */
    var tokenResult: AccessTokenResult? = null

    /** The answer to [requestConsent]. */
    var consentResult: ConsentResult = ConsentResult.Cancelled

    /** Called at the start of every [accessToken]. */
    var beforeTokenIssued: (() -> Unit)? = null

    private val consentGate = AtomicReference<Pair<CompletableDeferred<Unit>, CompletableDeferred<ConsentResult>>?>(null)

    /**
     * The next [requestConsent] waits: `entered` completes when the consent is "shown" and the value of
     * `answer` becomes its result (`Granted` connects the account). Returns (`entered`, `answer`).
     */
    fun holdConsent(): Pair<CompletableDeferred<Unit>, CompletableDeferred<ConsentResult>> {
        val pair = CompletableDeferred<Unit>() to CompletableDeferred<ConsentResult>()
        consentGate.store(pair)
        return pair
    }

    val tokenCalls: Int get() = tokenCallCount.load()
    val consentRequests: Int get() = consentCallCount.load()
    val signOutCalls: Int get() = signOutCallCount.load()
    val invalidated: List<String> get() = invalidatedTokens.load()

    /** Queues one-off [accessToken] answers. */
    fun queueTokens(vararg results: AccessTokenResult) {
        while (true) {
            val current = queuedTokens.load()
            if (queuedTokens.compareAndSet(current, current + results)) return
        }
    }

    override suspend fun currentAccount(): GoogleAccount? = currentAccount.load()

    override suspend fun accessToken(): AccessTokenResult {
        tokenCallCount.incrementAndFetch()
        beforeTokenIssued?.invoke()
        while (true) {
            val queue = queuedTokens.load()
            if (queue.isEmpty()) break
            if (queuedTokens.compareAndSet(queue, queue.drop(1))) return queue.first()
        }
        tokenResult?.let { return it }
        return if (currentAccount.load() == null) AccessTokenResult.NotSignedIn else AccessTokenResult.Token(TOKEN)
    }

    override suspend fun invalidateAccessToken(accessToken: String) {
        while (true) {
            val current = invalidatedTokens.load()
            if (invalidatedTokens.compareAndSet(current, current + accessToken)) return
        }
    }

    override suspend fun requestConsent(): ConsentResult {
        consentCallCount.incrementAndFetch()
        val result = consentGate.exchange(null)?.let { (entered, answer) ->
            entered.complete(Unit)
            answer.await()
        } ?: consentResult
        if (result is ConsentResult.Granted) currentAccount.store(result.account)
        return result
    }

    override suspend fun signOut() {
        signOutCallCount.incrementAndFetch()
        currentAccount.store(null)
    }

    companion object {
        const val TOKEN: String = "ya29.secret-test-token"
        val ACCOUNT_A: GoogleAccount = GoogleAccount(id = "google-sub-a", displayLabel = "luiz@example.com")
        val ACCOUNT_B: GoogleAccount = GoogleAccount(id = "google-sub-b", displayLabel = "other@example.com")
    }
}
