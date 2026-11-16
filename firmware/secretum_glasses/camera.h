#pragma once
#include <esp_camera.h>
#include <stdint.h>

namespace camera {
// 필요할 때만 켬(전력). 켜진 직후 노출 안정화를 위해 몇 프레임 버림.
bool ensureOn();
void off();
// 최신 프레임 획득. 성공 시 fb 반환, 사용 후 반드시 release().
camera_fb_t* capture(uint8_t frameSizeCode, uint8_t quality);
void release(camera_fb_t* fb);
// loop()에서 호출: 일정 시간 미사용 시 자동 off
void idleTick();
}
