package dev.paperreader.extensions.sample.source

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.content.pm.PackageManager
import dev.paperreader.extensions.api.ExtensionFailure
import dev.paperreader.extensions.api.ExtensionFailureCode
import dev.paperreader.extensions.api.ExtensionPayloadValidator
import dev.paperreader.extensions.api.IPaperSourceCallback
import dev.paperreader.extensions.api.IPaperSourceService
import dev.paperreader.extensions.api.SourceCapability
import dev.paperreader.extensions.api.SourceExtensionDescriptor
import dev.paperreader.extensions.api.SourceGetPaperRequest
import dev.paperreader.extensions.api.SourceManifestation
import dev.paperreader.extensions.api.SourcePaperRecord
import dev.paperreader.extensions.api.SourcePaperResponse
import dev.paperreader.extensions.api.SourceSearchPage
import dev.paperreader.extensions.api.SourceSearchRequest
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import org.json.JSONObject

class OpenAlexSourceService : Service() {
    private val executor = Executors.newFixedThreadPool(2)
    private val requests = ConcurrentHashMap<String, Future<*>>()
    private val connections = ConcurrentHashMap<String, HttpURLConnection>()

    private val binder = object : IPaperSourceService.Stub() {
        override fun getDescriptor(): Bundle {
            requirePaperReaderCaller()
            return SOURCE_DESCRIPTOR.toBundle()
        }

        override fun search(requestBundle: Bundle, callback: IPaperSourceCallback) {
            requirePaperReaderCaller()
            val request = decodeRequest(callback) { SourceSearchRequest.fromBundle(requestBundle) } ?: return
            submit(request.requestId, callback) {
                val cursor = request.cursor ?: "*"
                val url = "$OPENALEX/works?search=${encode(request.query)}&per-page=${request.limit}" +
                    "&cursor=${encode(cursor)}&select=id,title,doi,publication_date,authorships,primary_location,open_access"
                val json = fetchJson(request.requestId, url)
                val records = json.getJSONArray("results").let { results ->
                    buildList {
                        for (index in 0 until results.length()) add(parseRecord(results.getJSONObject(index)))
                    }
                }
                SourceSearchPage(
                    requestId = request.requestId,
                    records = records,
                    nextCursor = json.optJSONObject("meta")?.optNullableString("next_cursor"),
                ).toBundle()
            }
        }

        override fun getPaper(requestBundle: Bundle, callback: IPaperSourceCallback) {
            requirePaperReaderCaller()
            val request = decodeRequest(callback) { SourceGetPaperRequest.fromBundle(requestBundle) } ?: return
            submit(request.requestId, callback) {
                require(request.providerRecordId.matches(Regex("W\\d+")))
                val record = try {
                    parseRecord(fetchJson(request.requestId, "$OPENALEX/works/${request.providerRecordId}"))
                } catch (_: NotFoundException) {
                    null
                }
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
        binder.takeIf { intent?.action == dev.paperreader.extensions.api.PaperExtensionContract.SOURCE_SERVICE_ACTION }

    override fun onDestroy() {
        connections.values.forEach(HttpURLConnection::disconnect)
        requests.values.forEach { it.cancel(true) }
        executor.shutdownNow()
        super.onDestroy()
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
                        "OpenAlex rate limited the request",
                        limited.retryAfterMillis,
                    ).toBundle(),
                )
            } catch (error: Exception) {
                if (!Thread.currentThread().isInterrupted) {
                    android.util.Log.e(
                        "PaperReaderSample",
                        "Source request failed: ${error.stackTraceToString()}",
                    )
                    callback.onFailure(
                        ExtensionFailure(
                            requestId,
                            ExtensionFailureCode.UNAVAILABLE,
                            error.message?.take(512)?.takeIf(String::isNotBlank) ?: "OpenAlex is unavailable",
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

    private fun <T> decodeRequest(callback: IPaperSourceCallback, decode: () -> T): T? = try {
        decode()
    } catch (error: Exception) {
        callback.onFailure(
            ExtensionFailure(
                requestId = "invalid-request",
                code = ExtensionFailureCode.INVALID_REQUEST,
                message = error.message?.take(512) ?: "Invalid request",
            ).toBundle(),
        )
        null
    }

    private fun fetchJson(requestId: String, url: String): JSONObject {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connections[requestId] = connection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "PaperReader-OpenAlex-Sample/0.1")
        val status = connection.responseCode
        if (status == 429) {
            val seconds = connection.getHeaderField("Retry-After")?.toLongOrNull()
            throw RateLimitedException(seconds?.times(1_000))
        }
        if (status == 404) throw NotFoundException()
        require(status in 200..299) { "OpenAlex returned HTTP $status" }
        val output = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_RESPONSE_BYTES) { "OpenAlex response is too large" }
                output.write(buffer, 0, count)
            }
        }
        return JSONObject(output.toString(StandardCharsets.UTF_8.name()))
    }

    private fun parseRecord(json: JSONObject): SourcePaperRecord {
        val openAlexId = json.getString("id").substringAfterLast('/')
        val authorships = json.optJSONArray("authorships")
        val authors = buildList {
            if (authorships != null) {
                for (index in 0 until authorships.length()) {
                    authorships.optJSONObject(index)
                        ?.optJSONObject("author")
                        ?.optString("display_name")
                        ?.takeIf(String::isNotBlank)
                        ?.let(::add)
                }
            }
        }
        val location = json.optJSONObject("primary_location")
        val openAccessUrl = json.optJSONObject("open_access")?.optNullableString("oa_url")?.safeWebUrl()
        val landingPage = location?.optNullableString("landing_page_url")?.safeWebUrl() ?: openAccessUrl
        val pdfUrl = location?.optNullableString("pdf_url")?.safeWebUrl()
        return SourcePaperRecord(
            providerRecordId = openAlexId,
            title = json.getString("title").take(512),
            authors = authors.take(100),
            doi = json.optNullableString("doi")?.removePrefix("https://doi.org/"),
            publishedDate = json.optNullableString("publication_date"),
            manifestations = listOfNotNull(
                if (landingPage != null || pdfUrl != null) {
                    SourceManifestation(
                        type = "other",
                        landingPageUrl = landingPage,
                        pdfUrl = pdfUrl,
                        publishedDate = json.optNullableString("publication_date"),
                    )
                } else {
                    null
                },
            ),
        )
    }

    private fun String.safeWebUrl(): String? = takeIf { value ->
        runCatching {
            val uri = URI(value)
            uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
        }.getOrDefault(false)
    }

    private fun JSONObject.optNullableString(key: String): String? =
        takeUnless { isNull(key) }
            ?.optString(key)
            ?.trim()
            ?.takeIf(String::isNotBlank)

    private fun requirePaperReaderCaller() {
        val packages = packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        require(packages.contains(PAPERREADER_PACKAGE)) { "Caller is not PaperReader" }
        require(
            packageManager.hasSigningCertificate(
                PAPERREADER_PACKAGE,
                BuildConfig.PAPERREADER_HOST_SIGNER_SHA256.hexToBytes(),
                PackageManager.CERT_INPUT_SHA256,
            ),
        ) { "PaperReader signer is not trusted" }
    }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private class RateLimitedException(val retryAfterMillis: Long?) : Exception()
    private class NotFoundException : Exception()

    private companion object {
        const val PAPERREADER_PACKAGE = "dev.paperreader.app"
        const val OPENALEX = "https://api.openalex.org"
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

        val SOURCE_DESCRIPTOR = SourceExtensionDescriptor(
            packageName = "dev.paperreader.extensions.sample.source",
            providerId = "openalex-sample",
            displayName = "OpenAlex sample",
            minimumRequestIntervalMillis = 1_000,
            capabilities = setOf(
                SourceCapability.SEARCH,
                SourceCapability.DETAILS,
                SourceCapability.PDF_LINK,
            ),
        )
    }
}
