#pragma once
// ===== 하드웨어 구성: Seeed XIAO ESP32-S3 Sense =====
// Arduino IDE/CLI 보드: "XIAO_ESP32S3", PSRAM: "OPI PSRAM" 필수 (카메라 프레임버퍼)

#define DEVICE_NAME "secretum"

// --- 카메라 (XIAO ESP32S3 Sense 확장보드, OV2640/OV3660/OV5640) ---
// arduino-esp32 공식 camera_pins.h(CAMERA_MODEL_XIAO_ESP32S3)와 동일
#define CAM_PIN_PWDN   -1
#define CAM_PIN_RESET  -1
#define CAM_PIN_XCLK   10
#define CAM_PIN_SIOD   40
#define CAM_PIN_SIOC   39
#define CAM_PIN_D7     48  // Y9
#define CAM_PIN_D6     11  // Y8
#define CAM_PIN_D5     12  // Y7
#define CAM_PIN_D4     14  // Y6
#define CAM_PIN_D3     16  // Y5
#define CAM_PIN_D2     18  // Y4
#define CAM_PIN_D1     17  // Y3
#define CAM_PIN_D0     15  // Y2
#define CAM_PIN_VSYNC  38
#define CAM_PIN_HREF   47
#define CAM_PIN_PCLK   13

// --- 버튼 (액티브 로우, 내부 풀업) ---
// GPIO1(D0), GPIO2(D1): RTC GPIO라 딥슬립 웨이크업 가능. GPIO3은 스트래핑 핀이라 회피.
#define PIN_BTN_A      1   // 메인 버튼 (웨이크업 겸용)
#define PIN_BTN_B      2   // 보조 버튼

// --- 햅틱: DRV2605L (I2C 0x5A) ---
#define PIN_I2C_SDA    5   // D4
#define PIN_I2C_SCL    6   // D5
#define HAPTIC_USE_LRA 0   // 0 = ERM 코인 모터, 1 = LRA

// --- 촬영 표시 LED (프라이버시: 캡처/전송 중 무조건 점등) ---
#define PIN_LED_REC    4   // D3

// --- 타이밍 ---
#define BTN_DEBOUNCE_MS     25
#define BTN_LONG_MS         600
#define BTN_DOUBLE_GAP_MS   300
#define CAM_IDLE_OFF_MS     60000UL       // 마지막 캡처 후 카메라 전원 내림
#define SLEEP_NO_CONN_MS    (5UL * 60000) // 연결 없이 5분 → 딥슬립 (버튼 A로 웨이크)
