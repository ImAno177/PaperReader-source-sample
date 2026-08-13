package dev.paperreader.extensions.sample.source

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import dev.paperreader.extensions.api.ExtensionFailure
import dev.paperreader.extensions.api.ExtensionFailureCode
import dev.paperreader.extensions.api.ExtensionPayloadValidator
import dev.paperreader.extensions.api.IPaperSourceCallback
import dev.paperreader.extensions.api.IPaperSourceService
import dev.paperreader.extensions.api.PaperExtensionContract
import dev.paperreader.extensions.api.SourceCapability
import dev.paperreader.extensions.api.SourceExtensionDescriptor
import dev.paperreader.extensions.api.SourceGetPaperRequest
import dev.paperreader.extensions.api.SourceIdentifierType
import dev.paperreader.extensions.api.SourcePaperRecord
import dev.paperreader.extensions.api.SourcePaperResponse
import dev.paperreader.extensions.api.SourceRole
import dev.paperreader.extensions.api.SourceSearchPage
import dev.paperreader.extensions.api.SourceSearchRequest
import dev.paperreader.extensions.api.SourceSearchSort
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import org.json.JSONArray
import org.json.JSONObject

/** Minimal real-network example: Crossref is used only for exact DOI metadata lookup. */
class CrossrefSampleSourceService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val requests = ConcurrentHashMap<String, Future<*>>()
    private val connections = ConcurrentHashMap<String, HttpURLConnection>()

    private val binder = object : IPaperSourceService.Stub() {
        override fun getDescriptor(): Bundle {
            requirePaperReaderCaller()
            return SOURCE_DESCRIPTOR.toBundle()
        }

        override fun search(requestBundle: Bundle, callback: IPaperSourceCallback) {
            requirePaperReaderCaller()
            val request = decode(callback) { SourceSearchRequest.fromBundle(requestBundle) } ?: return
            submit(request.requestId, callback) {
                val doi = normalizeDoi(request.query)
                val record = doi?.let { fetchRecord(request.requestId, it) }
                SourceSearchPage(request.requestId, listOfNotNull(record), null).toBundle()
            }
        }

        override fun getPaper(requestBundle: Bundle, callback: IPaperSourceCallback) {
            requirePaperReaderCaller()
            val request = decode(callback) { SourceGetPaperRequest.fromBundle(requestBundle) } ?: return
            submit(request.requestId, callback) {
                val record = normalizeDoi(request.providerRecordId)?.let { fetchRecord(request.requestId, it) }
                SourcePaperResponse(request.requestId, record).toBundle()
            }
        }

        override fun cancel(requestId: String) {
            requirePaperReaderCaller()
            connections.remove(requestId)?.disconnect()
            requests.remove(requestId)?.cancel(true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? =
        binder.takeIf { intent?.action == PaperExtensionContract.SOURCE_SERVICE_ACTION }

    override fun onDestroy() {
        connections.values.forEach(HttpURLConnection::disconnect)
        requests.values.forEach { it.cancel(true) }
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun fetchRecord(requestId: String, doi: String): SourcePaperRecord? {
        val encoded = URLEncoder.encode(doi, StandardCharsets.UTF_8.name()).replace("+", "%20")
        val message = try {
            fetchJson(requestId, "$CROSSREF/works/$encoded").getJSONObject("message")
        } catch (_: NotFoundException) {
            return null
        }
        val returnedDoi = normalizeDoi(message.optString("DOI")) ?: return null
        require(returnedDoi == doi) { "Crossref returned a different DOI" }
        return SourcePaperRecord(
            providerRecordId = returnedDoi,
            title = message.optJSONArray("title").firstString()?.take(PaperExtensionContract.MAX_TITLE_CHARACTERS)
                ?: error("Crossref record has no title"),
            authors = message.optJSONArray("author").authors().take(PaperExtensionContract.MAX_AUTHORS),
            subjects = message.optJSONArray("subject").strings().take(PaperExtensionContract.MAX_SUBJECTS).toSet(),
            doi = returnedDoi,
            citationCount = message.optInt("is-referenced-by-count", -1).takeIf { it >= 0 },
            publishedDate = message.dateParts("published-print") ?: message.dateParts("published-online")
                ?: message.dateParts("issued"),
        )
    }

    private fun fetchJson(requestId: String, url: String): JSONObject {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connections[requestId] = connection
        connection.apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty(
                "User-Agent",
                "PaperReader-Crossref-Sample/0.1 (https://github.com/ImAno177/PaperReader-source-sample)",
            )
        }
        when (connection.responseCode) {
            404 -> throw NotFoundException()
            429 -> throw RateLimitedException(connection.getHeaderField("Retry-After")?.toLongOrNull()?.times(1_000))
            !in 200..299 -> error("Crossref returned HTTP ${connection.responseCode}")
        }
        val output = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_RESPONSE_BYTES) { "Crossref response is too large" }
                output.write(buffer, 0, count)
            }
        }
        return JSONObject(output.toString(StandardCharsets.UTF_8.name()))
    }

    private fun submit(requestId: String, callback: IPaperSourceCallback, block: () -> Bundle) {
        val task = FutureTask {
            try {
                val response = block()
                ExtensionPayloadValidator.requireBinderSafe(response)
                callback.onSuccess(response)
            } catch (limited: RateLimitedException) {
                callback.onFailure(
                    ExtensionFailure(
                        requestId,
                        ExtensionFailureCode.RATE_LIMITED,
                        "Crossref rate limited the request",
                        limited.retryAfterMillis,
                    ).toBundle(),
                )
            } catch (error: Exception) {
                if (!Thread.currentThread().isInterrupted) {
                    callback.onFailure(
                        ExtensionFailure(
                            requestId,
                            ExtensionFailureCode.UNAVAILABLE,
                            error.message?.take(512)?.takeIf(String::isNotBlank) ?: "Crossref is unavailable",
                        ).toBundle(),
                    )
                }
            } finally {
                requests.remove(requestId)
                connections.remove(requestId)?.disconnect()
            }
        }
        require(requests.putIfAbsent(requestId, task) == null) { "Duplicate request ID" }
        executor.execute(task)
    }

    private fun <T> decode(callback: IPaperSourceCallback, block: () -> T): T? = try {
        block()
    } catch (error: Exception) {
        callback.onFailure(
            ExtensionFailure(
                "invalid-request",
                ExtensionFailureCode.INVALID_REQUEST,
                error.message?.take(512) ?: "Invalid request",
            ).toBundle(),
        )
        null
    }

    private fun requirePaperReaderCaller() {
        val packages = packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        require(PAPERREADER_PACKAGE in packages) { "Caller is not PaperReader" }
        require(
            packageManager.hasSigningCertificate(
                PAPERREADER_PACKAGE,
                BuildConfig.PAPERREADER_HOST_SIGNER_SHA256.hexToBytes(),
                PackageManager.CERT_INPUT_SHA256,
            ),
        ) { "PaperReader signer is not trusted" }
    }

    private fun JSONObject.dateParts(key: String): String? {
        val parts = optJSONObject(key)?.optJSONArray("date-parts")?.optJSONArray(0) ?: return null
        val year = parts.optInt(0, -1).takeIf { it > 0 } ?: return null
        val month = parts.optInt(1, 1).coerceIn(1, 12)
        val day = parts.optInt(2, 1).coerceIn(1, 31)
        return runCatching { LocalDate.of(year, month, day).toString() }.getOrNull()
    }

    private fun JSONArray?.firstString(): String? = this?.optString(0)?.trim()?.takeIf(String::isNotBlank)

    private fun JSONArray?.strings(): List<String> = buildList {
        val array = this@strings ?: return@buildList
        for (index in 0 until array.length()) array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
    }

    private fun JSONArray?.authors(): List<String> = buildList {
        val array = this@authors ?: return@buildList
        for (index in 0 until array.length()) {
            val author = array.optJSONObject(index) ?: continue
            listOf(author.optString("given"), author.optString("family"))
                .map(String::trim)
                .filter(String::isNotBlank)
                .joinToString(" ")
                .takeIf(String::isNotBlank)
                ?.let(::add)
        }
    }

    private fun normalizeDoi(raw: String): String? = raw.trim().lowercase()
        .removePrefix("https://doi.org/")
        .removePrefix("doi:")
        .takeIf { DOI.matches(it) }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private class RateLimitedException(val retryAfterMillis: Long?) : Exception()
    private class NotFoundException : Exception()

    private companion object {
        const val PAPERREADER_PACKAGE = "dev.paperreader.app"
        const val CROSSREF = "https://api.crossref.org"
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        val DOI = Regex("10\\.\\d{4,9}/[-._;()/:a-z0-9]+")
        val SOURCE_DESCRIPTOR = SourceExtensionDescriptor(
            packageName = "dev.paperreader.extensions.sample.source",
            providerId = "crossref-sample",
            displayName = "Crossref sample",
            minimumRequestIntervalMillis = 1_000,
            capabilities = setOf(SourceCapability.SEARCH, SourceCapability.DETAILS),
            roles = setOf(SourceRole.METADATA_ENGINE),
            identifierLookupTypes = setOf(SourceIdentifierType.DOI),
            supportedSorts = setOf(SourceSearchSort.RELEVANCE),
        )
    }
}
