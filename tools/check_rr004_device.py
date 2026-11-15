"""Run only against named ReplyRadio test AVDs; synthesized MP3s, never user music."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--avd", required=True)
    parser.add_argument("--picker", action="store_true")
    parser.add_argument("--playback-only", action="store_true")
    args = parser.parse_args()
    assert args.serial.startswith("emulator-") and args.avd.startswith("ReplyRadio_RR003_")
    adb = str(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe")
    def call(*cmd):
        result = subprocess.run([adb, "-s", args.serial, *cmd], capture_output=True, timeout=60)
        assert result.returncode == 0, result.stderr.decode(errors="replace")
        return result.stdout.decode("utf-8", errors="replace").strip()
    assert call("emu", "avd", "name").splitlines()[0].strip() == args.avd
    assert call("shell", "getprop", "sys.boot_completed") == "1"
    user = call("shell", "am", "get-current-user")
    apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    tests = ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    for path in (apk, tests): assert "Success" in call("install", "-r", str(path))
    report = {"ticket": "RR-004", "recordedAtUtc": datetime.now(timezone.utc).isoformat(), "avd": args.avd,
              "serial": args.serial, "user": user, "androidApi": call("shell", "getprop", "ro.build.version.sdk"),
              "fingerprint": call("shell", "getprop", "ro.build.fingerprint"),
              "appSha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "testApkSha256": hashlib.sha256(tests.read_bytes()).hexdigest(),
              "audioFixture": "ORIGINAL_SYNTHESIZED_MP3_TEST_APK_ONLY", "tests": [], "status": "RUNNING",
              "physicalSpeakerListening": "NOT_RUN_EMULATOR_NO_AUDIO", "physicalUsbUnplug": "NOT_RUN", "vehicleDrivingUx": "NOT_RUN"}
    stages = ["playback"] if args.playback_only else ["write", "restore"] + (["picker"] if args.picker else []) + ["playback"]
    try:
        for stage in stages:
            call("shell", "am", "force-stop", "--user", user, "radio.reply.prototype")
            log = ROOT / "evidence" / f"rr004-{args.avd}-{stage}.log"
            with log.open("wb") as output:
                process = subprocess.Popen([adb, "-s", args.serial, "shell", "am", "instrument", "--user", user, "-w", "-r", "-e", "stage", stage,
                    "radio.reply.prototype.test/radio.reply.catalog.CatalogInstrumentation"], stdout=output, stderr=subprocess.STDOUT)
                try:
                    for _ in range(4):
                        try: process.wait(timeout=45); break
                        except subprocess.TimeoutExpired: continue
                    else: raise TimeoutError(f"Instrumentation timeout: {stage}")
                finally:
                    if process.poll() is None: process.kill(); call("shell", "am", "force-stop", "--user", user, "radio.reply.prototype")
            text = log.read_text(encoding="utf-8")
            names = re.findall(r"stream=PASS ([^\r\n]+)", text)
            report["tests"].extend({"name": name, "stage": stage, "status": "PASS"} for name in names)
            expected = {"write": 7, "restore": 5, "picker": 1, "playback": 14}[stage]
            assert f"RR003_PASS_COUNT={expected} STAGE={stage}" in text and "RR003_FAILED" not in text, f"Native failure: {log.name}"
            if stage == "playback":
                metrics = re.search(r"RR004_AUDIO advances=(\d+) renderedBuffers=(\d+) errors=(\d+)", text)
                assert metrics and all(int(value) > 0 for value in metrics.groups())
                report["audio"] = dict(zip(("audioTrackAdvances", "renderedDecoderBuffers", "verifiedDecoderOrIoErrors"), map(int, metrics.groups())))
                screenshot = subprocess.check_output([adb, "-s", args.serial, "exec-out", "run-as", "radio.reply.prototype", "--user", user,
                    "cat", "no_backup/rr004-playback.png"], timeout=15)
                assert screenshot.startswith(b"\x89PNG")
                (ROOT / "evidence" / f"rr004-{args.avd}.png").write_bytes(screenshot)
            print(f"PASS {args.avd}: {stage} {expected}", flush=True)
        report["status"] = "PASS"
    except Exception as failure:
        report["status"] = "FAIL"; report["failure"] = str(failure)
        raise
    finally:
        (ROOT / "evidence" / f"rr004-device-{args.avd}.json").write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__": main()
