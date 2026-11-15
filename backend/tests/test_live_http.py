"""Real localhost Uvicorn/socket proof; faults exist only in this injected test provider."""
import asyncio
from copy import deepcopy
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import socket
import threading
import time

import httpx
import uvicorn

from backend.app import Settings, create_app
from backend.scripts import MockLlm
from .test_api import PATH
from .test_contract import oracle


def test_real_http_exports_android_compatible_mock_and_fallback(payload, token):
    class Faults(MockLlm):
        calls = 0
        cancellations = 0

        async def generate(self, prompt):
            self.calls += 1
            request_id = json.loads(prompt.data_json)["requestId"]
            if request_id == "live-invalid":
                return '{"trackIds":["other"]}'
            if request_id == "live-error":
                raise RuntimeError("not logged")
            if request_id == "live-timeout":
                try:
                    await asyncio.sleep(60)
                except asyncio.CancelledError:
                    self.cancellations += 1
                    raise
            return await super().generate(prompt)

    provider = Faults()
    app = create_app(Settings(token), provider=provider)
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    config = uvicorn.Config(app, host="127.0.0.1", port=port, access_log=False, log_level="error")
    server = uvicorn.Server(config)
    thread = threading.Thread(target=lambda: server.run(sockets=[sock]), daemon=True)
    thread.start()
    cases = []
    try:
        deadline = time.monotonic() + 10
        while not server.started and time.monotonic() < deadline:
            time.sleep(0.02)
        assert server.started
        with httpx.Client(base_url=f"http://127.0.0.1:{port}", timeout=6) as api:
            assert api.get("/healthz").json()["provider"] == "mock"
            assert api.post(PATH, json=payload).status_code == 401
            headers = {"Authorization": f"Bearer {token}"}
            scenarios = [(kind.lower(), kind, condition) for kind in ["OPENING", "ERA_BRIDGE", "SAME_ERA_BRIDGE"]
                         for condition in [None, "RAIN"]]
            scenarios += [(name, "ERA_BRIDGE", None) for name in ["invalid", "error", "timeout"]]
            for name, kind, condition in scenarios:
                request = deepcopy(payload)
                request.update(requestId=f"live-{name}", type=kind, maxSpokenSeconds=25)
                now = datetime.now(timezone.utc)
                request["context"]["weather"] = None if condition is None else {
                    "condition": condition, "valid": True, "observedAt": now.isoformat()}
                started = time.monotonic()
                response = api.post(PATH, json=request, headers=headers)
                elapsed = time.monotonic() - started
                assert response.status_code == 200
                result = response.json()
                oracle("request", request)
                oracle("response", result)
                expected = {"invalid": "SCHEMA_INVALID", "error": "PROVIDER_FAILED", "timeout": "AI_TIMEOUT"}.get(name, "VALID")
                assert response.headers["x-rr-result"] == expected
                assert result["isFallback"] == (expected != "VALID")
                if name == "timeout":
                    assert 2.9 <= elapsed < 4.5
                cases.append({"name": name + ("-rain" if condition else "-dry"), "now": now.isoformat(),
                              "request": request, "response": result, "result": expected})
            assert provider.calls == 9 and provider.cancellations == 1
    finally:
        server.should_exit = True
        thread.join(timeout=5)
        sock.close()
        assert not thread.is_alive()
    output = os.environ.get("RR006_CONTRACT_OUTPUT")
    if output:
        Path(output).parent.mkdir(parents=True, exist_ok=True)
        Path(output).write_text(json.dumps({"source": "Uvicorn localhost HTTP; synthetic data; injected test faults", "cases": cases},
                                         ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
