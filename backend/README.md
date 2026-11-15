# Reply Radio · Mock DJ API

RR-006의 로컬 FastAPI 서비스입니다. 외부 LLM 호출이나 Android 네트워크 연결을 활성화하지 않습니다.

- `POST /api/v1/dj/scripts` — v0.5 대본 계약, Bearer 인증 필수.
- `GET /healthz` — Mock 상태와 정책 버전.
- `GET /openapi.json` — 실제 요청·응답 스키마.

Python 3.11 환경에서 저장소 루트 기준으로 실행합니다.

```powershell
py -3.11 -m venv backend/.venv
backend/.venv/Scripts/python.exe -m pip install --require-hashes -r backend/requirements.lock
backend/.venv/Scripts/python.exe -X utf8 tools/check_backend.py
```

[서버 시작·토큰 설정·제한·검증 결과](../docs/rr006-backend.md) · [프로젝트 소개](../README.md)
