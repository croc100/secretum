#include "haptics.h"

#include <Adafruit_DRV2605.h>
#include <Wire.h>

#include "config.h"
#include "protocol.h"

namespace {
Adafruit_DRV2605 drv;
bool ready = false;

// DRV2605 시퀀서 슬롯 최대 8개. 0x80|n = n*10ms 대기, 0 = 종료.
// 효과 번호는 TI ROM 라이브러리 기준 (1 Strong Click, 7 Soft Bump, 12 Triple Click, 14 Strong Buzz, 24 Sharp Tick)
struct Seq { uint8_t pattern; uint8_t steps[8]; };
const Seq kSeqs[] = {
  {HP_ACK,    {24, 0}},
  {HP_READY,  {7, 0}},
  {HP_ERROR,  {14, 0}},
  {HP_YES,    {1, 0}},                 // 톡
  {HP_NO,     {1, 0x80 | 25, 1, 0}},   // 톡 ... 톡  (더블클릭과 구분되게 250ms 간격)
  {HP_UNSURE, {12, 0}},                // 따다닥
  {HP_LEFT,   {1, 0}},                 // TODO: 좌우 모터 2개면 I2C 버스 분리(Wire1) 필요 (DRV2605 주소 고정)
  {HP_RIGHT,  {1, 0}},
};
}  // namespace

namespace haptics {

bool begin() {
  Wire.begin(PIN_I2C_SDA, PIN_I2C_SCL);
  if (!drv.begin(&Wire)) {
    Serial.println("[haptics] DRV2605 not found");
    return false;
  }
#if HAPTIC_USE_LRA
  drv.useLRA();
  drv.selectLibrary(6);
#else
  drv.useERM();
  drv.selectLibrary(1);
#endif
  drv.setMode(DRV2605_MODE_INTTRIG);
  ready = true;
  return true;
}

void play(uint8_t pattern) {
  if (!ready) return;
  for (const auto& s : kSeqs) {
    if (s.pattern != pattern) continue;
    drv.stop();
    for (uint8_t slot = 0; slot < 8; slot++) {
      drv.setWaveform(slot, s.steps[slot]);
      if (s.steps[slot] == 0) break;
    }
    drv.go();
    return;
  }
}

}  // namespace haptics
