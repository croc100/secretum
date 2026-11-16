"""echo 프로바이더로 API 계약 검증 (키 불필요).  실행: cd server && pytest -q"""
import json
import os

os.environ["APP_TOKEN"] = "t"
os.environ["VLM_PROVIDER"] = "echo"

from fastapi.testclient import TestClient  # noqa: E402

from app.main import app, parse_verdict  # noqa: E402

client = TestClient(app)
AUTH = {"Authorization": "Bearer t"}
JPEG = b"\xff\xd8\xff\xe0" + b"\x00" * 2048


def events(resp):
    return [json.loads(l[5:]) for l in resp.text.splitlines() if l.startswith("data:")]


def test_auth_required():
    r = client.post("/v1/ask", data={"question": "hi", "session_id": "s"})
    assert r.status_code == 401


def test_voice_stream_and_history():
    r = client.post("/v1/ask", headers=AUTH, data={"question": "이거 뭐야", "session_id": "v1"},
                    files={"image": ("f.jpg", JPEG, "image/jpeg")})
    ev = events(r)
    assert ev[0]["type"] == "delta"
    assert ev[-1]["type"] == "done" and "2KB" in ev[-1]["text"]
    assert all(e["type"] != "haptic" for e in ev)

    r2 = client.post("/v1/ask", headers=AUTH, data={"question": "다시", "session_id": "v1"})
    assert "히스토리는 2개" in events(r2)[-1]["text"]


def test_silent_mode_haptic():
    r = client.post("/v1/ask", headers=AUTH, data={"question": "이거 매워?", "session_id": "s1", "mode": "silent"})
    ev = events(r)
    assert [e["type"] for e in ev] == ["haptic", "done"]
    assert ev[0]["pattern"] == "YES"

    r = client.post("/v1/ask", headers=AUTH, data={"question": "아니지?", "session_id": "s1", "mode": "silent"})
    assert events(r)[0]["pattern"] == "NO"


def test_rejects_non_jpeg():
    r = client.post("/v1/ask", headers=AUTH, data={"question": "x", "session_id": "s"},
                    files={"image": ("f.png", b"\x89PNG....", "image/png")})
    assert r.status_code == 415


def test_parse_verdict_defaults_to_unsure():
    assert parse_verdict(" yes.") == "YES"
    assert parse_verdict("NO") == "NO"
    assert parse_verdict("아마 그럴걸요") == "UNSURE"
