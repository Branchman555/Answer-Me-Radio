from copy import deepcopy
from datetime import datetime, timezone
import json
from pathlib import Path
import secrets

import pytest

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = json.loads((ROOT / "docs/design/reply_radio_dj_script_fixture_v0.5.json").read_text(encoding="utf-8"))
NOW = datetime(2026, 11, 14, 17, 30, tzinfo=timezone.utc)


@pytest.fixture
def payload():
    return deepcopy(FIXTURE["request"])


@pytest.fixture
def token():
    return secrets.token_urlsafe(32)
