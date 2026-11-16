# secretum

DIY AI 글라스 스켈레톤. **안경은 센서·액추에이터만, 두뇌는 폰, 모델은 서버.**

```
 ┌──────── 안경 (XIAO ESP32-S3 Sense) ────────┐
 │ 카메라 · 버튼 A/B · 햅틱(DRV2605L) · 촬영 LED │   C++ 펌웨어, AI 연산 없음
 └──────────────────┬──────────────────────────┘
                    │ BLE  (JPEG 청크 / 버튼 이벤트 / 햅틱 명령)
 ┌──────────────────┴──────── 폰 (Android) ────┐
 │ GATT 큐 · 이미지 재조립 · STT · 스트리밍 TTS   │   Kotlin 네이티브
 │ 파이프라인: 트리거 → (캡처 ∥ 음성) → 질의 → 응답 │
 └──────────────────┬──────────────────────────┘
                    │ HTTPS + SSE
 ┌──────────────────┴──────── 서버 (FastAPI) ──┐
 │ 세션 히스토리 · 무음모드 판정 · VLM 프로바이더  │   Claude / OpenAI 호환(vLLM·Ollama) / echo
 └─────────────────────────────────────────────┘
```

| 디렉토리 | 내용 |
|---|---|
| `firmware/secretum_glasses/` | ESP32-S3 펌웨어 (Arduino) |
| `android/` | 폰 앱 (minSdk 33) |
| `server/` | 백엔드 (Python 3.12+) |
| `tools/ble_probe.py` | 폰 없이 노트북으로 안경 펌웨어 검증 |
| `docs/PROTOCOL.md` | BLE / SSE 계약, 버튼·햅틱 매핑 |

## 동작

| 입력 | 동작 | 응답 |
|---|---|---|
| A 짧게 | 사진 + 음성 질문 | 이어폰 음성 (문장 단위 스트리밍) |
| A 더블 | 사진 + 음성 질문 (무음 모드) | 진동: 톡=예 / 톡…톡=아니오 / 따다닥=모름 |
| A 길게 | 대화 리셋 | 부드러운 진동 |
| B 짧게 / 길게 | 마지막 답변 다시 듣기 / 중단 | |

버튼을 누르면 안경이 **로컬에서 즉시** 틱 진동을 냄(폰 왕복 없음). 폰이 연결돼 있지 않으면 강한 버즈.

---

## 검증 순서 (아래에서 위로 쌓기)

### 1. 서버 (키 없이 echo 모드)

```bash
cd server
pip install -r requirements-dev.txt
pytest -q                                     # 계약 테스트 5개
APP_TOKEN=dev-token VLM_PROVIDER=echo uvicorn app.main:app --port 8080
```

```bash
curl -N -H "Authorization: Bearer dev-token" -F question=테스트 -F session_id=s1 localhost:8080/v1/ask
curl -N -H "Authorization: Bearer dev-token" -F question=맞아? -F session_id=s1 -F mode=silent localhost:8080/v1/ask
```

### 2. 앱 — 안경 없이 폰 카메라로

Android Studio로 `android/` 열기 → 실기기 연결

```bash
adb reverse tcp:8080 tcp:8080   # 폰의 127.0.0.1:8080 → PC 서버
```

화면의 "음성 질문" 또는 **볼륨 다운** → 말하기 → echo 응답이 TTS로 나오면 파이프라인 OK.
안경이 연결돼 있지 않으면 자동으로 폰 후면 카메라를 씀. 로그에 단계별 경과 시간(ms)이 찍힘.

### 3. 펌웨어 — 폰 없이 노트북으로

Arduino IDE 2 또는 arduino-cli

- 보드 매니저: **esp32 by Espressif 3.3.x** → 보드 `XIAO_ESP32S3`, **PSRAM: OPI PSRAM**
- 라이브러리: **NimBLE-Arduino 2.x**, **Adafruit DRV2605 Library** (+ 의존성 Adafruit BusIO)

```bash
arduino-cli compile --fqbn "esp32:esp32:XIAO_ESP32S3:PSRAM=opi" firmware/secretum_glasses
arduino-cli upload  --fqbn "esp32:esp32:XIAO_ESP32S3:PSRAM=opi" -p /dev/ttyACM0 firmware/secretum_glasses
```

```bash
pip install bleak
python tools/ble_probe.py capture --size vga --out frame.jpg   # 촬영→전송, KB/s 출력
python tools/ble_probe.py haptic NO
python tools/ble_probe.py buttons                              # 제스처 인식 확인
```

### 4. 전체 연결

앱에서 "안경 연결" → `READY` 진동 → 안경 버튼 A로 질문.

### 5. 실제 모델

`server/.env.example` 참고.

```bash
VLM_PROVIDER=anthropic ANTHROPIC_API_KEY=... VLM_MODEL=claude-sonnet-5-5
# 자체 GPU (vLLM, OpenAI 호환)
VLM_PROVIDER=openai_compat OPENAI_BASE_URL=http://<gpu-host>:8000/v1 VLM_MODEL=Qwen/Qwen2.5-VL-7B-Instruct
```

---

## 하드웨어

| 부품 | 비고 |
|---|---|
| Seeed XIAO ESP32-S3 **Sense** | 카메라 확장보드 포함, PSRAM 8MB |
| DRV2605L 햅틱 드라이버 보드 | I2C 0x5A |
| 코인형 ERM 진동모터 (10mm) | LRA 쓰면 `config.h`의 `HAPTIC_USE_LRA 1` |
| 택트 스위치 ×2, LED + 330Ω | |
| 3.7V LiPo (250~500mAh) | XIAO 뒷면 BAT 패드 |

| 연결 | XIAO 핀 |
|---|---|
| DRV2605L SDA / SCL | D4 (GPIO5) / D5 (GPIO6), VIN→3V3 |
| 모터 | DRV2605L OUT+ / OUT− (GPIO에 직접 연결 금지) |
| 버튼 A / B | D0 (GPIO1) / D1 (GPIO2) ↔ GND (내부 풀업) |
| 촬영 LED | D3 (GPIO4) → 330Ω → LED → GND |

GPIO3은 스트래핑 핀이라 피함. 버튼 A는 딥슬립 웨이크업 겸용(RTC GPIO).

## 검증 상태

| 부분 | 상태 |
|---|---|
| 서버 | echo 프로바이더로 계약 테스트 5개 통과 (스트리밍, 히스토리, 무음 모드, 인증, JPEG 검사) |
| 펌웨어 | esp32 core 3.3.12 + NimBLE-Arduino 2.5.2 + Adafruit DRV2605 1.2.4로 **컴파일 통과, 경고 0** · 플래시 18% (627KB) · RAM 14% (46KB) · 실기기 미검증 |
| CRC32 | 펌웨어 알고리즘 = zlib = java.util.zip.CRC32 교차 확인 |
| 앱 | 작성 환경에 Android SDK가 없어 **빌드 미검증**. 첫 Gradle 싱크에서 버전 조정이 필요할 수 있음 |

## 다음 단계 (TODO)

- [ ] 포그라운드 서비스로 이전 (화면 꺼진 상태 동작) — 지금은 화면 켜진 동안만
- [ ] 블루투스 이어폰 마이크 라우팅 + 이어폰 버튼(MediaSession) 트리거
- [ ] BLE 본딩 + 기기 고정 (지금은 서비스 UUID만 맞으면 아무나 붙음)
- [ ] 안경 장착 방향에 맞춘 이미지 회전 (`set_vflip` / `set_hmirror`)
- [ ] 라이프로그: 주기 스냅샷 → 캡션 → 벡터 DB (`server/app/memory.py` 훅)
- [ ] 좌우 모터 2개 (DRV2605 주소 고정 → `Wire1`로 버스 분리) → 방향 안내
- [ ] 배터리 전압 측정 (XIAO에 분압 회로 없음 → 외부 분압 + ADC)
