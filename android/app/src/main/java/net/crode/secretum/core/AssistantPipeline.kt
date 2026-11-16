package net.crode.secretum.core

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.crode.secretum.audio.SpeechInput
import net.crode.secretum.audio.SpeechOutput
import net.crode.secretum.camera.FrameSource
import net.crode.secretum.glasses.ButtonEvent
import net.crode.secretum.glasses.ButtonId
import net.crode.secretum.glasses.GlassesLink
import net.crode.secretum.glasses.Gesture
import net.crode.secretum.glasses.Haptic
import net.crode.secretum.net.AskMode
import net.crode.secretum.net.AssistantClient
import net.crode.secretum.net.AssistantEvent
import java.util.UUID

/**
 * 한 번의 상호작용:  트리거 → (사진 캡처 ∥ 음성 인식) → 서버 질의 → 스트리밍 TTS 또는 햅틱 응답
 *
 * 버튼 매핑 (docs/PROTOCOL.md)
 *   A 짧게  : 음성 질문 (답: 음성)
 *   A 더블  : 무음 질문 (답: 진동 YES/NO/UNSURE)
 *   A 길게  : 대화 리셋
 *   B 짧게  : 마지막 답변 다시 듣기
 *   B 길게/더블 : 진행 중 작업·음성 중단
 *
 * scope는 Main 디스패처 기준 (TTS/STT가 메인 스레드 요구).
 */
class AssistantPipeline(
    private val scope: CoroutineScope,
    private val link: GlassesLink,
    private val phoneCamera: FrameSource,
    private val glassesCamera: FrameSource,
    private val speechIn: SpeechInput,
    private val speechOut: SpeechOutput,
    private val client: AssistantClient,
    private val log: (String) -> Unit,
) {
    private var sessionId = UUID.randomUUID().toString()
    private var current: Job? = null
    private var lastAnswer: String? = null

    fun start() {
        scope.launch { link.buttons.collect { onButton(it) } }
    }

    fun onButton(ev: ButtonEvent) {
        log("button ${ev.button} ${ev.gesture}")
        when (ev.button) {
            ButtonId.A -> when (ev.gesture) {
                Gesture.SHORT -> ask(AskMode.VOICE)
                Gesture.DOUBLE -> ask(AskMode.SILENT)
                Gesture.LONG -> resetSession()
            }
            ButtonId.B -> when (ev.gesture) {
                Gesture.SHORT -> lastAnswer?.let { speechOut.speakNow(it) } ?: feedback(Haptic.ERROR)
                else -> cancel()
            }
        }
    }

    fun ask(mode: AskMode) {
        cancel()
        current = scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            fun ms() = SystemClock.elapsedRealtime() - t0
            try {
                val source = if (link.state.value == GlassesLink.State.READY) glassesCamera else phoneCamera

                // 사진은 '누른 순간' 장면이어야 하므로 말하는 동안 병렬로 캡처
                val (image, question) = coroutineScope {
                    val img = async {
                        runCatching { source.capture() }
                            .onFailure { log("capture failed (${source.name}): ${it.message}") }
                            .getOrNull()
                    }
                    val q = speechIn.listen()
                    img.await() to q
                }
                if (question.isNullOrBlank()) {
                    log("no speech")
                    feedback(Haptic.ERROR)
                    return@launch
                }
                log("Q[$mode]: $question  img=${image?.size ?: 0}B via ${source.name}  +${ms()}ms")

                var firstDeltaLogged = false
                client.ask(sessionId, question, image, mode).collect { e ->
                    when (e) {
                        is AssistantEvent.Delta -> {
                            if (!firstDeltaLogged) { firstDeltaLogged = true; log("first token +${ms()}ms") }
                            speechOut.feed(e.text)
                        }
                        is AssistantEvent.Haptic -> feedback(Haptic.fromServer(e.pattern))
                        is AssistantEvent.Done -> {
                            speechOut.finish()
                            lastAnswer = e.text
                            log("A: ${e.text}  +${ms()}ms")
                        }
                        is AssistantEvent.Failed -> {
                            feedback(Haptic.ERROR)
                            log("server error: ${e.message}")
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                feedback(Haptic.ERROR)
                log("failed: ${e.message}")
            }
        }
    }

    fun cancel() {
        current?.cancel()
        current = null
        speechOut.stop()
    }

    fun resetSession() {
        cancel()
        sessionId = UUID.randomUUID().toString()  // 서버의 이전 세션은 TTL로 만료
        lastAnswer = null
        feedback(Haptic.READY)
        log("session reset")
    }

    private fun feedback(h: Haptic) {
        if (link.state.value == GlassesLink.State.READY) link.haptic(h) else log("haptic (no glasses): $h")
    }
}
