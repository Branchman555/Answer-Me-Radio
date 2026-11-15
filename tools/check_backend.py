"""RR-006: run installed locked dependencies, real HTTP tests and export JVM fixtures."""
import argparse
from datetime import datetime, timezone
import hashlib
from importlib.metadata import version
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence-prefix", choices=["rr006", "ui001", "ui002"], default="rr006")
    args = parser.parse_args()
    if sys.version_info[:2] != (3, 11):
        raise SystemExit("Use Python 3.11 in backend/.venv (the verified runtime).")
    lock = ROOT / "backend/requirements.lock"
    dependencies = dict(re.findall(r"^([A-Za-z0-9_.-]+)==([^\s]+)", lock.read_text(encoding="utf-8"), re.M))
    assert dependencies
    for name, expected in dependencies.items():
        assert version(name) == expected, f"Install locked dependency: {name}=={expected}"
    subprocess.run([sys.executable, "-m", "pip", "check"], cwd=ROOT, check=True)
    run_id = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    log = ROOT / f"evidence/backend-{run_id}.log"
    junit = ROOT / "backend/build/junit.xml"
    junit.parent.mkdir(parents=True, exist_ok=True)
    junit.unlink(missing_ok=True)
    capture = ROOT / "backend/fixtures/android-contracts.json"
    env = dict(os.environ, PYTHONUTF8="1", RR006_CONTRACT_OUTPUT=str(capture))
    result = subprocess.run([sys.executable, "-m", "pytest", "backend/tests", "-q", "-W", "error",
                             f"--junitxml={junit}"], cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    log.write_bytes(result.stdout)
    print(result.stdout.decode("utf-8", errors="replace"))
    if result.returncode:
        raise SystemExit(result.returncode)
    suites = ET.parse(junit).getroot()
    cases = suites.findall(".//testcase")
    assert cases and not suites.findall(".//failure") and not suites.findall(".//error") and not suites.findall(".//skipped")
    captures = json.loads(capture.read_text(encoding="utf-8"))["cases"]
    assert len(captures) == 9 and {x["result"] for x in captures} == {"VALID", "SCHEMA_INVALID", "PROVIDER_FAILED", "AI_TIMEOUT"}
    files = sorted((ROOT / "backend").glob("*.py")) + sorted((ROOT / "backend/tests").glob("*.py")) + [capture, lock, Path(__file__).resolve()]
    evidence = {"ticket": "UI-002" if args.evidence_prefix == "ui002" else "UI-001" if args.evidence_prefix == "ui001" else "RR-006", "status": "PASS", "recordedAtUtc": datetime.now(timezone.utc).isoformat(),
                "python": platform.python_version(), "dependencies": dependencies, "testCount": len(cases),
                "tests": [{"name": case.attrib["classname"] + "." + case.attrib["name"], "status": "PASS"} for case in cases],
                "liveHttp": {"transport": "Uvicorn TCP localhost", "cases": 9, "modelTimeoutSeconds": 3,
                             "retryCount": 0, "timeoutCancellationObserved": True, "provider": "Mock + injected test faults"},
                "sourceHashes": {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in files},
                "log": log.relative_to(ROOT).as_posix(),
                "limitations": ["Local single-process Mock API; no external model, cloud deployment or Android HTTP wiring",
                                "Approved templates only; original example is schema data, not general editorial authorization",
                                "Text length heuristic is not measured TTS duration or human listening evaluation"]}
    (ROOT / f"evidence/{args.evidence_prefix}-backend.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"PASS: {len(cases)} backend tests; 9 real HTTP captures exported for Kotlin verification.")


if __name__ == "__main__":
    main()
