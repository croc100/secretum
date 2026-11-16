// secretum glasses firmware — XIAO ESP32-S3 Sense
// 역할: 센서/액추에이터 + BLE. AI 연산 없음. 두뇌는 폰 앱.
//
// 의존 라이브러리: NimBLE-Arduino (2.x), Adafruit DRV2605 Library
// 보드: XIAO_ESP32S3, PSRAM: OPI PSRAM

#include <Arduino.h>
#include <driver/rtc_io.h>
#include <esp_sleep.h>

#include "ble_link.h"
#include "buttons.h"
#include "camera.h"
#include "config.h"
#include "haptics.h"
#include "protocol.h"

static uint8_t frameSeq = 0;

static void handleCapture(uint8_t sizeCode, uint8_t quality) {
  uint8_t id = frameSeq++;
  digitalWrite(PIN_LED_REC, HIGH);  // 프라이버시 LED: 촬영~전송 동안 점등
  camera_fb_t* fb = camera::capture(sizeCode, quality ? quality : 12);
  if (!fb) {
    digitalWrite(PIN_LED_REC, LOW);
    ble::sendImageError(id, IMG_ERR_CAMERA);
    haptics::play(HP_ERROR);
    return;
  }
  uint32_t t0 = millis();
  bool ok = ble::sendImage(id, fb->buf, fb->len);
  Serial.printf("[img] id=%u %u bytes %s in %lums\n", id, (unsigned)fb->len, ok ? "sent" : "FAILED",
                (unsigned long)(millis() - t0));
  camera::release(fb);
  digitalWrite(PIN_LED_REC, LOW);
  if (!ok) ble::sendImageError(id, IMG_ERR_LINK);
}

static void handleButton(const buttons::Event& ev) {
  if (!ble::connected()) {
    haptics::play(HP_ERROR);  // 폰 미연결: 누른 순간 바로 알려줌
    return;
  }
  haptics::play(HP_ACK);      // 로컬 즉시 피드백 (폰 왕복 없이)
  ble::sendButton(ev.button, ev.gesture);
}

static void maybeDeepSleep() {
  if (ble::connected()) return;
  if (millis() - ble::lastConnectedAt() < SLEEP_NO_CONN_MS) return;
  Serial.println("[power] no connection, deep sleep (wake: button A)");
  camera::off();
  rtc_gpio_pullup_en((gpio_num_t)PIN_BTN_A);
  rtc_gpio_pulldown_dis((gpio_num_t)PIN_BTN_A);
  esp_sleep_enable_ext0_wakeup((gpio_num_t)PIN_BTN_A, 0);
  esp_deep_sleep_start();
}

void setup() {
  Serial.begin(115200);
  pinMode(PIN_LED_REC, OUTPUT);
  digitalWrite(PIN_LED_REC, LOW);

  if (!psramFound()) Serial.println("[boot] WARNING: PSRAM not found (보드 설정에서 OPI PSRAM 활성화)");
  haptics::begin();
  buttons::begin();
  ble::begin();
  haptics::play(HP_READY);
  Serial.println("[boot] ready");
}

void loop() {
  buttons::Event ev;
  while (buttons::poll(ev)) handleButton(ev);

  ble::Command cmd;
  while (ble::nextCommand(cmd)) {
    switch (cmd.op) {
      case OP_CAPTURE: handleCapture(cmd.arg0, cmd.arg1); break;
      case OP_HAPTIC:  haptics::play(cmd.arg0); break;
      case OP_PING:    haptics::play(HP_ACK); break;
      default: break;
    }
  }

  camera::idleTick();
  maybeDeepSleep();
  delay(5);
}
