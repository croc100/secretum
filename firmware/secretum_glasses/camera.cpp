#include "camera.h"

#include <Arduino.h>

#include "config.h"
#include "protocol.h"

namespace {
bool on = false;
uint32_t lastUse = 0;

framesize_t toFrameSize(uint8_t code) {
  switch (code) {
    case FS_SVGA: return FRAMESIZE_SVGA;
    case FS_XGA:  return FRAMESIZE_XGA;
    default:      return FRAMESIZE_VGA;
  }
}
}  // namespace

namespace camera {

bool ensureOn() {
  if (on) return true;
  camera_config_t c = {};
  c.ledc_channel = LEDC_CHANNEL_0;
  c.ledc_timer = LEDC_TIMER_0;
  c.pin_d0 = CAM_PIN_D0; c.pin_d1 = CAM_PIN_D1; c.pin_d2 = CAM_PIN_D2; c.pin_d3 = CAM_PIN_D3;
  c.pin_d4 = CAM_PIN_D4; c.pin_d5 = CAM_PIN_D5; c.pin_d6 = CAM_PIN_D6; c.pin_d7 = CAM_PIN_D7;
  c.pin_xclk = CAM_PIN_XCLK;
  c.pin_pclk = CAM_PIN_PCLK;
  c.pin_vsync = CAM_PIN_VSYNC;
  c.pin_href = CAM_PIN_HREF;
  c.pin_sccb_sda = CAM_PIN_SIOD;
  c.pin_sccb_scl = CAM_PIN_SIOC;
  c.pin_pwdn = CAM_PIN_PWDN;
  c.pin_reset = CAM_PIN_RESET;
  c.xclk_freq_hz = 20000000;
  c.pixel_format = PIXFORMAT_JPEG;
  c.frame_size = FRAMESIZE_XGA;        // 버퍼를 최대 해상도 기준으로 잡고, 캡처 시 낮춤
  c.jpeg_quality = 12;
  c.fb_count = 2;
  c.fb_location = CAMERA_FB_IN_PSRAM;
  c.grab_mode = CAMERA_GRAB_LATEST;    // 버튼 누른 '그 순간'에 가까운 프레임

  esp_err_t err = esp_camera_init(&c);
  if (err != ESP_OK) {
    Serial.printf("[camera] init failed 0x%x\n", err);
    return false;
  }
  // 자동노출/화이트밸런스 수렴 대기: 초반 프레임 버림
  for (int i = 0; i < 4; i++) {
    camera_fb_t* fb = esp_camera_fb_get();
    if (fb) esp_camera_fb_return(fb);
  }
  on = true;
  lastUse = millis();
  return true;
}

void off() {
  if (!on) return;
  esp_camera_deinit();
  on = false;
}

camera_fb_t* capture(uint8_t frameSizeCode, uint8_t quality) {
  if (!ensureOn()) return nullptr;
  sensor_t* s = esp_camera_sensor_get();
  if (s) {
    s->set_framesize(s, toFrameSize(frameSizeCode));
    s->set_quality(s, constrain(quality, 10, 40));
  }
  // 설정 변경 직후 프레임은 이전 설정일 수 있어 1장 버림
  camera_fb_t* stale = esp_camera_fb_get();
  if (stale) esp_camera_fb_return(stale);

  camera_fb_t* fb = esp_camera_fb_get();
  lastUse = millis();
  if (!fb || fb->format != PIXFORMAT_JPEG) {
    if (fb) esp_camera_fb_return(fb);
    return nullptr;
  }
  return fb;
}

void release(camera_fb_t* fb) {
  if (fb) esp_camera_fb_return(fb);
}

void idleTick() {
  if (on && millis() - lastUse > CAM_IDLE_OFF_MS) off();
}

}  // namespace camera
