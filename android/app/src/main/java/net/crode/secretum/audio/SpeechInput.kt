package net.crode.secretum.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 온디바이스/시스템 STT (SpeechRecognizer). 서버 왕복 없이 텍스트로 만들어 지연을 줄임.
 * SpeechRecognizer는 메인 스레드에서만 호출 가능.
 *
 * TODO(phase2): 블루투스 이어폰 마이크 사용 시 AudioManager.setCommunicationDevice()로 입력 라우팅.
 * TODO(phase2): 인식 품질이 부족하면 오디오를 서버로 올려 Whisper 계열로 교체 (인터페이스 유지).
 */
class SpeechInput(private val context: Context) {

    /** 한 문장 인식. 말이 없거나 실패하면 null. */
    suspend fun listen(languageTag: String = "ko-KR", timeoutMs: Long = 12_000): String? =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(timeoutMs) { listenOnce(languageTag) }
        }

    private suspend fun listenOnce(languageTag: String): String? = suspendCancellableCoroutine { cont ->
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        var destroyed = false
        fun destroy() {
            if (!destroyed) { destroyed = true; sr.destroy() }
        }

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                destroy()
                if (cont.isActive) cont.resume(text)
            }

            override fun onError(error: Int) {
                destroy()
                if (cont.isActive) cont.resume(null)  // NO_MATCH, SPEECH_TIMEOUT 등
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        // invokeOnCancellation은 임의 스레드에서 불릴 수 있어 메인으로 넘겨서 정리
        cont.invokeOnCancellation {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (!destroyed) { sr.cancel(); destroy() }
            }
        }

        sr.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        })
    }
}
