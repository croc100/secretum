#pragma once
#include <stddef.h>
#include <stdint.h>

namespace ble {
struct Command { uint8_t op, arg0, arg1; };

void begin();
bool connected();
uint32_t lastConnectedAt();   // 마지막으로 연결돼 있던 시각(ms), 딥슬립 판단용
// BLE 콜백(호스트 태스크)에서 받은 명령을 loop()로 넘김
bool nextCommand(Command& out);
bool sendButton(uint8_t button, uint8_t gesture);
// JPEG을 START/DATA/END 패킷으로 분할 전송. 블로킹.
bool sendImage(uint8_t frameId, const uint8_t* data, size_t len);
void sendImageError(uint8_t frameId, uint8_t code);
}
