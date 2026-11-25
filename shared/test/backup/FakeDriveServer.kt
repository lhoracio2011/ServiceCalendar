package app.servicecalendar.backup

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A minimal in-memory Drive on top of `MockEngine` for coordinator tests: `files.list`, `files.create`
 * and `files.update` (resumable: starting a session is not logged, the write is a `PUT` to the session
 * URI), `files.get?alt=media` and metadata `files.get` in `appDataFolder`. Every content write produces a
 * new revision `rev-<n>` (`headRevisionId`) and a new `modifiedTime`.
 *
 * - [log] lists the executed requests: `list`, `create`, `update:<id>`, `get:<id>` (content), `meta:<id>` (metadata).
 * - [interceptor] returns a scripted response instead of the normal one (failure, delay); `null` means handle normally.
 * - [holdNextRequest] delays the next request, to verify that only one operation runs at a time.
 */
class FakeDriveServer(private val token: String = FakeGoogleAuthProvider.TOKEN) {
    class StoredFile(val id: String, var content: String, var modified: Instant, var revision: String)

    private val mutex = Mutex()
    private val stored = LinkedHashMap<String, StoredFile>()
    private var nextId = 1
    private var tick = 0
    private var nextRevision = 1
    private val requests = mutableListOf<String>()
    private val sessions = mutableMapOf<String, String>()
    private var gate: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>? = null

    /** A scripted response for a request label (`list`, `create`, `update:<id>`, `get:<id>`, `meta:<id>`). */
    var interceptor: (suspend MockRequestHandleScope.(label: String, request: HttpRequestData) -> HttpResponseData?)? = null

    val log: List<String> get() = requests.toList()
    val fileIds: List<String> get() = stored.keys.toList()

    fun content(id: String): String? = stored[id]?.content

    /** The current content revision (`headRevisionId`) of file [id]. */
    fun revision(id: String): String? = stored[id]?.revision

    /** Adds a backup file as if another device created it. */
    fun put(content: String, modified: Instant = nextModified()): String {
        val id = "drive-file-${nextId++}"
        stored[id] = StoredFile(id, content, modified, newRevision())
        return id
    }

    /** Overwrites file [id] as if another device of the same account updated the backup. */
    fun overwrite(id: String, content: String, modified: Instant = nextModified()) {
        val file = checkNotNull(stored[id]) { "No file to overwrite" }
        file.content = content
        file.modified = modified
        file.revision = newRevision()
    }

    /** Deletes a file as if the user cleared the app data on Drive. */
    fun delete(id: String) {
        stored.remove(id)
    }

    /**
     * The next request waits for `release`; `entered` completes when it arrives.
     * Returns (`entered`, `release`).
     */
    fun holdNextRequest(): Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>> {
        val pair = CompletableDeferred<Unit>() to CompletableDeferred<Unit>()
        gate = pair
        return pair
    }

    fun client(auth: GoogleAuthProvider): DriveBackupClient = DriveBackupClient(
        http = HttpClient(MockEngine { request -> handle(request) }),
        auth = auth,
        retryPolicy = DriveRetryPolicy(maxAttempts = 2, initialDelay = 1.seconds, maxDelay = 1.seconds),
        delay = { },
    )

    private fun nextModified(): Instant = BASE_TIME + (tick++).seconds

    private fun newRevision(): String = "rev-${nextRevision++}"

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        gate?.let { (entered, release) ->
            gate = null
            entered.complete(Unit)
            release.await()
        }
        val path = request.url.encodedPath
        if (request.method != HttpMethod.Put && request.url.parameters["uploadType"] == "resumable") {
            if (request.headers[HttpHeaders.Authorization] != "Bearer $token") return respond("", HttpStatusCode.Unauthorized)
            val label = when {
                request.method == HttpMethod.Post && path == "/upload/drive/v3/files" -> "create"
                request.method == HttpMethod.Patch && path.startsWith("/upload/drive/v3/files/") -> "update:" + path.substringAfterLast('/')
                else -> error("Unexpected request ${request.method.value} $path")
            }
            val sessionId = "session-${sessions.size + 1}"
            sessions[sessionId] = label
            val uri = "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=$sessionId"
            return respond("", HttpStatusCode.OK, headersOf(HttpHeaders.Location, uri))
        }
        val label = when {
            request.method == HttpMethod.Put && path == "/upload/drive/v3/files" ->
                sessions[request.url.parameters["upload_id"]] ?: error("Unknown upload session")
            request.method == HttpMethod.Get && path == "/drive/v3/files" -> "list"
            request.method == HttpMethod.Get && path.startsWith("/drive/v3/files/") ->
                (if (request.url.parameters["alt"] == "media") "get:" else "meta:") + path.substringAfterLast('/')
            else -> error("Unexpected request ${request.method.value} $path")
        }
        mutex.withLock { requests += label }
        interceptor?.let { intercept -> intercept(label, request)?.let { return it } }
        if (request.headers[HttpHeaders.Authorization] != "Bearer $token") return respond("", HttpStatusCode.Unauthorized)
        return mutex.withLock {
            when {
                label == "list" -> {
                    val files = stored.values.sortedByDescending { it.modified }.joinToString(",") { it.metadata() }
                    json("""{"files":[$files]}""")
                }
                label == "create" -> {
                    val file = StoredFile("drive-file-${nextId++}", bodyText(request), nextModified(), newRevision())
                    stored[file.id] = file
                    json(file.metadata())
                }
                label.startsWith("update:") -> {
                    val file = stored[label.removePrefix("update:")] ?: return@withLock notFound()
                    file.content = bodyText(request)
                    file.modified = nextModified()
                    file.revision = newRevision()
                    json(file.metadata())
                }
                label.startsWith("meta:") -> {
                    val file = stored[label.removePrefix("meta:")] ?: return@withLock notFound()
                    json(file.metadata())
                }
                else -> {
                    val file = stored[label.removePrefix("get:")] ?: return@withLock notFound()
                    respond(file.content, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
    }

    private fun StoredFile.metadata(): String = """{"id":"$id","modifiedTime":"$modified","headRevisionId":"$revision"}"""

    private fun MockRequestHandleScope.json(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.notFound(): HttpResponseData = respond("", HttpStatusCode.NotFound)

    companion object {
        val BASE_TIME: Instant = Instant.parse("2026-11-01T00:00:00Z")

        fun bodyText(request: HttpRequestData): String =
            (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

        /** The file content in a write request (the body of the `PUT`). */
        fun multipartContent(request: HttpRequestData): String = bodyText(request)
    }
}
