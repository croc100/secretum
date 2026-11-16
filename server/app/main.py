"""secretum 백엔드.

POST /v1/ask   multipart(image?, question, session_id, mode=voice|silent) → SSE
POST /v1/reset 세션 히스토리 초기화
GET  /healthz

SSE 계약 (docs/PROTOCOL.md, 앱 AssistantClient.kt와 동일):
  data: {"type":"delta","text":"..."}            voice 모드: 답변 텍스트 조각
  data: {"type":"haptic","pattern":"YES"}        silent 모드: YES | NO | UNSURE, 오류 시 ERROR
  data: {"type":"done","text":"<전체 답변>"}
  data: {"type":"error","message":"..."}
"""
from __future__ import annotations

import json
import logging
import time
from typing import AsyncIterator, Literal

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import StreamingResponse

from .config import SILENT_PROMPT, SILENT_VERDICTS, SYSTEM_PROMPT, settings
from .memory import on_turn_complete
from .providers import build_provider
from .session import SessionStore

log = logging.getLogger("secretum")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

app = FastAPI(title="secretum-backend")
provider = build_provider(settings)
sessions = SessionStore(settings.history_turns, settings.session_ttl_sec)


def auth(authorization: str = Header(default="")) -> None:
    if authorization != f"Bearer {settings.app_token}":
        raise HTTPException(status_code=401, detail="unauthorized")


def _sse(obj: dict) -> bytes:
    return f"data: {json.dumps(obj, ensure_ascii=False)}\n\n".encode()


def parse_verdict(text: str) -> str:
    """모델 출력에서 YES/NO/UNSURE 추출. 형식이 어긋나면 UNSURE (잘못된 '예'보다 안전)."""
    head = text.strip().upper()
    for v in SILENT_VERDICTS:
        if head.startswith(v):
            return v
    return "UNSURE"


@app.get("/healthz")
async def healthz():
    return {"ok": True, "provider": settings.provider, "model": settings.model}


@app.post("/v1/reset", dependencies=[Depends(auth)])
async def reset(session_id: str = Form(...)):
    sessions.reset(session_id)
    return {"ok": True}


@app.post("/v1/ask", dependencies=[Depends(auth)])
async def ask(
    question: str = Form(...),
    session_id: str = Form(...),
    mode: Literal["voice", "silent"] = Form("voice"),
    image: UploadFile | None = File(default=None),
):
    question = question.strip()
    if not question:
        raise HTTPException(status_code=400, detail="empty question")

    image_bytes: bytes | None = None
    if image is not None:
        image_bytes = await image.read()
        if len(image_bytes) > settings.max_image_bytes:
            raise HTTPException(status_code=413, detail="image too large")
        if not image_bytes.startswith(b"\xff\xd8"):
            raise HTTPException(status_code=415, detail="image must be JPEG")

    history = sessions.history(session_id)
    system = SILENT_PROMPT if mode == "silent" else SYSTEM_PROMPT

    async def gen() -> AsyncIterator[bytes]:
        t0 = time.monotonic()
        first_token_at: float | None = None
        parts: list[str] = []
        try:
            async for delta in provider.stream(system, history, question, image_bytes):
                if first_token_at is None:
                    first_token_at = time.monotonic()
                parts.append(delta)
                if mode == "voice":
                    yield _sse({"type": "delta", "text": delta})
        except Exception as e:  # 프로바이더 오류는 스트림 안에서 앱에 전달
            log.exception("provider error")
            if mode == "silent":
                yield _sse({"type": "haptic", "pattern": "ERROR"})
            yield _sse({"type": "error", "message": str(e)[:200]})
            return

        answer = "".join(parts).strip()
        if mode == "silent":
            answer = parse_verdict(answer)
            yield _sse({"type": "haptic", "pattern": answer})

        sessions.append(session_id, question, answer)
        await on_turn_complete(session_id, question, answer, image_bytes)

        ttft = (first_token_at - t0) if first_token_at else -1
        log.info("ask sid=%s mode=%s img=%s ttft=%.2fs total=%.2fs",
                 session_id, mode, bool(image_bytes), ttft, time.monotonic() - t0)
        yield _sse({"type": "done", "text": answer})

    return StreamingResponse(
        gen(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )
