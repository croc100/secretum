package net.crode.secretum.camera

import net.crode.secretum.glasses.FrameSize
import net.crode.secretum.glasses.GlassesLink

/** 카메라 소스 추상화: 폰 카메라(개발용) ↔ 안경 카메라(BLE)를 갈아끼움. */
interface FrameSource {
    val name: String
    /** 업로드 가능한 JPEG (긴 변 ≤ 1024px 권장). */
    suspend fun capture(): ByteArray
}

class GlassesCameraSource(
    private val link: GlassesLink,
    private val size: FrameSize = FrameSize.VGA,
) : FrameSource {
    override val name = "glasses"
    // TODO: 안경 장착 방향에 따라 회전 필요 시 펌웨어에서 sensor->set_vflip / set_hmirror 로 처리
    override suspend fun capture(): ByteArray = link.capture(size)
}
