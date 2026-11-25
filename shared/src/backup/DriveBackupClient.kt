package app.servicecalendar.backup

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.takeFrom
import io.ktor.utils.io.readBuffer
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class DriveFile(
    val id: String,
    val modifiedTime: Instant?,
    val headRevisionId: String? = null,
) {
    // headRevisionId changes only with new content, so metadata-only changes by Drive do not trigger a conflict. modifiedTime is the fallback.
    val revisionMarker: String?
        get() = headRevisionId ?: modifiedTime?.let { "modifiedTime:$it" }

    override fun equals(other: Any?): Boolean =
        other is DriveFile && other.id == id && other.modifiedTime == modifiedTime && other.headRevisionId == headRevisionId

    override fun hashCode(): Int = 31 * (31 * id.hashCode() + (modifiedTime?.hashCode() ?: 0)) + (headRevisionId?.hashCode() ?: 0)

    override fun toString(): String = "DriveFile(id=***, modifiedTime=$modifiedTime, revision=${if (headRevisionId == null) "none" else "***"})"
}

class ValidatedCopy(val file: DriveFile, val snapshot: BackupSnapshot) {
    override fun toString(): String = "ValidatedCopy(modifiedTime=${file.modifiedTime})"
}

sealed interface DriveError {
    data object NotSignedIn : DriveError

    data object AccountChanged : DriveError

    data object NeedsUserAction : DriveError

    data class AuthFailure(val retryable: Boolean) : DriveError

    data object NotFound : DriveError

    data class Forbidden(val reason: String) : DriveError

    data class Transient(val httpStatus: Int) : DriveError

    data object Unavailable : DriveError

    data object UnknownCreateResult : DriveError

    data object SearchRequired : DriveError

    data object TooLarge : DriveError

    data class InvalidResponse(val detail: String) : DriveError

    data class HttpError(val httpStatus: Int) : DriveError
}

sealed interface DriveResult<out T> {
    class Success<out T>(val value: T) : DriveResult<T> {
        override fun equals(other: Any?): Boolean = other is Success<*> && other.value == value

        override fun hashCode(): Int = value.hashCode()

        override fun toString(): String = "Success"
    }

    data class Failure(val error: DriveError) : DriveResult<Nothing>
}

class DriveRetryPolicy(
    val maxAttempts: Int = 4,
    val initialDelay: Duration = 1.seconds,
    val maxDelay: Duration = 16.seconds,
) {
    init {
        require(maxAttempts >= 1) { "At least one attempt is required" }
        require(initialDelay >= Duration.ZERO && maxDelay >= initialDelay) { "Invalid retry delays" }
    }

    internal fun delayBeforeRetry(failedAttempt: Int, retryAfter: Duration?): Duration {
        val exponential = initialDelay * (1 shl (failedAttempt - 1).coerceIn(0, 20))
        return (retryAfter ?: exponential).coerceAtMost(maxDelay)
    }
}

/**
 * Tokens are fetched per request and sent only in the Authorization header; nothing is logged or stored.
 * Creation is not idempotent: an unknown outcome forces a search before the next create ([DriveError.SearchRequired]).
 * Uploads use the resumable protocol for all sizes, and oversized content is never uploaded, so Drive never holds a copy that cannot be restored.
 */
@OptIn(ExperimentalAtomicApi::class)
class DriveBackupClient(
    private val http: HttpClient,
    private val auth: GoogleAuthProvider,
    private val retryPolicy: DriveRetryPolicy = DriveRetryPolicy(),
    private val delay: suspend (Duration) -> Unit = { kotlinx.coroutines.delay(it) },
    private val maxDownloadBytes: Int = SnapshotLimits.MAX_JSON_CHARS,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    private val searchRequired = AtomicBoolean(false)

    val searchRequiredBeforeCreate: Boolean get() = searchRequired.load()

    suspend fun list(accountId: String? = null): DriveResult<List<DriveFile>> {
        val files = mutableListOf<DriveFile>()
        var pageToken: String? = null
        repeat(MAX_LIST_PAGES) {
            val page = call(Operation.LIST, accountId, configure = {
                method = HttpMethod.Get
                url {
                    takeFrom(baseUrl)
                    appendPathSegments("drive", "v3", "files")
                    parameters.append("spaces", "appDataFolder")
                    parameters.append("q", "name='$FILE_NAME' and trashed=false")
                    parameters.append("fields", "nextPageToken,files($FILE_FIELDS)")
                    parameters.append("orderBy", "modifiedTime desc")
                    parameters.append("pageSize", "100")
                    pageToken?.let { parameters.append("pageToken", it) }
                }
            }) { response -> parseJson(response, MAX_METADATA_BYTES) { parseFileList(it) } }
            when (page) {
                is DriveResult.Failure -> return page
                is DriveResult.Success -> {
                    files += page.value.files
                    pageToken = page.value.nextPageToken
                    if (pageToken == null) {
                        searchRequired.store(false)
                        return DriveResult.Success(files.sortedWith(NEWEST_FIRST))
                    }
                }
            }
        }
        return DriveResult.Failure(DriveError.InvalidResponse("too many pages"))
    }

    suspend fun create(json: String, accountId: String? = null): DriveResult<DriveFile> {
        if (searchRequired.load()) return DriveResult.Failure(DriveError.SearchRequired)
        val content = json.encodeToByteArray()
        if (content.size > maxDownloadBytes) return DriveResult.Failure(DriveError.TooLarge)
        val session = startSession(Operation.UPLOAD_INIT, HttpMethod.Post, null, CREATE_METADATA, content.size, accountId)
        if (session is DriveResult.Failure) return session
        val result = putContent(Operation.CREATE, (session as DriveResult.Success).value, content, accountId)
        if (result is DriveResult.Failure && result.error == DriveError.UnknownCreateResult) searchRequired.store(true)
        return result
    }

    suspend fun update(fileId: String, json: String, accountId: String? = null): DriveResult<DriveFile> {
        val content = json.encodeToByteArray()
        if (content.size > maxDownloadBytes) return DriveResult.Failure(DriveError.TooLarge)
        val session = startSession(Operation.UPLOAD_INIT, HttpMethod.Patch, fileId, "{}", content.size, accountId)
        if (session is DriveResult.Failure) return session
        return putContent(Operation.UPDATE, (session as DriveResult.Success).value, content, accountId)
    }

    private suspend fun startSession(
        operation: Operation,
        method: HttpMethod,
        fileId: String?,
        metadata: String,
        size: Int,
        accountId: String?,
    ): DriveResult<String> = call(operation, accountId, configure = {
        this.method = method
        url {
            takeFrom(baseUrl)
            appendPathSegments("upload", "drive", "v3", "files")
            if (fileId != null) appendPathSegments(fileId)
            // The session URI is accepted only from the same origin as the base URL, so the token never reaches another host.
            parameters.append("uploadType", "resumable")
            parameters.append("fields", FILE_FIELDS)
        }
        header("X-Upload-Content-Type", ContentType.Application.Json.toString())
        header("X-Upload-Content-Length", size.toString())
        setBody(ByteArrayContent(metadata.encodeToByteArray(), JSON_UTF8))
    }) { response ->
        val location = response.headers[HttpHeaders.Location]
        if (location != null && sameOrigin(location)) {
            DriveResult.Success(location)
        } else {
            DriveResult.Failure(DriveError.InvalidResponse("missing upload session URI"))
        }
    }

    private suspend fun putContent(
        operation: Operation,
        sessionUri: String,
        content: ByteArray,
        accountId: String?,
    ): DriveResult<DriveFile> = call(operation, accountId, configure = {
        method = HttpMethod.Put
        url { takeFrom(sessionUri) }
        setBody(ByteArrayContent(content, JSON_UTF8))
    }) { response -> parseJson(response, MAX_METADATA_BYTES) { parseFile(it) } }

    private fun sameOrigin(location: String): Boolean = try {
        val expected = Url(baseUrl)
        val actual = Url(location)
        actual.protocol == expected.protocol && actual.host == expected.host && actual.port == expected.port
    } catch (e: Exception) {
        false
    }

    suspend fun metadata(fileId: String, accountId: String? = null): DriveResult<DriveFile> =
        call(Operation.GET, accountId, configure = {
            method = HttpMethod.Get
            url {
                takeFrom(baseUrl)
                appendPathSegments("drive", "v3", "files", fileId)
                parameters.append("fields", FILE_FIELDS)
            }
        }) { response ->
            parseJson(response, MAX_METADATA_BYTES) { obj -> parseFile(obj)?.takeIf { it.id == fileId } }
        }

    suspend fun download(fileId: String, accountId: String? = null): DriveResult<String> =
        call(Operation.GET, accountId, configure = {
            method = HttpMethod.Get
            url {
                takeFrom(baseUrl)
                appendPathSegments("drive", "v3", "files", fileId)
                parameters.append("alt", "media")
            }
        }) { response ->
            val bytes = readBounded(response, maxDownloadBytes) ?: return@call DriveResult.Failure(DriveError.TooLarge)
            try {
                DriveResult.Success(bytes.decodeToString(throwOnInvalidSequence = true))
            } catch (e: CharacterCodingException) {
                DriveResult.Failure(DriveError.InvalidResponse("not UTF-8"))
            }
        }

    suspend fun findNewestValidCopy(files: List<DriveFile>, accountId: String? = null): DriveResult<ValidatedCopy?> {
        for (file in files.sortedWith(NEWEST_FIRST)) {
            when (val downloaded = download(file.id, accountId)) {
                is DriveResult.Success -> {
                    val valid = parseAndValidate(downloaded.value) as? SnapshotValidationResult.Valid ?: continue
                    return DriveResult.Success(ValidatedCopy(file, valid.snapshot))
                }
                is DriveResult.Failure -> when (downloaded.error) {
                    DriveError.NotFound, DriveError.TooLarge, is DriveError.InvalidResponse -> continue
                    else -> return downloaded
                }
            }
        }
        return DriveResult.Success(null)
    }

    private enum class Operation { LIST, UPLOAD_INIT, CREATE, UPDATE, GET }

    private sealed interface Attempt {
        class Done<T>(val result: DriveResult<T>) : Attempt
        data object Unauthorized : Attempt
        class Status(val code: Int, val retryAfter: Duration?, val reasons: Set<String>) : Attempt
        data object NoResponse : Attempt
    }

    private suspend fun <T> call(
        operation: Operation,
        accountId: String?,
        configure: HttpRequestBuilder.() -> Unit,
        onSuccess: suspend (HttpResponse) -> DriveResult<T>,
    ): DriveResult<T> {
        var attempt = 1
        var reauthorized = false
        while (true) {
            val token = when (val t = auth.accessToken()) {
                is AccessTokenResult.Token -> t.accessToken
                AccessTokenResult.NeedsUserAction -> return DriveResult.Failure(DriveError.NeedsUserAction)
                AccessTokenResult.NotSignedIn -> return DriveResult.Failure(DriveError.NotSignedIn)
                is AccessTokenResult.Failure -> return DriveResult.Failure(DriveError.AuthFailure(t.retryable))
            }
            if (accountId != null && auth.currentAccount()?.id != accountId) {
                return DriveResult.Failure(DriveError.AccountChanged)
            }
            val outcome: Attempt = try {
                http.prepareRequest {
                    configure()
                    expectSuccess = false
                    header(HttpHeaders.Authorization, "Bearer $token")
                }.execute { response ->
                    when (val code = response.status.value) {
                        in 200..299 -> Attempt.Done(onSuccess(response))
                        401 -> Attempt.Unauthorized
                        else -> Attempt.Status(code, retryAfter(response), if (code == 403) errorReasons(response) else emptySet())
                    }
                }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                Attempt.NoResponse
            } catch (e: Throwable) {
                Attempt.NoResponse
            }

            when (outcome) {
                is Attempt.Done<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val result = outcome.result as DriveResult<T>
                    return if (operation == Operation.CREATE && result is DriveResult.Failure) {
                        DriveResult.Failure(DriveError.UnknownCreateResult)
                    } else {
                        result
                    }
                }
                Attempt.NoResponse -> return DriveResult.Failure(
                    if (operation == Operation.CREATE) DriveError.UnknownCreateResult else DriveError.Unavailable,
                )
                Attempt.Unauthorized -> {
                    if (reauthorized) return DriveResult.Failure(DriveError.NeedsUserAction)
                    auth.invalidateAccessToken(token)
                    reauthorized = true
                }
                is Attempt.Status -> {
                    val code = outcome.code
                    val rateLimited = code == 429 || (code == 403 && outcome.reasons.any { it in RATE_LIMIT_REASONS })
                    val serverError = code == 408 || code in 500..599
                    when {
                        serverError && operation == Operation.CREATE ->
                            return DriveResult.Failure(DriveError.UnknownCreateResult)
                        rateLimited || serverError -> {
                            if (attempt >= retryPolicy.maxAttempts) return DriveResult.Failure(DriveError.Transient(code))
                            delay(retryPolicy.delayBeforeRetry(attempt, outcome.retryAfter))
                            attempt++
                        }
                        code == 403 && outcome.reasons.any { it in SCOPE_REASONS } ->
                            return DriveResult.Failure(DriveError.NeedsUserAction)
                        // A file this OAuth project cannot see (e.g. written under the previous Cloud project) is treated like a missing one.
                        code == 403 && NOT_AUTHORIZED_TO_FILE in outcome.reasons ->
                            return DriveResult.Failure(DriveError.NotFound)
                        code == 403 && STORAGE_QUOTA_EXCEEDED in outcome.reasons ->
                            return DriveResult.Failure(DriveError.Forbidden(STORAGE_QUOTA_EXCEEDED))
                        code == 403 -> return DriveResult.Failure(
                            DriveError.Forbidden(outcome.reasons.firstOrNull { it !in GENERIC_REASONS } ?: "forbidden"),
                        )
                        code == 404 -> return DriveResult.Failure(DriveError.NotFound)
                        else -> return DriveResult.Failure(DriveError.HttpError(code))
                    }
                }
            }
        }
    }

    private fun retryAfter(response: HttpResponse): Duration? =
        response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.seconds

    private suspend fun errorReasons(response: HttpResponse): Set<String> {
        val bytes = readBounded(response, MAX_METADATA_BYTES) ?: return emptySet()
        val error = (parseOrNull(bytes) as? JsonObject)?.get("error") as? JsonObject ?: return emptySet()
        val reasons = mutableSetOf<String>()
        for (key in listOf("errors", "details")) {
            (error[key] as? JsonArray)?.forEach { item -> (item as? JsonObject)?.string("reason")?.let(reasons::add) }
        }
        error.string("status")?.let(reasons::add)
        return reasons
    }

    private suspend fun <T> parseJson(
        response: HttpResponse,
        limit: Int,
        parse: (JsonObject) -> T?,
    ): DriveResult<T> {
        val bytes = readBounded(response, limit) ?: return DriveResult.Failure(DriveError.InvalidResponse("response too large"))
        val root = parseOrNull(bytes) as? JsonObject ?: return DriveResult.Failure(DriveError.InvalidResponse("not a JSON object"))
        val value = parse(root) ?: return DriveResult.Failure(DriveError.InvalidResponse("unexpected structure"))
        return DriveResult.Success(value)
    }

    private class FileListPage(val files: List<DriveFile>, val nextPageToken: String?)

    private fun parseFileList(root: JsonObject): FileListPage? {
        val files = when (val array = root["files"]) {
            null -> emptyList()
            is JsonArray -> array.map { (it as? JsonObject)?.let(::parseFile) ?: return null }
            else -> return null
        }
        return FileListPage(files, root.string("nextPageToken")?.takeIf { it.isNotEmpty() })
    }

    private fun parseFile(obj: JsonObject): DriveFile? {
        val id = obj.string("id")?.takeIf { it.isNotBlank() } ?: return null
        val modified = obj.string("modifiedTime")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        return DriveFile(id, modified, obj.string("headRevisionId")?.takeIf { it.isNotBlank() })
    }

    companion object {
        const val FILE_NAME: String = "service-calendar-backup.json"

        const val DEFAULT_BASE_URL: String = "https://www.googleapis.com"

        fun withPlatformEngine(auth: GoogleAuthProvider): DriveBackupClient =
            DriveBackupClient(
                http = HttpClient {
                    install(HttpTimeout) {
                        connectTimeoutMillis = 20_000
                        socketTimeoutMillis = 60_000
                        requestTimeoutMillis = 180_000
                    }
                },
                auth = auth,
            )

        private const val FILE_FIELDS = "id,modifiedTime,headRevisionId"

        private const val MAX_LIST_PAGES = 100
        private const val MAX_METADATA_BYTES = 1024 * 1024

        private val RATE_LIMIT_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded")
        private val SCOPE_REASONS = setOf("insufficientPermissions", "ACCESS_TOKEN_SCOPE_INSUFFICIENT", "authError")
        private val GENERIC_REASONS = setOf("PERMISSION_DENIED", "forbidden")
        private const val NOT_AUTHORIZED_TO_FILE = "appNotAuthorizedToFile"

        const val STORAGE_QUOTA_EXCEEDED: String = "storageQuotaExceeded"

        private val JSON_UTF8 = ContentType.Application.Json.withParameter("charset", "UTF-8")

        private val CREATE_METADATA: String = buildJsonObject {
            put("name", FILE_NAME)
            putJsonArray("parents") { add("appDataFolder") }
            put("mimeType", "application/json")
        }.toString()

        private val NEWEST_FIRST: Comparator<DriveFile> =
            compareByDescending<DriveFile, Instant?>(nullsFirst()) { it.modifiedTime }.thenBy { it.id }

        private val lenientJson = Json { ignoreUnknownKeys = true }

        private fun parseOrNull(bytes: ByteArray): JsonElement? =
            try {
                lenientJson.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true))
            } catch (e: Exception) {
                null
            }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private suspend fun readBounded(response: HttpResponse, limit: Int): ByteArray? {
            val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declared != null && declared > limit) return null
            val bytes = response.bodyAsChannel().readBuffer(limit.toLong() + 1).readByteArray()
            return if (bytes.size > limit) null else bytes
        }
    }
}
