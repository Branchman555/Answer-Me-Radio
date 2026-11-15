"""One-process localhost API. No default secret, external provider or Android key."""
import asyncio
from collections import deque
from dataclasses import dataclass, field
import hashlib
import hmac
import logging
import math
import os
import time

from fastapi import Depends, FastAPI, HTTPException, Request, Response
from fastapi.openapi.utils import get_openapi
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import ValidationError

from .models import DjRequest, DjResponse, MAX_BYTES, strict_json
from .scripts import MockLlm, POLICY_VERSION, generate

logger = logging.getLogger("reply_radio.api")


@dataclass(frozen=True)
class Settings:
    token: str = field(repr=False)
    requests_per_minute: int = 30
    max_inflight: int = 4

    def __post_init__(self):
        if not self.token.isascii() or not 32 <= len(self.token) <= 256 or any(c.isspace() for c in self.token):
            raise ValueError("RR_API_TOKEN must be a random 32..256 character ASCII secret")
        if self.requests_per_minute < 1 or self.max_inflight < 1:
            raise ValueError("limits must be positive")


def create_app(settings: Settings | None = None, provider=None, monotonic=time.monotonic) -> FastAPI:
    settings = settings or Settings(os.environ.get("RR_API_TOKEN", ""))
    provider = provider if provider is not None else MockLlm()
    token_hash = hashlib.sha256(settings.token.encode()).digest()
    arrivals = deque()
    inflight = 0
    app = FastAPI(title="Reply Radio · Mock DJ API", version="0.6.0", docs_url=None, redoc_url=None)
    bearer = HTTPBearer(auto_error=False)

    def authenticate(credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
        supplied = credentials.credentials if credentials else ""
        if not hmac.compare_digest(hashlib.sha256(supplied.encode()).digest(), token_hash):
            raise HTTPException(401, "UNAUTHORIZED", headers={"WWW-Authenticate": "Bearer"})

    @app.get("/healthz")
    async def health():
        return {"status": "ok", "provider": "mock", "policyVersion": POLICY_VERSION}

    request_schema = DjRequest.model_json_schema(ref_template="#/components/schemas/{model}")
    definitions = request_schema.pop("$defs", {})

    @app.post("/api/v1/dj/scripts", response_model=DjResponse, dependencies=[Depends(authenticate)],
              openapi_extra={"requestBody": {"required": True, "content": {"application/json": {"schema": request_schema}}}})
    async def scripts(request: Request, response: Response):
        nonlocal inflight
        started = monotonic()
        while arrivals and arrivals[0] <= started - 60:
            arrivals.popleft()
        if len(arrivals) >= settings.requests_per_minute:
            retry = max(1, math.ceil(60 - (started - arrivals[0])))
            raise HTTPException(429, "RATE_LIMITED", headers={"Retry-After": str(retry)})
        arrivals.append(started)  # Invalid authenticated bodies also consume quota.
        if inflight >= settings.max_inflight:
            raise HTTPException(429, "BUSY", headers={"Retry-After": "1"})
        # No await between admission check and increment: single event-loop owner.
        inflight += 1
        try:
            if request.headers.get("content-type", "").split(";", 1)[0].strip().lower() != "application/json":
                raise HTTPException(415, "JSON_REQUIRED")
            if request.headers.get("content-encoding", "identity").lower() != "identity":
                raise HTTPException(415, "ENCODING_UNSUPPORTED")
            body = bytearray()
            try:
                async with asyncio.timeout(3):
                    async for chunk in request.stream():
                        if len(body) + len(chunk) > MAX_BYTES:
                            raise HTTPException(413, "BODY_TOO_LARGE")
                        body.extend(chunk)
            except TimeoutError:
                raise HTTPException(408, "BODY_TIMEOUT") from None
            try:
                payload = DjRequest.model_validate(strict_json(bytes(body)))
            except (ValueError, TypeError, RecursionError, ValidationError):
                # Never reflect tags, paths, coordinates, tokens or raw exception input.
                raise HTTPException(422, "REQUEST_INVALID") from None
            result = await generate(payload, provider)
            logger.info("dj policy=%s type=%s result=%s fallback=%s elapsed_ms=%d", POLICY_VERSION,
                        payload.type, result.code, result.response.isFallback, round((monotonic() - started) * 1000))
            response.headers["Cache-Control"] = "no-store"
            response.headers["X-RR-Provider"] = "mock"
            response.headers["X-RR-Result"] = result.code
            return result.response
        finally:
            inflight -= 1

    def openapi():
        if app.openapi_schema is None:
            schema = get_openapi(title=app.title, version=app.version, routes=app.routes)
            schema.setdefault("components", {}).setdefault("schemas", {}).update(definitions)
            app.openapi_schema = schema
        return app.openapi_schema
    app.openapi = openapi
    return app
