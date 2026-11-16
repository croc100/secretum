#pragma once
#include <stdint.h>

namespace buttons {
struct Event { uint8_t button; uint8_t gesture; };  // protocol.h ButtonId, Gesture

void begin();
// loop()에서 매번 호출. 제스처가 확정되면 true와 이벤트 반환.
bool poll(Event& out);
}
