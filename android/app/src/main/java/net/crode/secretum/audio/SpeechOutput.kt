package net.crode.secretum.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 스트리밍 TTS: 서버 델타를 받으며 문장 경계마다 바로 읽기 시작 → 전체 답변을 기다리지 않음.
 * 출력은 시스템 오디오 라우팅(연결된 블루투스 이어폰)으로 나감.
 * 메인 스레드에서 호출할 것.
 */
class SpeechOutput(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    private val pending = StringBuilder()
    private var ready = false
    private var seq = 0

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.KOREAN
            tts.setSpeechRate(1.1f)
        }
    }

    /** 델타 누적 후 완성된 문장만 큐에 넣음. */
    fun feed(delta: String) {
        pending.append(delta)
        flush(force = false)
    }

    /** 스트림 종료: 남은 꼬리 문장까지 읽음. */
    fun finish() = flush(force = true)

    fun speakNow(text: String) {
        stop()
        say(text, TextToSpeech.QUEUE_FLUSH)
    }

    fun stop() {
        pending.clear()
        if (ready) tts.stop()
    }

    fun shutdown() = tts.shutdown()

    private fun flush(force: Boolean) {
        while (true) {
            // 한계: "3.5" 같은 소수점에서도 끊김. 필요하면 '.' 뒤 공백/끝 확인 로직으로 개선.
            val idx = pending.indexOfFirst { it == '.' || it == '?' || it == '!' || it == '\n' || it == '。' }
            if (idx < 0) break
            val sentence = pending.substring(0, idx + 1).trim()
            pending.delete(0, idx + 1)
            if (sentence.isNotEmpty()) say(sentence, TextToSpeech.QUEUE_ADD)
        }
        if (force && pending.isNotBlank()) {
            say(pending.toString().trim(), TextToSpeech.QUEUE_ADD)
            pending.clear()
        }
    }

    private fun say(text: String, mode: Int) {
        if (!ready) return
        tts.speak(text, mode, null, "utt-${seq++}")
    }
}
