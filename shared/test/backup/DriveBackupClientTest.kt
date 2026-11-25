package app.servicecalendar.backup

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** A `MockEngine` response to one request. */
private typealias Responder = MockRequestHandleScope.(HttpRequestData) -> HttpResponseData

private fun responders(vararg responses: Responder): List<Responder> = responses.toList()

/**
 * The Drive client on a Ktor `MockEngine`.
 * Each test checks the exact shape of the request: method, path, parameters, headers and body.
 */
class DriveBackupClientTest {
    private val token = FakeGoogleAuthProvider.TOKEN
    private val json = SnapshotCodec.encode(sampleSnapshot())
    private val listQuery = "name='service-calendar-backup.json' and trashed=false"

    private class Recorded(
        val method: HttpMethod,
        val url: Url,
        val headers: Headers,
        val contentType: ContentType?,
        val body: ByteArray,
    ) {
        val text: String get() = body.decodeToString()
    }

    private class Env(val client: DriveBackupClient, val auth: FakeGoogleAuthProvider) {
        val requests = mutableListOf<Recorded>()
        val delays = mutableListOf<Duration>()
    }

    private fun MockRequestHandleScope.ok(body: String, headers: Headers = jsonHeaders): HttpResponseData =
        respond(body, HttpStatusCode.OK, headers)

    private fun MockRequestHandleScope.status(code: Int, body: String = "", headers: Headers = jsonHeaders): HttpResponseData =
        respond(body, HttpStatusCode.fromValue(code), headers)

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json; charset=UTF-8")

    /** A client over a scripted list of responses: request n gets response n; an extra request fails the test. */
    private fun driveTest(
        auth: FakeGoogleAuthProvider = FakeGoogleAuthProvider(),
        maxDownloadBytes: Int = SnapshotLimits.MAX_JSON_CHARS,
        responses: List<Responder> = emptyList(),
        block: suspend Env.() -> Unit,
    ) {
        val queue = ArrayDeque(responses)
        lateinit var env: Env
        val engine = MockEngine { request ->
            val body = when (val content = request.body) {
                is OutgoingContent.ByteArrayContent -> content.bytes()
                is OutgoingContent.NoContent -> ByteArray(0)
                else -> error("Unexpected body type ${content::class}")
            }
            env.requests += Recorded(request.method, request.url, request.headers, request.body.contentType, body)
            val next = queue.removeFirstOrNull() ?: error("Unexpected extra request ${request.method.value} ${request.url.encodedPath}")
            next(request)
        }
        val client = DriveBackupClient(
            http = HttpClient(engine),
            auth = auth,
            retryPolicy = DriveRetryPolicy(maxAttempts = 3, initialDelay = 1.seconds, maxDelay = 4.seconds),
            delay = { env.delays += it },
            maxDownloadBytes = maxDownloadBytes,
        )
        env = Env(client, auth)
        runBlocking { withTimeout(30_000) { env.block() } }
        assertTrue(queue.isEmpty(), "Not all scripted responses were used: ${queue.size}")
        env.assertTokenOnlyInAuthorizationHeader()
    }

    /** The token appears only in `Authorization: Bearer ...`, never in the URL or other headers. */
    private fun Env.assertTokenOnlyInAuthorizationHeader() {
        for (r in requests) {
            assertFalse(r.url.toString().contains("secret"), "Token in URL: ${r.url.encodedPath}")
            for ((name, values) in r.headers.entries()) {
                if (name.equals(HttpHeaders.Authorization, ignoreCase = true)) continue
                assertFalse(values.any { "secret" in it }, "Token in header $name")
            }
            assertFalse(r.text.contains(token), "Token in request body")
        }
    }

    private fun Recorded.assertAuthorized(expected: String = token) {
        assertEquals(listOf("Bearer $expected"), headers.getAll(HttpHeaders.Authorization))
    }

    private fun fileJson(id: String, modified: String) = """{"id":"$id","modifiedTime":"$modified"}"""

    @Test
    fun `list searches appDataFolder by name and follows pages`() = driveTest(
        responses = responders(
            { ok("""{"nextPageToken":"page-2","files":[${fileJson("old", "2026-11-01T10:00:00Z")}]}""") },
            { ok("""{"files":[${fileJson("new", "2026-11-09T10:00:00.250Z")}],"kind":"drive#fileList"}""") },
        ),
    ) {
        val result = client.list()

        assertIs<DriveResult.Success<List<DriveFile>>>(result)
        assertEquals(
            listOf(
                DriveFile("new", Instant.parse("2026-11-09T10:00:00.250Z")),
                DriveFile("old", Instant.parse("2026-11-01T10:00:00Z")),
            ),
            result.value,
        )
        assertEquals(2, requests.size)
        requests.forEachIndexed { index, r ->
            assertEquals(HttpMethod.Get, r.method)
            assertEquals("www.googleapis.com", r.url.host)
            assertEquals("/drive/v3/files", r.url.encodedPath)
            assertEquals("appDataFolder", r.url.parameters["spaces"])
            assertEquals(listQuery, r.url.parameters["q"])
            assertEquals("nextPageToken,files(id,modifiedTime,headRevisionId)", r.url.parameters["fields"])
            assertEquals("modifiedTime desc", r.url.parameters["orderBy"])
            assertEquals("100", r.url.parameters["pageSize"])
            assertEquals(if (index == 0) null else "page-2", r.url.parameters["pageToken"])
            r.assertAuthorized()
            assertEquals(0, r.body.size)
        }
    }

    @Test
    fun `list without files is an empty result`() = driveTest(responses = responders({ ok("{}") })) {
        val result = client.list()
        assertIs<DriveResult.Success<List<DriveFile>>>(result)
        assertTrue(result.value.isEmpty())
    }

    @Test
    fun `list with a broken response is an invalid response`() = driveTest(responses = responders({ ok("not json") })) {
        val result = client.list()
        assertIs<DriveResult.Failure>(result)
        assertIs<DriveError.InvalidResponse>(result.error)
    }

    private val sessionUri = "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=sess-1"

    private fun MockRequestHandleScope.session(uri: String = sessionUri): HttpResponseData =
        respond("", HttpStatusCode.OK, headersOf(HttpHeaders.Location, uri))

    @Test
    fun `create starts a resumable session with appDataFolder parent and puts the content`() = driveTest(
        responses = responders({ session() }, { ok(fileJson("created-1", "2026-11-10T12:00:00Z")) }),
    ) {
        val result = client.create(json)

        assertIs<DriveResult.Success<DriveFile>>(result)
        assertEquals(DriveFile("created-1", Instant.parse("2026-11-10T12:00:00Z")), result.value)
        assertEquals(2, requests.size)
        val init = requests[0]
        assertEquals(HttpMethod.Post, init.method)
        assertEquals("/upload/drive/v3/files", init.url.encodedPath)
        assertEquals("resumable", init.url.parameters["uploadType"])
        assertEquals("id,modifiedTime,headRevisionId", init.url.parameters["fields"])
        assertEquals("application/json", init.headers["X-Upload-Content-Type"])
        assertEquals(json.encodeToByteArray().size.toString(), init.headers["X-Upload-Content-Length"])
        assertEquals(
            """{"name":"service-calendar-backup.json","parents":["appDataFolder"],"mimeType":"application/json"}""",
            init.text,
        )
        init.assertAuthorized()

        val put = requests[1]
        assertEquals(HttpMethod.Put, put.method)
        assertEquals(sessionUri, put.url.toString())
        assertEquals(json, put.text)
        assertEquals(ContentType.Application.Json.withParameter("charset", "UTF-8"), put.contentType)
        put.assertAuthorized()
    }

    @Test
    fun `create with a failed session start creates nothing and does not require a search`() = driveTest(
        responses = responders({ status(503) }, { status(503) }, { status(503) }),
    ) {
        val first = client.create(json)
        assertIs<DriveResult.Failure>(first)
        assertEquals(DriveError.Transient(503), first.error)
        assertEquals(3, requests.size)
        assertFalse(client.searchRequiredBeforeCreate)
    }

    @Test
    fun `create with a missing or foreign session location is an invalid response`() = driveTest(
        responses = responders(
            { respond("", HttpStatusCode.OK, jsonHeaders) },
            { session("https://evil.example/upload?id=1") },
        ),
    ) {
        assertIs<DriveError.InvalidResponse>((client.create(json) as DriveResult.Failure).error)
        assertIs<DriveError.InvalidResponse>((client.create(json) as DriveResult.Failure).error)
        assertFalse(client.searchRequiredBeforeCreate)
        assertEquals(2, requests.size) // the token was not sent to a foreign host
    }

    @Test
    fun `create with an IO failure has an unknown result and requires a search before the next create`() = driveTest(
        responses = responders(
            { session() },
            { throw kotlinx.io.IOException("connection reset") },
            { ok("""{"files":[${fileJson("maybe-created", "2026-11-10T12:00:00Z")}]}""") },
            { session() },
            { ok(fileJson("created-2", "2026-11-10T12:01:00Z")) },
        ),
    ) {
        val first = client.create(json)
        assertIs<DriveResult.Failure>(first)
        assertEquals(DriveError.UnknownCreateResult, first.error)
        assertTrue(client.searchRequiredBeforeCreate)

        // Creating again without a search is refused without any request to Drive.
        val refused = client.create(json)
        assertIs<DriveResult.Failure>(refused)
        assertEquals(DriveError.SearchRequired, refused.error)
        assertEquals(2, requests.size)

        assertIs<DriveResult.Success<List<DriveFile>>>(client.list())
        assertFalse(client.searchRequiredBeforeCreate)
        assertIs<DriveResult.Success<DriveFile>>(client.create(json))
        assertEquals(
            listOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Get, HttpMethod.Post, HttpMethod.Put),
            requests.map { it.method },
        )
    }

    @Test
    fun `create with a server error on the put is not retried because the file may exist`() = driveTest(
        responses = responders({ session() }, { status(503) }),
    ) {
        val result = client.create(json)
        assertIs<DriveResult.Failure>(result)
        assertEquals(DriveError.UnknownCreateResult, result.error)
        assertEquals(2, requests.size)
        assertTrue(delays.isEmpty())
        assertTrue(client.searchRequiredBeforeCreate)
    }

    @Test
    fun `create with an unreadable success response has an unknown result`() = driveTest(
        responses = responders({ session() }, { ok("<html>") }),
    ) {
        val result = client.create(json)
        assertIs<DriveResult.Failure>(result)
        assertEquals(DriveError.UnknownCreateResult, result.error)
    }

    @Test
    fun `create rejected with 429 is retried safely`() = driveTest(
        responses = responders(
            { status(429, headers = headersOf(HttpHeaders.RetryAfter, "2")) },
            { session() },
            { ok(fileJson("created-1", "2026-11-10T12:00:00Z")) },
        ),
    ) {
        assertIs<DriveResult.Success<DriveFile>>(client.create(json))
        assertEquals(listOf(2.seconds), delays)
        assertFalse(client.searchRequiredBeforeCreate)
    }

    @Test
    fun `update starts a resumable session by file id and puts the content`() = driveTest(
        responses = responders(
            { session() },
            { ok("""{"id":"file-1","modifiedTime":"2026-11-10T12:00:00Z","headRevisionId":"rev-after"}""") },
        ),
    ) {
        val result = client.update("file-1", json)

        assertIs<DriveResult.Success<DriveFile>>(result)
        assertEquals("file-1", result.value.id)
        assertEquals("rev-after", result.value.revisionMarker) // marker of the written content
        val init = requests[0]
        assertEquals(HttpMethod.Patch, init.method)
        assertEquals("/upload/drive/v3/files/file-1", init.url.encodedPath)
        assertEquals("resumable", init.url.parameters["uploadType"])
        assertEquals("id,modifiedTime,headRevisionId", init.url.parameters["fields"])
        assertEquals(json.encodeToByteArray().size.toString(), init.headers["X-Upload-Content-Length"])
        init.assertAuthorized()
        val put = requests[1]
        assertEquals(HttpMethod.Put, put.method)
        assertEquals(sessionUri, put.url.toString())
        assertEquals(json, put.text)
        put.assertAuthorized()
    }

    @Test
    fun `update with a failed content put is retried as a transient error`() = driveTest(
        responses = responders({ session() }, { status(503) }, { status(503) }, { status(503) }),
    ) {
        val result = client.update("file-1", json)
        assertIs<DriveResult.Failure>(result)
        assertEquals(DriveError.Transient(503), result.error)
        assertEquals(listOf(HttpMethod.Patch, HttpMethod.Put, HttpMethod.Put, HttpMethod.Put), requests.map { it.method })
    }

    @Test
    fun `update with a failed session start is reported`() = driveTest(responses = responders({ status(500) }, { status(500) }, { status(500) })) {
        val result = client.update("file-1", json)
        assertIs<DriveResult.Failure>(result)
        assertEquals(DriveError.Transient(500), result.error)
    }

    @Test
    fun `update of a missing file is not found`() = driveTest(responses = responders({ status(404) })) {
        val result = client.update("file-1", json)
        assertIs<DriveResult.Failure>(result)
        assertEquals(DriveError.NotFound, result.error)
    }

    @Test
    fun `upload over the download limit is refused without a request`() = driveTest(maxDownloadBytes = 10) {
        val update = client.update("file-1", json)
        val create = client.create(json)
        assertEquals(DriveError.TooLarge, (update as DriveResult.Failure).error)
        assertEquals(DriveError.TooLarge, (create as DriveResult.Failure).error)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `metadata reads id modified time and head revision without the content`() = driveTest(
        responses = responders(
            { ok("""{"id":"file-1","modifiedTime":"2026-11-10T12:00:00Z","headRevisionId":"rev-7"}""") },
        ),
    ) {
        val result = client.metadata("file-1", FakeGoogleAuthProvider.ACCOUNT_A.id)

        assertIs<DriveResult.Success<DriveFile>>(result)
        assertEquals(DriveFile("file-1", Instant.parse("2026-11-10T12:00:00Z"), "rev-7"), result.value)
        assertEquals("rev-7", result.value.revisionMarker)
        val r = requests.single()
        assertEquals(HttpMethod.Get, r.method)
        assertEquals("/drive/v3/files/file-1", r.url.encodedPath)
        assertEquals("id,modifiedTime,headRevisionId", r.url.parameters["fields"])
        assertNull(r.url.parameters["alt"])
        r.assertAuthorized()
        assertEquals(0, r.body.size)
    }

    @Test
    fun `metadata of a missing file is not found and of another file is invalid`() = driveTest(
        responses = responders({ status(404) }, { ok(fileJson("other", "2026-11-10T12:00:00Z")) }),
    ) {
        assertEquals(DriveError.NotFound, (client.metadata("file-1") as DriveResult.Failure).error)
        assertIs<DriveError.InvalidResponse>((client.metadata("file-1") as DriveResult.Failure).error)
    }

    @Test
    fun `metadata for an account that is no longer current stops before any request`() = driveTest(
        auth = FakeGoogleAuthProvider(account = FakeGoogleAuthProvider.ACCOUNT_B),
    ) {
        assertEquals(
            DriveError.AccountChanged,
            (client.metadata("file-1", FakeGoogleAuthProvider.ACCOUNT_A.id) as DriveResult.Failure).error,
        )
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `revision marker falls back to the modified time`() {
        val modified = Instant.parse("2026-11-10T12:00:00Z")
        assertEquals("rev-1", DriveFile("a", modified, "rev-1").revisionMarker)
        assertEquals("modifiedTime:2026-11-10T12:00:00Z", DriveFile("a", modified).revisionMarker)
        assertNull(DriveFile("a", null).revisionMarker)
        // The fallback marker differs from a revision marker and changes with the modified time.
        assertTrue(DriveFile("a", modified).revisionMarker != DriveFile("a", modified + 1.seconds).revisionMarker)
    }

    @Test
    fun `download reads the media of the file`() = driveTest(responses = responders({ ok(json) })) {
        val result = client.download("file-1")

        assertIs<DriveResult.Success<String>>(result)
        assertEquals(json, result.value)
        val r = requests.single()
        assertEquals(HttpMethod.Get, r.method)
        assertEquals("/drive/v3/files/file-1", r.url.encodedPath)
        assertEquals("media", r.url.parameters["alt"])
        r.assertAuthorized()
    }

    @Test
    fun `download stops at the byte limit`() = driveTest(
        maxDownloadBytes = 100,
        responses = responders(
            { ok("x".repeat(101), headersOf()) },
            { ok("y".repeat(1000), headersOf(HttpHeaders.ContentLength, "1000")) },
            { ok("z".repeat(100), headersOf()) },
        ),
    ) {
        assertEquals(DriveError.TooLarge, (client.download("a") as DriveResult.Failure).error)
        assertEquals(DriveError.TooLarge, (client.download("b") as DriveResult.Failure).error)
        assertEquals("z".repeat(100), (client.download("c") as DriveResult.Success).value)
    }

    @Test
    fun `download with an IO failure is unavailable`() = driveTest(
        responses = responders({ throw kotlinx.io.IOException("offline") }),
    ) {
        assertEquals(DriveError.Unavailable, (client.download("file-1") as DriveResult.Failure).error)
    }

    @Test
    fun `401 invalidates the token and retries once with a fresh one`() = driveTest(
        auth = FakeGoogleAuthProvider().apply {
            queueTokens(AccessTokenResult.Token("secret-old"), AccessTokenResult.Token("secret-new"))
        },
        responses = responders({ status(401) }, { ok("{}") }),
    ) {
        assertIs<DriveResult.Success<List<DriveFile>>>(client.list())
        assertEquals(listOf("secret-old"), auth.invalidated)
        requests[0].assertAuthorized("secret-old")
        requests[1].assertAuthorized("secret-new")
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `401 twice needs user action`() = driveTest(responses = responders({ status(401) }, { status(401) })) {
        val result = client.list()
        assertEquals(DriveError.NeedsUserAction, (result as DriveResult.Failure).error)
        assertEquals(2, requests.size)
    }

    @Test
    fun `token that needs user action stops before any request`() = driveTest(
        auth = FakeGoogleAuthProvider().apply { tokenResult = AccessTokenResult.NeedsUserAction },
    ) {
        assertEquals(DriveError.NeedsUserAction, (client.list() as DriveResult.Failure).error)
        assertEquals(DriveError.NeedsUserAction, (client.create(json) as DriveResult.Failure).error)
        assertFalse(client.searchRequiredBeforeCreate)
        assertEquals(0, auth.consentRequests)
    }

    @Test
    fun `operation for an account that is no longer current stops before any request`() = driveTest(
        responses = responders({ ok("{}") }),
    ) {
        val a = FakeGoogleAuthProvider.ACCOUNT_A.id
        assertIs<DriveResult.Success<*>>(client.list(accountId = a))
        auth.account = FakeGoogleAuthProvider.ACCOUNT_B

        assertEquals(DriveError.AccountChanged, (client.list(a) as DriveResult.Failure).error)
        assertEquals(DriveError.AccountChanged, (client.create(json, a) as DriveResult.Failure).error)
        assertEquals(DriveError.AccountChanged, (client.update("file-1", json, a) as DriveResult.Failure).error)
        assertEquals(DriveError.AccountChanged, (client.download("file-1", a) as DriveResult.Failure).error)
        assertFalse(client.searchRequiredBeforeCreate)
        assertEquals(1, requests.size)
    }

    @Test
    fun `missing account and token failures are typed`() = driveTest(
        auth = FakeGoogleAuthProvider(account = null),
    ) {
        assertEquals(DriveError.NotSignedIn, (client.list() as DriveResult.Failure).error)
        auth.tokenResult = AccessTokenResult.Failure(retryable = true, diagnostic = "network")
        assertEquals(DriveError.AuthFailure(retryable = true), (client.download("x") as DriveResult.Failure).error)
    }

    @Test
    fun `403 for a missing scope needs user action`() = driveTest(
        responses = responders({
            status(
                403,
                """{"error":{"code":403,"errors":[{"reason":"insufficientPermissions"}],"status":"PERMISSION_DENIED",
                   "details":[{"reason":"ACCESS_TOKEN_SCOPE_INSUFFICIENT"}]}}""",
            )
        }),
    ) {
        assertEquals(DriveError.NeedsUserAction, (client.list() as DriveResult.Failure).error)
    }

    @Test
    fun `403 for a file this project may not open is not found`() = driveTest(
        responses = responders({
            status(403, """{"error":{"code":403,"errors":[{"reason":"appNotAuthorizedToFile"}],"status":"PERMISSION_DENIED"}}""")
        }),
    ) {
        assertEquals(DriveError.NotFound, (client.metadata("f") as DriveResult.Failure).error)
    }

    @Test
    fun `403 for a full storage among other reasons is still a full storage`() = driveTest(
        responses = responders({
            status(403, """{"error":{"errors":[{"reason":"domainPolicy"},{"reason":"storageQuotaExceeded"}],"status":"PERMISSION_DENIED"}}""")
        }),
    ) {
        assertEquals(DriveError.Forbidden("storageQuotaExceeded"), (client.update("f", json) as DriveResult.Failure).error)
    }

    @Test
    fun `403 for a full storage is forbidden with the reason`() = driveTest(
        responses = responders({ status(403, """{"error":{"errors":[{"reason":"storageQuotaExceeded"}]}}""") }),
    ) {
        assertEquals(DriveError.Forbidden("storageQuotaExceeded"), (client.update("f", json) as DriveResult.Failure).error)
    }

    @Test
    fun `server errors and rate limits are retried with bounded backoff`() = driveTest(
        responses = responders(
            { status(503) },
            { status(403, """{"error":{"errors":[{"reason":"userRateLimitExceeded"}]}}""") },
            { ok("{}") },
        ),
    ) {
        assertIs<DriveResult.Success<List<DriveFile>>>(client.list())
        assertEquals(listOf(1.seconds, 2.seconds), delays)
    }

    @Test
    fun `retries end after the attempt limit`() = driveTest(
        responses = responders(
            { status(500) },
            { status(502) },
            { status(429, headers = headersOf(HttpHeaders.RetryAfter, "3600")) },
        ),
    ) {
        assertEquals(DriveError.Transient(429), (client.download("f") as DriveResult.Failure).error)
        assertEquals(3, requests.size)
        // Retry-After cannot stretch the wait beyond maxDelay.
        assertEquals(listOf(1.seconds, 2.seconds), delays)
    }

    @Test
    fun `list with an IO failure is unavailable without a retry`() = driveTest(
        responses = responders({ throw kotlinx.io.IOException("offline") }),
    ) {
        assertEquals(DriveError.Unavailable, (client.list() as DriveResult.Failure).error)
    }

    @Test
    fun `other client errors are typed http errors`() = driveTest(responses = responders({ status(400) })) {
        assertEquals(DriveError.HttpError(400), (client.list() as DriveResult.Failure).error)
    }

    @Test
    fun `newest copy that passes validation is chosen`() = driveTest(
        responses = responders(
            { ok("""{"schemaVersion":1,"broken":""") },
            { status(404) },
            { ok(json) },
        ),
    ) {
        val files = listOf(
            DriveFile("oldest", Instant.parse("2026-11-01T00:00:00Z")),
            DriveFile("newest", Instant.parse("2026-11-09T00:00:00Z")),
            DriveFile("gone", Instant.parse("2026-11-08T00:00:00Z")),
            DriveFile("middle", Instant.parse("2026-11-05T00:00:00Z")),
        )

        val result = client.findNewestValidCopy(files)

        assertIs<DriveResult.Success<ValidatedCopy?>>(result)
        val copy = assertNotNull(result.value)
        assertEquals("middle", copy.file.id)
        assertEquals(sampleSnapshot(), copy.snapshot)
        // From the newest down to the first valid one; the oldest is never downloaded.
        assertEquals(
            listOf("/drive/v3/files/newest", "/drive/v3/files/gone", "/drive/v3/files/middle"),
            requests.map { it.url.encodedPath },
        )
    }

    @Test
    fun `no valid copy among several is null`() = driveTest(
        responses = responders({ ok("[]") }, { ok("{}") }),
    ) {
        val result = client.findNewestValidCopy(
            listOf(DriveFile("a", Instant.parse("2026-11-09T00:00:00Z")), DriveFile("b", null)),
        )
        assertIs<DriveResult.Success<ValidatedCopy?>>(result)
        assertNull(result.value)
    }

    @Test
    fun `download failure while choosing a copy is a failure`() = driveTest(
        responses = responders({ throw kotlinx.io.IOException("offline") }),
    ) {
        val result = client.findNewestValidCopy(listOf(DriveFile("a", null), DriveFile("b", null)))
        assertEquals(DriveError.Unavailable, (result as DriveResult.Failure).error)
    }

    @Test
    fun `results and errors hide tokens file ids and content in toString`() {
        val texts = listOf(
            DriveFile("file-secret-id", null).toString(),
            DriveFile("x", null, "rev-secret-id").toString(),
            DriveResult.Success(json).toString(),
            DriveResult.Success(DriveFile("file-secret-id", null)).toString(),
            ValidatedCopy(DriveFile("file-secret-id", null), sampleSnapshot()).toString(),
            DriveResult.Failure(DriveError.Forbidden("storageQuotaExceeded")).toString(),
        )
        for (text in texts) {
            assertFalse("file-secret-id" in text, text)
            assertFalse("rev-secret-id" in text, text)
            assertFalse("Огляд" in text, text)
        }
    }
}
