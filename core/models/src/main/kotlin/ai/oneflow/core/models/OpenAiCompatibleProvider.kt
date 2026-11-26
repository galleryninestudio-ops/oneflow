package ai.oneflow.core.models

import ai.oneflow.core.contracts.ChatRole
import ai.oneflow.core.contracts.ErrorCode
import ai.oneflow.core.contracts.FlowError
import ai.oneflow.core.contracts.ModelCapabilities
import ai.oneflow.core.contracts.ModelCatalogResult
import ai.oneflow.core.contracts.ModelEvent
import ai.oneflow.core.contracts.ModelId
import ai.oneflow.core.contracts.ModelProvider
import ai.oneflow.core.contracts.ModelRequest
import ai.oneflow.core.contracts.ProviderConfig
import ai.oneflow.core.contracts.SecretRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenAiCompatibleProvider(
    private val config: ProviderConfig,
    private val apiKeyProvider: () -> String?,
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) : ModelProvider {

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val parser = OpenAiSseParser(
        json = json,
        knownSecrets = { listOfNotNull(apiKeyProvider()) }
    )

    private fun normalizedBaseUrl(): String = config.baseUrl.trimEnd('/')

    private fun redact(text: String): String =
        SecretRedactor.redact(text, listOfNotNull(apiKeyProvider()))

    override suspend fun capabilities(model: ModelId): ModelCapabilities {
        // Conservative defaults until probed explicitly with user authorization.
        return ModelCapabilities(
            modelId = model,
            streamingSupported = true,
            toolsSupported = true,
            parallelToolCalls = true,
            visionSupported = false,
            usageReported = false,
            probed = false
        )
    }

    override suspend fun listModels(): Result<ModelCatalogResult> = withContext(Dispatchers.IO) {
        val key = apiKeyProvider()?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure(IllegalStateException("Missing API key for alias ${config.keyAlias}"))

        val req = Request.Builder()
            .url("${normalizedBaseUrl()}/models")
            .header("Authorization", "Bearer $key")
            .get()
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                val bodyStr = resp.body.string()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("HTTP ${resp.code}: ${redact(bodyStr.take(300))}")
                    )
                }
                val root = json.parseToJsonElement(bodyStr).jsonObject
                val data = root["data"]?.jsonArray ?: JsonArray(emptyList())
                val ids = data.mapNotNull { elem ->
                    (elem as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::ModelId)
                }
                Result.success(ModelCatalogResult(models = ids, source = "${normalizedBaseUrl()}/models"))
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException(redact(e.message ?: "Failed to query /models")))
        }
    }

    override fun generate(request: ModelRequest): Flow<ModelEvent> = flow {
        val key = apiKeyProvider()?.takeIf { it.isNotBlank() }
        if (key == null) {
            emit(
                ModelEvent.Failed(
                    FlowError(
                        code = ErrorCode.PROVIDER_AUTH,
                        message = "No API key configured for alias '${config.keyAlias}'"
                    )
                )
            )
            return@flow
        }

        val payload = buildChatPayload(request)
        val httpReq = Request.Builder()
            .url("${normalizedBaseUrl()}/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("Accept", "text/event-stream")
            .post(json.encodeToString(payload).toRequestBody(JSON_MEDIA))
            .build()

        val response = try {
            client.newCall(httpReq).execute()
        } catch (e: IOException) {
            emit(
                ModelEvent.Failed(
                    FlowError(
                        code = ErrorCode.UNAVAILABLE,
                        message = redact("Network error: ${e.message}"),
                        retryable = true
                    )
                )
            )
            return@flow
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                val errBody = runCatching { resp.body.string() }.getOrDefault("")
                emit(ModelEvent.Failed(classifyHttpError(resp.code, errBody)))
                return@flow
            }
            parser.parse(resp.body.source()).collect { event ->
                emit(event)
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun buildChatPayload(request: ModelRequest): JsonObject = buildJsonObject {
        put("model", JsonPrimitive(request.model.value))
        put("stream", JsonPrimitive(request.stream))
        if (request.stream) {
            put("stream_options", buildJsonObject { put("include_usage", JsonPrimitive(true)) })
        }
        request.maxTokens?.let { put("max_tokens", JsonPrimitive(it)) }
        request.temperature?.let { put("temperature", JsonPrimitive(it)) }
        put("messages", buildJsonArray {
            for (msg in request.messages) {
                add(buildJsonObject {
                    put("role", JsonPrimitive(msg.role.name.lowercase()))
                    msg.content?.let { put("content", JsonPrimitive(it)) }
                    msg.toolCallId?.let { put("tool_call_id", JsonPrimitive(it)) }
                    if (msg.toolCalls.isNotEmpty()) {
                        put("tool_calls", buildJsonArray {
                            for (tc in msg.toolCalls) {
                                add(buildJsonObject {
                                    put("id", JsonPrimitive(tc.id))
                                    put("type", JsonPrimitive("function"))
                                    put("function", buildJsonObject {
                                        put("name", JsonPrimitive(tc.name))
                                        put("arguments", JsonPrimitive(tc.argumentsJson))
                                    })
                                })
                            }
                        })
                    }
                })
            }
        })
        if (request.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                for (tool in request.tools) {
                    add(buildJsonObject {
                        put("type", JsonPrimitive("function"))
                        put("function", buildJsonObject {
                            put("name", JsonPrimitive(tool.name))
                            put("description", JsonPrimitive(tool.description))
                            put("parameters", tool.parametersSchema)
                        })
                    })
                }
            })
        }
    }

    private fun classifyHttpError(code: Int, rawBody: String): FlowError {
        val safeMsg = redact(rawBody.take(400)).ifBlank { "HTTP $code" }
        return when (code) {
            401, 403 -> FlowError(ErrorCode.PROVIDER_AUTH, "Authentication failed ($code): $safeMsg", retryable = false)
            429 -> FlowError(ErrorCode.PROVIDER_RATE_LIMITED, "Rate limited ($code): $safeMsg", retryable = true)
            in 500..599 -> FlowError(ErrorCode.UNAVAILABLE, "Provider server error ($code): $safeMsg", retryable = true)
            else -> FlowError(ErrorCode.PROVIDER_PROTOCOL_ERROR, "Provider rejected request ($code): $safeMsg", retryable = false)
        }
    }
}
