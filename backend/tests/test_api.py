import asyncio
import json
import logging

import httpx
from jsonschema import Draft202012Validator
import pytest

from backend.app import Settings, create_app
from backend.scripts import MockLlm
from .test_service import Provider

PATH = "/api/v1/dj/scripts"


class AsgiClient:
    """Exercise the ASGI app with HTTPX without Starlette's deprecated HTTPX wrapper."""
    def __init__(self, app):
        self.app = app

    def request(self, method, url, **kwargs):
        async def send():
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app), base_url="http://test") as api:
                return await api.request(method, url, **kwargs)
        return asyncio.run(send())

    def get(self, url, **kwargs):
        return self.request("GET", url, **kwargs)

    def post(self, url, **kwargs):
        return self.request("POST", url, **kwargs)


def client(token, **kwargs):
    return AsgiClient(create_app(Settings(token), **kwargs)), {"Authorization": f"Bearer {token}"}


@pytest.mark.parametrize("secret", ["", "short", "한" * 32, "x" * 31 + " "])
def test_server_refuses_missing_or_invalid_secret(secret):
    with pytest.raises(ValueError, match="RR_API_TOKEN"):
        create_app(Settings(secret))


@pytest.mark.parametrize("auth", [None, "Bearer invalid", "Basic invalid", "Bearer "])
def test_authentication_precedes_body_parsing(token, auth):
    provider = Provider()
    api, _ = client(token, provider=provider)
    response = api.post(PATH, content="bad JSON", headers={"Authorization": auth} if auth else {})
    assert response.status_code == 401
    assert response.headers["www-authenticate"] == "Bearer"
    assert provider.calls == 0


def test_valid_response_and_operational_logs_do_not_retain_data(payload, token, caplog):
    api, headers = client(token)
    payload["requestId"] = "PRIVATE_REQUEST_ID"
    payload["nextTracks"][0]["title"] = "PRIVATE_MUSIC_TAG"
    with caplog.at_level(logging.INFO, logger="reply_radio.api"):
        response = api.post(PATH, json=payload, headers=headers)
    assert response.status_code == 200
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["x-rr-provider"] == "mock"
    assert response.json()["requestId"] == "PRIVATE_REQUEST_ID"
    logs = " ".join(record.getMessage() for record in caplog.records if record.name == "reply_radio.api")
    assert "result=VALID" in logs
    for private in [token, "PRIVATE_REQUEST_ID", "PRIVATE_MUSIC_TAG", response.json()["text"]]:
        assert private not in logs


@pytest.mark.parametrize("body,status,content_type", [
    ("{}", 422, "application/json"), ("{", 422, "application/json"),
    ('{"requestId":"secret","requestId":"other"}', 422, "application/json"),
    ("secret" * 6000, 413, "application/json"), ("{}", 415, "text/plain"),
    ("{}", 415, "application/octet-stream"),
])
def test_body_failures_are_bounded_private_and_do_not_call_model(token, body, status, content_type):
    provider = Provider()
    api, headers = client(token, provider=provider)
    response = api.post(PATH, content=body, headers={**headers, "Content-Type": content_type})
    assert response.status_code == status
    assert "secret" not in response.text
    assert provider.calls == 0


def test_compressed_body_rejected(token):
    api, headers = client(token)
    response = api.post(PATH, content=b"compressed", headers={**headers, "Content-Type": "application/json", "Content-Encoding": "gzip"})
    assert response.status_code == 415


def test_422_never_echoes_sensitive_extra_fields(payload, token):
    api, headers = client(token)
    payload["contentUri"] = "content://sensitive-folder/user.mp3"
    response = api.post(PATH, json=payload, headers=headers)
    assert response.status_code == 422
    assert "sensitive" not in response.text


@pytest.mark.parametrize("number", ["1e999999999", "1e999999999999999999999999", "1." + "0" * 100])
def test_extreme_numeric_input_is_422_not_server_error(payload, token, number):
    api, headers = client(token)
    raw = json.dumps(payload).replace('"generation": 12', '"generation": ' + number)
    response = api.post(PATH, content=raw, headers={**headers, "Content-Type": "application/json"})
    assert response.status_code == 422


def test_rate_limit_counts_invalid_requests_and_recovers_at_boundary(payload, token):
    clock = [0.0]
    api = AsgiClient(create_app(Settings(token, requests_per_minute=2), monotonic=lambda: clock[0]))
    headers = {"Authorization": f"Bearer {token}"}
    assert api.post(PATH, json={}, headers=headers).status_code == 422
    assert api.post(PATH, json=payload, headers=headers).status_code == 200
    response = api.post(PATH, json=payload, headers=headers)
    assert response.status_code == 429 and response.headers["retry-after"] == "60"
    clock[0] = 60
    assert api.post(PATH, json=payload, headers=headers).status_code == 200


def test_inflight_limit_and_slot_release_on_provider_failure(payload, token):
    async def scenario():
        entered, release = asyncio.Event(), asyncio.Event()

        class Blocking:
            async def generate(self, prompt):
                entered.set()
                await release.wait()
                raise RuntimeError("private")

        app = create_app(Settings(token, max_inflight=1), provider=Blocking())
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as api:
            headers = {"Authorization": f"Bearer {token}"}
            first = asyncio.create_task(api.post(PATH, json=payload, headers=headers))
            await asyncio.wait_for(entered.wait(), timeout=1)
            second = await api.post(PATH, json=payload, headers=headers)
            assert second.status_code == 429 and second.json()["detail"] == "BUSY"
            release.set()
            response = await first
            assert response.status_code == 200 and response.json()["isFallback"]
            third = await api.post(PATH, json=payload, headers=headers)
            assert third.status_code == 200
    asyncio.run(scenario())


def test_chunked_body_cannot_bypass_size_limit_and_releases_slot(payload, token):
    async def scenario():
        async def chunks():
            for _ in range(5):
                yield b" " * 8192
        app = create_app(Settings(token, max_inflight=1))
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as api:
            headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
            response = await api.post(PATH, content=chunks(), headers=headers)
            assert response.status_code == 413
            assert (await api.post(PATH, json=payload, headers=headers)).status_code == 200
    asyncio.run(scenario())


def test_body_read_timeout_is_bounded_and_releases_slot(payload, token):
    async def scenario():
        async def slow():
            yield b"{"
            await asyncio.sleep(60)
        app = create_app(Settings(token, max_inflight=1))
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as api:
            headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
            response = await api.post(PATH, content=slow(), headers=headers)
            assert response.status_code == 408
            assert (await api.post(PATH, json=payload, headers=headers)).status_code == 200
    asyncio.run(scenario())


def test_openapi_describes_strict_request_response_and_bearer(payload, token):
    api, headers = client(token)
    schema = api.get("/openapi.json").json()
    operation = schema["paths"][PATH]["post"]
    assert operation["security"] == [{"HTTPBearer": []}]
    request_schema = operation["requestBody"]["content"]["application/json"]["schema"]
    Draft202012Validator({**request_schema, "components": schema["components"]}).validate(payload)
    response_schema = operation["responses"]["200"]["content"]["application/json"]["schema"]
    Draft202012Validator({**response_schema, "components": schema["components"]}).validate(api.post(PATH, json=payload, headers=headers).json())
    assert api.get("/healthz").json()["provider"] == "mock"
    assert token not in json.dumps(schema)
