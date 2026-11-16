package net.crode.secretum.glasses

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * BLE notify 패킷(START/DATA/END/ERR)을 JPEG 한 장으로 재조립.
 * 순서가 어긋나거나 CRC가 틀리면 Error → 호출측이 재캡처.
 */
class ImageAssembler {
    sealed interface Result {
        class Image(val frameId: Int, val jpeg: ByteArray) : Result
        data class Error(val frameId: Int, val code: Int) : Result
    }

    companion object {
        const val ERR_SEQUENCE = 100
        const val ERR_CORRUPT = 101
    }

    private var frameId = -1
    private var expectedLen = 0
    private var expectedCrc = 0L
    private var nextChunk = 0
    private var buf: ByteArrayOutputStream? = null

    fun feed(p: ByteArray): Result? {
        if (p.isEmpty()) return null
        when (u8(p, 0)) {
            GlassesProtocol.IMG_START -> {
                if (p.size < 10) return null
                frameId = u8(p, 1)
                expectedLen = u32(p, 2).toInt()
                expectedCrc = u32(p, 6)
                nextChunk = 0
                buf = ByteArrayOutputStream(expectedLen)
            }
            GlassesProtocol.IMG_DATA -> {
                val b = buf ?: return null
                if (p.size < 4 || u8(p, 1) != frameId) return null
                if (u16(p, 2) != nextChunk) {
                    buf = null
                    return Result.Error(frameId, ERR_SEQUENCE)
                }
                b.write(p, 4, p.size - 4)
                nextChunk++
            }
            GlassesProtocol.IMG_END -> {
                val b = buf ?: return null
                buf = null
                val bytes = b.toByteArray()
                val crc = CRC32().apply { update(bytes) }.value
                return if (bytes.size == expectedLen && crc == expectedCrc) Result.Image(frameId, bytes)
                else Result.Error(frameId, ERR_CORRUPT)
            }
            GlassesProtocol.IMG_ERR -> {
                buf = null
                return Result.Error(if (p.size > 1) u8(p, 1) else -1, if (p.size > 2) u8(p, 2) else 0)
            }
        }
        return null
    }

    private fun u8(p: ByteArray, i: Int) = p[i].toInt() and 0xFF
    private fun u16(p: ByteArray, i: Int) = u8(p, i) or (u8(p, i + 1) shl 8)
    private fun u32(p: ByteArray, i: Int): Long =
        (u8(p, i).toLong()) or (u8(p, i + 1).toLong() shl 8) or (u8(p, i + 2).toLong() shl 16) or (u8(p, i + 3).toLong() shl 24)
}
