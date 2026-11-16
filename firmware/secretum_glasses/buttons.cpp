#include "buttons.h"

#include <Arduino.h>

#include "config.h"
#include "protocol.h"

// 버튼별 제스처 상태머신
//   누른 채 BTN_LONG_MS 경과        → LONG (떼기 전에 즉시 발화: 반응성)
//   짧게 떼고 GAP 안에 다시 누름     → 두 번째 떼는 순간 DOUBLE
//   짧게 떼고 GAP 동안 입력 없음     → SHORT
// SHORT는 DOUBLE 판별 때문에 GAP(300ms)만큼 늦게 확정됨. 지연이 거슬리면 GAP을 줄이거나
// 해당 버튼의 DOUBLE을 안 쓰는 방식으로 조정.

namespace {
struct Btn {
  uint8_t pin, id;
  bool stable = false;        // 디바운스된 눌림 상태
  bool raw = false;
  uint32_t rawChangedAt = 0;
  uint32_t pressedAt = 0;
  uint32_t releasedAt = 0;
  uint8_t clicks = 0;
  bool longFired = false;
};

Btn btns[] = {{PIN_BTN_A, BTN_A}, {PIN_BTN_B, BTN_B}};

bool step(Btn& b, uint32_t now, buttons::Event& out) {
  bool r = digitalRead(b.pin) == LOW;
  if (r != b.raw) { b.raw = r; b.rawChangedAt = now; }

  if (b.raw != b.stable && now - b.rawChangedAt >= BTN_DEBOUNCE_MS) {
    b.stable = b.raw;
    if (b.stable) {                       // pressed
      b.pressedAt = now;
      b.longFired = false;
    } else {                              // released
      b.releasedAt = now;
      if (!b.longFired) {
        b.clicks++;
        if (b.clicks >= 2) {
          b.clicks = 0;
          out = {b.id, G_DOUBLE};
          return true;
        }
      }
    }
  }

  if (b.stable && !b.longFired && now - b.pressedAt >= BTN_LONG_MS) {
    b.longFired = true;
    b.clicks = 0;
    out = {b.id, G_LONG};
    return true;
  }

  if (!b.stable && b.clicks == 1 && now - b.releasedAt >= BTN_DOUBLE_GAP_MS) {
    b.clicks = 0;
    out = {b.id, G_SHORT};
    return true;
  }
  return false;
}
}  // namespace

namespace buttons {

void begin() {
  for (auto& b : btns) pinMode(b.pin, INPUT_PULLUP);
}

bool poll(Event& out) {
  uint32_t now = millis();
  for (auto& b : btns) {
    if (step(b, now, out)) return true;
  }
  return false;
}

}  // namespace buttons
