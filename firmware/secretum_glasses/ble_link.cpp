// NimBLE-Arduino 2.x API 기준
#include "ble_link.h"

#include <Arduino.h>
#include <NimBLEDevice.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>

#include "config.h"
#include "crc32.h"
#include "protocol.h"

namespace {
NimBLEServer* server = nullptr;
NimBLECharacteristic* chrImg = nullptr;
NimBLECharacteristic* chrBtn = nullptr;
QueueHandle_t cmdQueue = nullptr;

volatile bool isConnected = false;
volatile uint16_t connHandle = 0;
volatile uint16_t mtu = 23;
volatile uint32_t lastConnAt = 0;

class ServerCb : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* s, NimBLEConnInfo& info) override {
    isConnected = true;
    connHandle = info.getConnHandle();
    lastConnAt = millis();
    // 처리량을 위해 짧은 연결 간격 요청 (7.5~15ms). 폰이 최종 결정.
    s->updateConnParams(info.getConnHandle(), 6, 12, 0, 400);
  }
  void onDisconnect(NimBLEServer* s, NimBLEConnInfo& info, int reason) override {
    isConnected = false;
    mtu = 23;
    lastConnAt = millis();
    NimBLEDevice::startAdvertising();
  }
  void onMTUChange(uint16_t m, NimBLEConnInfo& info) override { mtu = m; }
};

class CtrlCb : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* c, NimBLEConnInfo& info) override {
    NimBLEAttValue v = c->getValue();
    if (v.length() < 1) return;
    ble::Command cmd{v.data()[0], v.length() > 1 ? v.data()[1] : (uint8_t)0, v.length() > 2 ? v.data()[2] : (uint8_t)0};
    xQueueSend(cmdQueue, &cmd, 0);  // 큐가 차면 버림 (폰이 타임아웃 후 재시도)
  }
};

// 알림 송신 버퍼가 차면 notify가 false → 잠깐 쉬고 재시도 (흐름 제어)
bool notifyReliable(NimBLECharacteristic* chr, const uint8_t* buf, size_t n) {
  for (int tries = 0; tries < 400; tries++) {
    if (!isConnected) return false;
    if (chr->notify(buf, n)) return true;
    delay(3);
  }
  return false;
}

inline void putU16(uint8_t* p, uint16_t v) { p[0] = v & 0xFF; p[1] = v >> 8; }
inline void putU32(uint8_t* p, uint32_t v) { for (int i = 0; i < 4; i++) p[i] = (v >> (8 * i)) & 0xFF; }
}  // namespace

namespace ble {

void begin() {
  cmdQueue = xQueueCreate(8, sizeof(Command));

  NimBLEDevice::init(DEVICE_NAME);
  NimBLEDevice::setMTU(517);

  server = NimBLEDevice::createServer();
  server->setCallbacks(new ServerCb());

  NimBLEService* svc = server->createService(SVC_UUID);
  NimBLECharacteristic* ctrl =
      svc->createCharacteristic(CHR_CTRL_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
  ctrl->setCallbacks(new CtrlCb());
  chrImg = svc->createCharacteristic(CHR_IMG_UUID, NIMBLE_PROPERTY::NOTIFY);
  chrBtn = svc->createCharacteristic(CHR_BTN_UUID, NIMBLE_PROPERTY::NOTIFY);
  // NimBLE 2.x: 서비스는 서버 시작 시 자동 시작 (svc->start() 불필요)

  // TODO(security): 출시 전 본딩/페어링(Just Works 이상) + 화이트리스트. 지금은 아무 폰이나 붙을 수 있음.
  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  adv->addServiceUUID(SVC_UUID);
  adv->setName(DEVICE_NAME);
  adv->enableScanResponse(true);
  adv->start();
}

bool connected() { return isConnected; }
uint32_t lastConnectedAt() { return isConnected ? millis() : lastConnAt; }

bool nextCommand(Command& out) { return xQueueReceive(cmdQueue, &out, 0) == pdTRUE; }

bool sendButton(uint8_t button, uint8_t gesture) {
  uint8_t p[2] = {button, gesture};
  return notifyReliable(chrBtn, p, sizeof(p));
}

bool sendImage(uint8_t frameId, const uint8_t* data, size_t len) {
  if (!isConnected) return false;

  uint8_t start[10] = {IMG_START, frameId};
  putU32(start + 2, (uint32_t)len);
  putU32(start + 6, crc32_std(data, len));
  if (!notifyReliable(chrImg, start, sizeof(start))) return false;

  const size_t payload = (mtu > 3 + 4 + 20 ? mtu : 3 + 4 + 20) - 3 - 4;  // ATT 헤더 3B + 우리 헤더 4B
  static uint8_t pkt[517];
  uint16_t idx = 0;
  for (size_t off = 0; off < len; off += payload, idx++) {
    size_t n = min(payload, len - off);
    pkt[0] = IMG_DATA;
    pkt[1] = frameId;
    putU16(pkt + 2, idx);
    memcpy(pkt + 4, data + off, n);
    if (!notifyReliable(chrImg, pkt, n + 4)) return false;
  }

  uint8_t end[2] = {IMG_END, frameId};
  return notifyReliable(chrImg, end, sizeof(end));
}

void sendImageError(uint8_t frameId, uint8_t code) {
  uint8_t p[3] = {IMG_ERR, frameId, code};
  notifyReliable(chrImg, p, sizeof(p));
}

}  // namespace ble
