package net.crode.secretum.net

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class AskMode(val wire: String) { VOICE("voice"), SILENT("silent") }

sealed interface AssistantEvent {
    data class Delta(val text: String) : AssistantEvent
    data class Haptic(val pattern: String) : AssistantEvent
    data class Done(val text: String) : AssistantEvent
    data class Failed(val message: String) : AssistantEvent
}

/** 서버 /v1/ask SSE 클라이언트. 계약은 docs/PROTOCOL.md. 수집을 취소하면 HTTP 요청도 즉시 끊음. */
class AssistantClient(private val baseUrl: String, private val token: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun ask(sessionId: String, question: String, jpeg: ByteArray?, mode: AskMode): Flow<AssistantEvent> = callbackFlow {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("question", question)
            .addFormDataPart("session_id", sessionId)
            .addFormDataPart("mode", mode.wire)
            .apply { if (jpeg != null) addFormDataPart("image", "frame.jpg", jpeg.toRequestBody("image/jpeg".toMediaType())) }
            .build()
        val req = Request.Builder()
            .url("$baseUrl/v1/ask")
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .post(body)
            .build()

        val call = http.newCall(req)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                trySend(AssistantEvent.Failed(e.message ?: "network error"))
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        trySend(AssistantEvent.Failed("HTTP ${resp.code}"))
                        close()
                        return
                    }
                    val src = resp.body?.source() ?: run { close(); return }
                    try {
                        while (!isClosedForSend) {
                            val line = src.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val evt = parse(line.removePrefix("data:").trim()) ?: continue
                            trySendBlocking(evt)
                            if (evt is AssistantEvent.Done || evt is AssistantEvent.Failed) break
                        }
                    } catch (e: IOException) {
                        if (!call.isCanceled()) trySend(AssistantEvent.Failed(e.message ?: "stream error"))
                    }
                    close()
                }
            }
        })
        awaitClose { call.cancel() }
    }

    private fun parse(json: String): AssistantEvent? = runCatching {
        val o = JSONObject(json)
        when (o.optString("type")) {
            "delta" -> AssistantEvent.Delta(o.getString("text"))
            "haptic" -> AssistantEvent.Haptic(o.getString("pattern"))
            "done" -> AssistantEvent.Done(o.optString("text"))
            "error" -> AssistantEvent.Failed(o.optString("message"))
            else -> null
        }
    }.getOrNull()
}
