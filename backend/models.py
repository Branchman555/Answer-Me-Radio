"""v0.5 wire models. All fields required; unknown fields and coercions rejected."""
from datetime import datetime
from decimal import Decimal, DecimalException
import json
import re
from typing import Annotated, Literal
import unicodedata

from pydantic import BaseModel, BeforeValidator, ConfigDict, Field, field_validator

MAX_BYTES = 32_768
DjType = Literal["OPENING", "ERA_BRIDGE", "SAME_ERA_BRIDGE"]
Topic = Literal["ERA_TRANSITION", "MUSIC_METADATA", "WEATHER", "GENERAL_NOSTALGIA", "LIGHT_HUMOR"]
Short = Annotated[str, Field(min_length=1, max_length=100)]
Era = Annotated[str, Field(pattern=r"^(19|20)[0-9]0s$")]


def integer(value):
    # JSON Schema integer includes 12.0, but never strings or booleans.
    if isinstance(value, Decimal) and value.is_finite():
        if not -(2**63 - 1) <= value <= 2**63 - 1:
            raise ValueError("integer outside Android Long range")
        if value == value.to_integral_value():
            return int(value)
    return value


Integer = Annotated[int, BeforeValidator(integer)]


class WireModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class Track(WireModel):
    id: Short
    title: Short
    artist: Short
    year: Annotated[Integer, Field(ge=1900, le=2100)]

    @field_validator("id")
    @classmethod
    def opaque_id(cls, value):
        if any(char in value for char in ":/\\"):
            raise ValueError("opaque track ID required")
        return value

    @field_validator("title", "artist", mode="before")
    @classmethod
    def metadata(cls, value):
        if isinstance(value, str):
            return unicodedata.normalize("NFC", "".join(c for c in value if unicodedata.category(c) != "Cc")).strip()
        return value


class Weather(WireModel):
    condition: Literal["RAIN", "SUN", "CLOUD", "SNOW", "UNKNOWN"]
    observedAt: Annotated[str, Field(json_schema_extra={"format": "date-time"})]
    valid: bool

    @field_validator("observedAt")
    @classmethod
    def timestamp(cls, value):
        if not re.fullmatch(r"\d{4}-\d\d-\d\d[Tt]\d\d:\d\d:\d\d(?:\.\d+)?(?:[Zz]|[+-]\d\d:\d\d)", value):
            raise ValueError("RFC3339 timestamp with offset required")
        datetime.fromisoformat(value.upper().replace("Z", "+00:00"))
        return value


class Context(WireModel):
    timeOfDay: Literal["MORNING", "DAY", "EVENING", "NIGHT", "UNKNOWN"]
    weather: Weather | None
    mode: Literal["SINGLE", "FAMILY"]
    previousEra: Era | None
    nextEra: Era | None


class Correlation(WireModel):
    schemaVersion: Literal["1.0"]
    requestId: Short
    sessionId: Short
    generation: Annotated[Integer, Field(ge=0, le=2**63 - 1)]
    segmentId: Short
    type: DjType
    personaId: Literal["retro_fm_90_v1"]


class DjRequest(Correlation):
    locale: Literal["ko-KR"]
    maxSpokenSeconds: Annotated[Integer, Field(ge=5, le=30)]
    context: Context
    previousTracks: Annotated[list[Track], Field(max_length=3)]
    nextTracks: Annotated[list[Track], Field(max_length=3)]
    recentTopics: Annotated[list[Topic], Field(max_length=3)]


class Delivery(WireModel):
    tone: Literal["WARM", "CALM", "BRIGHT"]
    pace: Literal["RELAXED", "NORMAL"]


Grounding = Annotated[str, Field(pattern=r"^(context\.(timeOfDay|previousEra|nextEra|weather\.condition)|previousTracks\[[0-2]\]\.(title|artist|year)|nextTracks\[[0-2]\]\.(title|artist|year))$")]


class DjResponse(Correlation):
    text: Annotated[str, Field(min_length=10, max_length=160)]
    topic: Topic
    delivery: Delivery
    groundingFields: Annotated[list[Grounding], Field(max_length=12, json_schema_extra={"uniqueItems": True})]
    isFallback: bool

    @field_validator("groundingFields")
    @classmethod
    def unique(cls, value):
        if len(value) != len(set(value)):
            raise ValueError("duplicate grounding")
        return value


def strict_json(raw: bytes | str):
    """No duplicate keys, non-finite numbers, trailing JSON or nesting bombs."""
    if isinstance(raw, bytes):
        raw = raw.decode("utf-8", errors="strict")
    if not isinstance(raw, str) or len(raw.encode("utf-8")) > MAX_BYTES:
        raise ValueError("JSON size")

    def pairs(entries):
        result = {}
        for key, value in entries:
            if key in result:
                raise ValueError("duplicate JSON key")
            result[key] = value
        return result

    def constant(_):
        raise ValueError("non-finite JSON")

    def decimal(token):
        if len(token) > 64:
            raise ValueError("numeric token too long")
        try:
            return Decimal(token)
        except DecimalException:
            raise ValueError("invalid numeric token") from None

    value = json.loads(raw, object_pairs_hook=pairs, parse_float=decimal, parse_constant=constant)

    def check(item, depth=0):
        if depth > 16:
            raise ValueError("JSON depth")
        if isinstance(item, dict):
            for key, child in item.items():
                check(key, depth + 1)
                check(child, depth + 1)
        elif isinstance(item, list):
            for child in item:
                check(child, depth + 1)
        elif isinstance(item, str):
            item.encode("utf-8", errors="strict")
    check(value)
    return value
