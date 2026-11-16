"""환경변수 기반 설정. .env.example 참고."""
from __future__ import annotations

import os
from dataclasses import dataclass, field


def _env(key: str, default: str = "") -> str:
    return os.environ.get(key, default).strip()


@dataclass(frozen=True)
class Settings:
    # 앱 ↔ 서버 인증용 고정 토큰 (MVP). 운영 단계에선 기기별 토큰/JWT로 교체.
    app_token: str = field(default_factory=lambda: _env("APP_TOKEN", "dev-token"))

    # anthropic | openai_compat | echo
    provider: str = field(default_factory=lambda: _env("VLM_PROVIDER", "echo"))
    model: str = field(default_factory=lambda: _env("VLM_MODEL", "claude-sonnet-5-5"))
    max_tokens: int = field(default_factory=lambda: int(_env("VLM_MAX_TOKENS", "400")))

    anthropic_api_key: str = field(default_factory=lambda: _env("ANTHROPIC_API_KEY"))

    # OpenAI, vLLM, Ollama 등 OpenAI 호환 엔드포인트 (자체 GPU 서버 포함)
    openai_base_url: str = field(default_factory=lambda: _env("OPENAI_BASE_URL", "https://api.openai.com/v1"))
    openai_api_key: str = field(default_factory=lambda: _env("OPENAI_API_KEY", "none"))

    # 세션 히스토리: 최근 N턴(텍스트만) 유지, TTL 지나면 폐기
    history_turns: int = field(default_factory=lambda: int(_env("HISTORY_TURNS", "6")))
    session_ttl_sec: int = field(default_factory=lambda: int(_env("SESSION_TTL_SEC", "900")))

    max_image_bytes: int = field(default_factory=lambda: int(_env("MAX_IMAGE_BYTES", str(4 * 1024 * 1024))))


settings = Settings()

SYSTEM_PROMPT = """당신은 사용자가 착용한 스마트 글라스의 음성 비서다.
- 첨부 이미지는 사용자가 지금 정면으로 보고 있는 장면이다.
- 답변은 TTS로 읽힌다: 마크다운·목록·이모지·URL 금지, 1~3문장의 구어체 한국어.
- 이미지로 판단이 불확실하면 추측하지 말고 짧게 그렇다고 말한다.
- 글자(간판, 메뉴, 라벨)가 보이면 필요한 부분만 읽어주거나 번역한다.
- 질문이 이미지와 무관하면 이미지를 무시하고 답한다."""

# 무음 모드: 답을 진동(YES/NO/UNSURE)으로만 전달. 서버가 첫 토큰을 파싱해 haptic 이벤트로 변환.
SILENT_PROMPT = """당신은 사용자가 착용한 스마트 글라스의 비서이며, 지금은 무음 모드다.
첨부 이미지는 사용자가 정면으로 보고 있는 장면이다.
사용자의 질문에 대해 반드시 YES, NO, UNSURE 중 정확히 한 단어만 출력한다. 다른 글자는 출력하지 않는다.
예/아니오로 답할 수 없는 질문이거나 확신이 없으면 UNSURE."""

SILENT_VERDICTS = ("YES", "NO", "UNSURE")
