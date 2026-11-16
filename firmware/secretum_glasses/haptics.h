#pragma once
#include <stdint.h>

namespace haptics {
bool begin();             // DRV2605L 초기화. 실패해도 나머지 기능은 동작.
void play(uint8_t pattern);  // protocol.h HapticPattern. 논블로킹(드라이버가 재생).
}
