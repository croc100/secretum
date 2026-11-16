package net.crode.secretum.glasses

import java.util.UUID

/** 펌웨어 protocol.h, docs/PROTOCOL.md 와 반드시 동일하게 유지. */
object GlassesProtocol {
    val SERVICE: UUID = UUID.fromString("8b6e0001-5a3c-4d8e-9f21-6a1b2c3d4e5f")
    val CHR_CTRL: UUID = UUID.fromString("8b6e0002-5a3c-4d8e-9f21-6a1b2c3d4e5f")
    val CHR_IMG: UUID = UUID.fromString("8b6e0003-5a3c-4d8e-9f21-6a1b2c3d4e5f")
    val CHR_BTN: UUID = UUID.fromString("8b6e0004-5a3c-4d8e-9f21-6a1b2c3d4e5f")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val OP_CAPTURE: Byte = 0x01
    const val OP_HAPTIC: Byte = 0x02
    const val OP_PING: Byte = 0x03

    const val IMG_START = 0xA0
    const val IMG_DATA = 0xA1
    const val IMG_END = 0xA2
    const val IMG_ERR = 0xAF
}

enum class FrameSize(val code: Int) { VGA(0), SVGA(1), XGA(2) }

enum class Haptic(val id: Int) {
    ACK(0x01), READY(0x02), ERROR(0x03),
    YES(0x04), NO(0x05), UNSURE(0x06),
    LEFT(0x10), RIGHT(0x11);

    companion object {
        /** 서버 SSE의 pattern 문자열 → 패턴. 모르는 값은 UNSURE (잘못된 '예'보다 안전). */
        fun fromServer(name: String): Haptic = entries.firstOrNull { it.name == name } ?: UNSURE
    }
}

enum class ButtonId { A, B }
enum class Gesture { SHORT, DOUBLE, LONG }
data class ButtonEvent(val button: ButtonId, val gesture: Gesture) {
    companion object {
        fun parse(p: ByteArray): ButtonEvent? {
            if (p.size < 2) return null
            val b = ButtonId.entries.getOrNull(p[0].toInt()) ?: return null
            val g = when (p[1].toInt()) { 1 -> Gesture.SHORT; 2 -> Gesture.DOUBLE; 3 -> Gesture.LONG; else -> return null }
            return ButtonEvent(b, g)
        }
    }
}
