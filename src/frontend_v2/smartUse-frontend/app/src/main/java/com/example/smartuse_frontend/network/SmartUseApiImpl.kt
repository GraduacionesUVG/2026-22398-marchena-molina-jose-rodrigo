package com.example.smartuse_frontend.network

import android.util.Log
import com.example.smartuse_frontend.network.dto.CutoffResponse
import com.example.smartuse_frontend.network.dto.ErrorResponse
import com.example.smartuse_frontend.network.dto.FinalSendRequest
import com.example.smartuse_frontend.network.dto.FinalSendResponse
import com.example.smartuse_frontend.network.dto.RegisterRequest
import com.example.smartuse_frontend.network.dto.RegisterResponse
import com.example.smartuse_frontend.network.dto.appJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit

private const val LOG_TAG = "SmartUseApi"
private const val MAX_LOGGED_BODY_CHARS = 3000

/**
 * [logger] receives one multi-line entry per failed call: method, full URL,
 * status, and the response body exactly as received. The bearer token is
 * never logged. Request bodies are logged only where a call opts in (the
 * register payload is profile data, so it never does).
 */
class SmartUseApiImpl(
    baseUrl: String = ApiEndpoint.baseUrl,
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = appJson,
    private val logger: (String) -> Unit = { Log.d(LOG_TAG, it) },
    cutoffTimeout: Duration = Duration.ofSeconds(5),
) : SmartUseApi {

    private val root = baseUrl.trimEnd('/')
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // GET /cutoff-deployment-group runs at launch, at hard opt-out confirm and in every job; it
    // must never hold them up for the shared 15 s timeouts. The call timeout caps
    // the whole call (DNS, every route attempt, a trickling body).
    private val cutoffClient: OkHttpClient = client.newBuilder()
        .connectTimeout(cutoffTimeout)
        .readTimeout(cutoffTimeout)
        .callTimeout(cutoffTimeout.multipliedBy(2))
        .build()

    override suspend fun register(body: RegisterRequest): NetworkResult<RegisterResponse> {
        val payload = json.encodeToString(RegisterRequest.serializer(), body)
        val request = Request.Builder()
            .url("$root/users")
            .post(payload.toRequestBody(jsonMedia))
            .build()
        return execute(request, requestBodyToLog = null) {
            json.decodeFromString(RegisterResponse.serializer(), it)
        }
    }

    override suspend fun getCutoff(): NetworkResult<CutoffResponse> {
        val request = Request.Builder()
            .url("$root/cutoff-deployment-group")
            .get()
            .build()
        return execute(request, requestBodyToLog = null, httpClient = cutoffClient) {
            json.decodeFromString(CutoffResponse.serializer(), it)
        }
    }

    override suspend fun sendFinal(token: String, body: FinalSendRequest): NetworkResult<FinalSendResponse> {
        val payload = json.encodeToString(FinalSendRequest.serializer(), body)
        val request = Request.Builder()
            .url("$root/final")
            .header("Authorization", "Bearer $token")
            .post(payload.toRequestBody(jsonMedia))
            .build()
        // The body holds the participant's answers: never logged. Any 2xx means
        // stored: an unreadable body must not turn a confirmed send into retries.
        return execute(request, requestBodyToLog = null) {
            runCatching { json.decodeFromString(FinalSendResponse.serializer(), it) }.getOrDefault(FinalSendResponse())
        }
    }

    private suspend fun <T> execute(
        request: Request,
        requestBodyToLog: String?,
        httpClient: OkHttpClient = client,
        parse: (String) -> T,
    ): NetworkResult<T> = withContext(Dispatchers.IO) {
        val label = "${request.method} ${request.url}"
        try {
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    try {
                        NetworkResult.Success(parse(text))
                    } catch (e: IllegalArgumentException) { // SerializationException is one
                        logger(
                            "$label -> HTTP ${response.code} but the body could not be parsed: ${e.message}\n" +
                                "  response body: ${text.take(MAX_LOGGED_BODY_CHARS)}",
                        )
                        NetworkResult.IoError(
                            IOException("Unparseable response (HTTP ${response.code}): ${text.take(200)}", e),
                        )
                    }
                } else {
                    logger(
                        buildString {
                            append("$label -> HTTP ${response.code}\n")
                            append("  response body: ${text.take(MAX_LOGGED_BODY_CHARS)}")
                            if (requestBodyToLog != null) {
                                append("\n  request body: ${requestBodyToLog.take(MAX_LOGGED_BODY_CHARS)}")
                            }
                        },
                    )
                    val parsed = runCatching {
                        json.decodeFromString(ErrorResponse.serializer(), text)
                    }.getOrNull()
                    NetworkResult.HttpError(response.code, parsed, text)
                }
            }
        } catch (e: IOException) {
            logger("$label -> ${e::class.java.simpleName}: ${e.message}")
            NetworkResult.IoError(e)
        }
    }

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
