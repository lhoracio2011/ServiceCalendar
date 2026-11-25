package app.servicecalendar.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** The auth interface: secret masking and the callback bridge used from Swift. */
class GoogleAuthProviderTest {
    private val secret = "ya29.very-secret-token"

    @Test
    fun `token result hides the token in toString`() {
        val token = AccessTokenResult.Token(secret)
        assertEquals(secret, token.accessToken)
        assertFalse(secret in token.toString())
        assertFalse("very-secret" in token.toString())
    }

    @Test
    fun `blank token is rejected`() {
        assertFailsWith<IllegalArgumentException> { AccessTokenResult.Token(" ") }
    }

    @Test
    fun `account hides the id and the label in toString`() {
        val account = GoogleAccount(id = "1234567890", displayLabel = "luiz@example.com")
        val text = account.toString()
        assertFalse("1234567890" in text)
        assertFalse("luiz" in text)
        assertEquals(GoogleAccount("1234567890", "luiz@example.com"), account)
    }

    @Test
    fun `blank account id is rejected`() {
        assertFailsWith<IllegalArgumentException> { GoogleAccount(id = "", displayLabel = "x") }
    }

    @Test
    fun `consent granted hides the account in toString`() {
        val granted = ConsentResult.Granted(GoogleAccount("1234567890", "luiz@example.com"))
        assertFalse("1234567890" in granted.toString())
        assertFalse("luiz" in granted.toString())
    }

    /** A bridge that answers synchronously, never answers, or answers twice, depending on the script. */
    private class ScriptedBridge : GoogleAuthBridge {
        var account: GoogleAccount? = null
        var token: AccessTokenResult? = AccessTokenResult.NotSignedIn
        var consent: ConsentResult? = ConsentResult.Cancelled
        var throwOnToken: Boolean = false
        var answerTwice: Boolean = false
        val invalidated = mutableListOf<String>()
        var signOuts = 0
        var consentCalls = 0

        override fun currentAccount(callback: GoogleAccountCallback) {
            callback.onResult(account)
        }

        override fun fetchAccessToken(callback: AccessTokenCallback) {
            if (throwOnToken) error("platform failure")
            val result = token ?: return // never answers
            callback.onResult(result)
            if (answerTwice) callback.onResult(AccessTokenResult.NotSignedIn)
        }

        override fun invalidateAccessToken(accessToken: String, callback: AuthCompletionCallback) {
            invalidated += accessToken
            callback.onComplete()
        }

        override fun requestConsent(callback: ConsentCallback) {
            consentCalls++
            val result = consent ?: return
            callback.onResult(result)
        }

        override fun signOut(callback: AuthCompletionCallback) {
            signOuts++
            callback.onComplete()
        }
    }

    private fun bridged(bridge: ScriptedBridge) =
        BridgedGoogleAuthProvider(bridge, callTimeout = 200.milliseconds)

    private fun <T> run(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    @Test
    fun `bridge delivers the account and the token`() = run {
        val bridge = ScriptedBridge()
        val account = GoogleAccount("sub-1", "label")
        bridge.account = account
        val token = AccessTokenResult.Token(secret)
        bridge.token = token
        val provider = bridged(bridge)

        assertEquals(account, provider.currentAccount())
        assertSame(token, provider.accessToken())
    }

    @Test
    fun `bridge answering twice keeps the first answer`() = run {
        val bridge = ScriptedBridge()
        val token = AccessTokenResult.Token(secret)
        bridge.token = token
        bridge.answerTwice = true

        assertSame(token, bridged(bridge).accessToken())
    }

    @Test
    fun `bridge that never answers the token request ends with a retryable failure`() = run {
        val bridge = ScriptedBridge()
        bridge.token = null

        val result = bridged(bridge).accessToken()

        assertIs<AccessTokenResult.Failure>(result)
        assertTrue(result.retryable)
    }

    @Test
    fun `bridge that throws ends with a failure instead of an exception`() = run {
        val bridge = ScriptedBridge()
        bridge.throwOnToken = true

        val result = bridged(bridge).accessToken()

        assertIs<AccessTokenResult.Failure>(result)
        // The platform exception message may contain anything, so it must not be carried over.
        assertFalse("platform failure" in result.diagnostic)
    }

    @Test
    fun `consent waits for the visible UI without a timeout`() = run {
        val bridge = ScriptedBridge()
        val account = GoogleAccount("sub-1", "label")
        bridge.consent = ConsentResult.Granted(account)

        val result = bridged(bridge).requestConsent()

        assertIs<ConsentResult.Granted>(result)
        assertEquals(account, result.account)
        assertEquals(1, bridge.consentCalls)
    }

    @Test
    fun `invalidate and sign out reach the bridge`() = run {
        val bridge = ScriptedBridge()
        val provider = bridged(bridge)

        provider.invalidateAccessToken(secret)
        provider.signOut()

        assertEquals(listOf(secret), bridge.invalidated)
        assertEquals(1, bridge.signOuts)
    }

    @Test
    fun `account lookup that never answers means not connected`() = run {
        val bridge = object : GoogleAuthBridge by ScriptedBridge() {
            override fun currentAccount(callback: GoogleAccountCallback) = Unit
        }
        assertNull(BridgedGoogleAuthProvider(bridge, callTimeout = 100.milliseconds).currentAccount())
    }
}
