"""세션별 대화 히스토리 (인메모리).

설계 결정: 과거 턴은 '텍스트만' 보관한다. 이미지는 이번 턴에만 붙인다.
→ 토큰 비용/지연이 턴 수에 비례해 늘지 않음. "아까 그거"를 이미지로 기억해야 하면
  memory 훅(라이프로그)에서 캡션+임베딩으로 처리한다.

운영 단계: 단일 프로세스 가정. 워커 여러 개면 Redis로 교체.
"""
from __future__ import annotations

import time
from collections import deque
from dataclasses import dataclass, field
from threading import Lock


@dataclass
class Turn:
    role: str  # "user" | "assistant"
    text: str


@dataclass
class _Session:
    turns: deque[Turn]
    last_seen: float = field(default_factory=time.monotonic)


class SessionStore:
    def __init__(self, max_turns: int, ttl_sec: int):
        self._max_msgs = max_turns * 2
        self._ttl = ttl_sec
        self._data: dict[str, _Session] = {}
        self._lock = Lock()

    def _gc(self, now: float) -> None:
        dead = [k for k, v in self._data.items() if now - v.last_seen > self._ttl]
        for k in dead:
            del self._data[k]

    def history(self, sid: str) -> list[Turn]:
        now = time.monotonic()
        with self._lock:
            self._gc(now)
            s = self._data.get(sid)
            return list(s.turns) if s else []

    def append(self, sid: str, question: str, answer: str) -> None:
        now = time.monotonic()
        with self._lock:
            s = self._data.get(sid)
            if s is None:
                s = self._data[sid] = _Session(turns=deque(maxlen=self._max_msgs))
            s.turns.append(Turn("user", question))
            s.turns.append(Turn("assistant", answer))
            s.last_seen = now

    def reset(self, sid: str) -> None:
        with self._lock:
            self._data.pop(sid, None)
