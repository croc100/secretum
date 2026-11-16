# 프로토콜

세 컴포넌트 사이 계약. 바꿀 때는 아래 파일을 **같이** 수정해야 함.

| 계약 | 펌웨어 | 앱 | 서버 / 도구 |
|---|---|---|---|
| BLE | `firmware/secretum_glasses/protocol.h` | `glasses/GlassesProtocol.kt` | `tools/ble_probe.py` |
| HTTP/SSE | – | `net/AssistantClient.kt` | `server/app/main.py` |

---

## 1. BLE GATT (안경 ↔ 폰)

서비스 `8b6e0001-5a3c-4d8e-9f21-6a1b2c3d4e5f`

| 특성 | UUID 끝자리 | 방향 | 속성 |
|---|---|---|---|
| CTRL | `...0002` | 폰 → 안경 | Write (응답 있음) |
| IMG  | `...0003` | 안경 → 폰 | Notify |
| BTN  | `...0004` | 안경 → 폰 | Notify |

연결 직후 폰이 하는 일 (순서대로, GATT 직렬 큐로): MTU 517 요청 → 2M PHY 선호 → 서비스 탐색 → IMG, BTN CCCD 활성화 → `READY` 햅틱.

### CTRL 명령 `[op, arg0, arg1]`

| op | 이름 | arg0 | arg1 |
|---|---|---|---|
| `0x01` | CAPTURE | 해상도 0=VGA, 1=SVGA, 2=XGA | JPEG quality 10~40 (낮을수록 고화질, 기본 12) |
| `0x02` | HAPTIC | 패턴 ID (아래) | – |
| `0x03` | PING | – | – (안경이 ACK 진동) |

### IMG 패킷 (JPEG 1장 = START + DATA×N + END)

```
START [0xA0, frameId, len:u32le, crc32:u32le]      CRC-32 = zlib / java.util.zip.CRC32
DATA  [0xA1, frameId, chunkIdx:u16le, payload...]  payload = MTU - 3(ATT) - 4(헤더)
END   [0xA2, frameId]
ERR   [0xAF, frameId, code]                        1=카메라 실패, 2=링크 실패
```

폰은 chunkIdx 연속성 + 길이 + CRC를 검사하고, 틀리면 1회 재캡처.

### BTN 이벤트 `[buttonId, gesture]`

buttonId: 0=A, 1=B · gesture: 1=SHORT, 2=DOUBLE, 3=LONG

안경은 제스처를 확정하는 순간 **로컬에서 바로** ACK 진동을 내고(폰 왕복 없음), 폰이 연결돼 있지 않으면 ERROR 진동을 냄.

| 버튼 | 짧게 | 더블 | 길게 |
|---|---|---|---|
| A | 음성 질문 | 무음 질문 (진동으로 답) | 대화 리셋 |
| B | 마지막 답변 다시 듣기 | 중단 | 중단 |

### 햅틱 패턴

| ID | 이름 | 느낌 (DRV2605 ROM 효과) | 용도 |
|---|---|---|---|
| `0x01` | ACK | 가벼운 틱 (24) | 버튼 인식 |
| `0x02` | READY | 부드러운 범프 (7) | 연결됨, 리셋 |
| `0x03` | ERROR | 강한 버즈 (14) | 실패, 미연결, 음성 미인식 |
| `0x04` | YES | 톡 (1) | 무음 모드 예 |
| `0x05` | NO | 톡 … 톡 (1, 250ms, 1) | 무음 모드 아니오 |
| `0x06` | UNSURE | 따다닥 (12) | 무음 모드 모름 |
| `0x10/0x11` | LEFT/RIGHT | 예약 | 좌우 모터 2개 구성 시 방향 안내 |

---

## 2. HTTP (폰 ↔ 서버)

`POST /v1/ask` · `Authorization: Bearer <APP_TOKEN>` · multipart/form-data

| 필드 | 필수 | 설명 |
|---|---|---|
| `question` | ✓ | STT 결과 텍스트 |
| `session_id` | ✓ | 앱이 생성한 UUID. 리셋 시 새로 발급 |
| `mode` | | `voice`(기본) \| `silent` |
| `image` | | JPEG (≤ 4MB). 아니면 415 |

응답: `text/event-stream`

```
data: {"type":"delta","text":"..."}        voice: 답변 조각 (문장 단위로 TTS)
data: {"type":"haptic","pattern":"YES"}    silent: YES | NO | UNSURE, 실패 시 ERROR
data: {"type":"done","text":"전체 답변"}
data: {"type":"error","message":"..."}
```

서버는 세션당 최근 N턴을 **텍스트만** 보관함 (이미지는 그 턴에만 첨부 → 비용이 대화 길이에 비례해 늘지 않음).
무음 모드에서 모델 출력이 형식을 벗어나면 `UNSURE`로 처리함 (잘못된 '예'보다 안전).

`POST /v1/reset` (session_id) · `GET /healthz`
