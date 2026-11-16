"""라이프로그/장기 기억 훅 (2단계 기능 자리).

TODO(phase2):
  1) image_bytes가 있으면 저비용 모델로 1문장 캡션 생성
  2) 캡션 + question + answer를 임베딩 → 벡터 DB(pgvector/Qdrant) 저장 (session_id, ts 포함)
  3) /v1/ask 진입 시 "아까", "어디 뒀", "이름 뭐였" 류 질문이면 벡터 검색 결과를
     system 프롬프트 뒤에 컨텍스트로 붙임
  * 원본 이미지는 기본 미보관(프라이버시). 보관 시 암호화 + 보존기간 정책 필수.
"""
from __future__ import annotations


async def on_turn_complete(session_id: str, question: str, answer: str, image_bytes: bytes | None) -> None:
    return None
