"""VLM 프로바이더 추상화.

모든 프로바이더는 같은 입력(시스템 프롬프트, 텍스트 히스토리, 이번 턴 이미지+질문)을 받아
텍스트 델타를 async 스트림으로 내보낸다. 서버/앱은 어떤 모델이 뒤에 있는지 모른다.

- AnthropicProvider     : Claude API
- OpenAICompatProvider  : OpenAI / vLLM / Ollama / LM Studio 등 (자체 GPU 서버용)
- EchoProvider          : 키 없이 파이프라인 테스트용
"""
from __future__ import annotations

import asyncio
import base64
from abc import ABC, abstractmethod
from typing import AsyncIterator

from .config import Settings
from .session import Turn


class VisionProvider(ABC):
    @abstractmethod
    def stream(
        self,
        system: str,
        history: list[Turn],
        question: str,
        image_jpeg: bytes | None,
    ) -> AsyncIterator[str]:
        """텍스트 델타를 순서대로 yield."""


class AnthropicProvider(VisionProvider):
    def __init__(self, s: Settings):
        from anthropic import AsyncAnthropic  # 지연 import: 다른 프로바이더만 쓸 땐 불필요

        if not s.anthropic_api_key:
            raise RuntimeError("ANTHROPIC_API_KEY 미설정")
        self.client = AsyncAnthropic(api_key=s.anthropic_api_key)
        self.model = s.model
        self.max_tokens = s.max_tokens

    async def stream(self, system, history, question, image_jpeg):
        messages = [{"role": t.role, "content": t.text} for t in history]
        content: list[dict] = []
        if image_jpeg:
            content.append({
                "type": "image",
                "source": {
                    "type": "base64",
                    "media_type": "image/jpeg",
                    "data": base64.b64encode(image_jpeg).decode(),
                },
            })
        content.append({"type": "text", "text": question})
        messages.append({"role": "user", "content": content})

        async with self.client.messages.stream(
            model=self.model,
            max_tokens=self.max_tokens,
            system=system,
            messages=messages,
        ) as s:
            async for text in s.text_stream:
                yield text


class OpenAICompatProvider(VisionProvider):
    def __init__(self, s: Settings):
        from openai import AsyncOpenAI

        self.client = AsyncOpenAI(base_url=s.openai_base_url, api_key=s.openai_api_key)
        self.model = s.model
        self.max_tokens = s.max_tokens

    async def stream(self, system, history, question, image_jpeg):
        messages: list[dict] = [{"role": "system", "content": system}]
        messages += [{"role": t.role, "content": t.text} for t in history]
        content: list[dict] = [{"type": "text", "text": question}]
        if image_jpeg:
            b64 = base64.b64encode(image_jpeg).decode()
            content.append({"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{b64}"}})
        messages.append({"role": "user", "content": content})

        resp = await self.client.chat.completions.create(
            model=self.model,
            messages=messages,
            max_tokens=self.max_tokens,
            stream=True,
        )
        async for chunk in resp:
            if chunk.choices and chunk.choices[0].delta.content:
                yield chunk.choices[0].delta.content


class EchoProvider(VisionProvider):
    """API 키 없이 앱↔서버 스트리밍/TTS 파이프라인을 검증하기 위한 가짜 모델."""

    async def stream(self, system, history, question, image_jpeg):
        if "YES, NO, UNSURE" in system:  # 무음 모드 테스트: 질문에 '아니'가 있으면 NO, 아니면 YES
            yield "NO" if "아니" in question else "YES"
            return
        size = f"{len(image_jpeg) // 1024}KB 이미지" if image_jpeg else "이미지 없음"
        reply = f"테스트 응답입니다. 질문은 {question} 이었고, {size}를 받았습니다. 히스토리는 {len(history)}개입니다."
        for word in reply.split(" "):
            await asyncio.sleep(0.03)
            yield word + " "


def build_provider(s: Settings) -> VisionProvider:
    match s.provider:
        case "anthropic":
            return AnthropicProvider(s)
        case "openai_compat":
            return OpenAICompatProvider(s)
        case "echo":
            return EchoProvider()
        case other:
            raise RuntimeError(f"알 수 없는 VLM_PROVIDER: {other}")
