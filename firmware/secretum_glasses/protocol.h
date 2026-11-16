#pragma once
// ===== BLE 프로토콜 (docs/PROTOCOL.md, Android GlassesProtocol.kt와 반드시 동일) =====
#include <stdint.h>

#define SVC_UUID      "8b6e0001-5a3c-4d8e-9f21-6a1b2c3d4e5f"
#define CHR_CTRL_UUID "8b6e0002-5a3c-4d8e-9f21-6a1b2c3d4e5f"  // phone → glasses, write
#define CHR_IMG_UUID  "8b6e0003-5a3c-4d8e-9f21-6a1b2c3d4e5f"  // glasses → phone, notify
#define CHR_BTN_UUID  "8b6e0004-5a3c-4d8e-9f21-6a1b2c3d4e5f"  // glasses → phone, notify

// CTRL 명령: [op, arg0, arg1]
enum CtrlOp : uint8_t {
  OP_CAPTURE = 0x01,  // arg0 = FrameSizeCode, arg1 = JPEG quality (10~40, 낮을수록 고화질)
  OP_HAPTIC  = 0x02,  // arg0 = HapticPattern
  OP_PING    = 0x03,
};

enum FrameSizeCode : uint8_t {
  FS_VGA  = 0,  // 640x480  (기본, VLM에 충분)
  FS_SVGA = 1,  // 800x600
  FS_XGA  = 2,  // 1024x768 (작은 글자 읽기)
};

// IMG 패킷
//   START [0xA0, frameId, len u32 LE, crc32 u32 LE]
//   DATA  [0xA1, frameId, chunkIdx u16 LE, payload...]
//   END   [0xA2, frameId]
//   ERR   [0xAF, frameId, errCode]
enum ImgPkt : uint8_t { IMG_START = 0xA0, IMG_DATA = 0xA1, IMG_END = 0xA2, IMG_ERR = 0xAF };
enum ImgErr : uint8_t { IMG_ERR_CAMERA = 1, IMG_ERR_LINK = 2 };

// BTN 이벤트: [buttonId, gesture]
enum ButtonId : uint8_t { BTN_A = 0, BTN_B = 1 };
enum Gesture  : uint8_t { G_SHORT = 1, G_DOUBLE = 2, G_LONG = 3 };

// 햅틱 패턴 ID (펌웨어 haptics.cpp에서 DRV2605 시퀀스로 매핑)
enum HapticPattern : uint8_t {
  HP_ACK    = 0x01,  // 버튼 인식 (로컬 즉시)
  HP_READY  = 0x02,  // 준비됨/부팅
  HP_ERROR  = 0x03,  // 실패, 폰 미연결
  HP_YES    = 0x04,  // 무음 모드: 예
  HP_NO     = 0x05,  // 무음 모드: 아니오
  HP_UNSURE = 0x06,  // 무음 모드: 모름
  HP_LEFT   = 0x10,  // 예약: 좌우 모터 2개 구성 시 방향 안내
  HP_RIGHT  = 0x11,
};
