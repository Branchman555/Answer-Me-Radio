"""Explicitly selected AVD only. Synthetic provider tests; no user music or physical devices."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--avd", required=True)
    parser.add_argument("--picker", action="store_true")
    args = parser.parse_args()
    assert args.serial.startswith("emulator-") and args.avd.startswith("ReplyRadio_RR003_"), "Dedicated test AVD required"
    adb = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"

    def call(*arguments, binary=False):
        result = subprocess.run([str(adb), "-s", args.serial, *arguments], capture_output=True, timeout=60)
        if result.returncode:
            raise RuntimeError(result.stderr.decode("utf-8", errors="replace"))
        return result.stdout if binary else result.stdout.decode("utf-8", errors="replace").strip()

    assert call("emu", "avd", "name").splitlines()[0].strip() == args.avd
    assert call("shell", "getprop", "sys.boot_completed") == "1"
    user = call("shell", "am", "get-current-user")
    apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    test_apk = ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    for artifact in (apk, test_apk):
        assert "Success" in call("install", "-r", str(artifact)), "Install failed"
    picker = call("shell", "cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.OPEN_DOCUMENT",
                  "-c", "android.intent.category.OPENABLE", "-t", "audio/mpeg")
    report = {"ticket": "RR-003", "recordedAtUtc": datetime.now(timezone.utc).isoformat(),
              "serial": args.serial, "avd": args.avd, "androidApi": call("shell", "getprop", "ro.build.version.sdk"),
              "fingerprint": call("shell", "getprop", "ro.build.fingerprint"), "user": user,
              "appSha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
              "testApkSha256": hashlib.sha256(test_apk.read_bytes()).hexdigest(),
              "fixture": "GENERATED_SILENT_MPEG_FRAMES_AND_SYNTHETIC_TAGS_TEST_APK_ONLY",
              "pickerResolver": picker, "picker": "NOT_RUN", "tests": [], "actualMusicPlayback": "NOT_RUN",
              "physicalUsbUnplug": "NOT_RUN", "vehicleDrivingUx": "NOT_RUN"}
    try:
        for stage in ("write", "restore", "picker"):
            if stage == "restore":
                call("shell", "am", "force-stop", "--user", user, "radio.reply.prototype")
                report["targetProcessForceStoppedBeforeRestore"] = True
            if stage == "picker" and not args.picker:
                if "DocumentsUIStub" in picker:
                    report["picker"] = "BLOCKED_PLATFORM_STUB"
                continue
            log = ROOT / "evidence" / f"rr003-{args.avd}-{stage}.log"
            try:
                output = call("shell", "am", "instrument", "--user", user, "-w", "-r", "-e", "stage", stage,
                              "radio.reply.prototype.test/radio.reply.catalog.CatalogInstrumentation")
            except subprocess.TimeoutExpired as failure:
                log.write_bytes(failure.stdout or b"")
                call("shell", "am", "force-stop", "--user", user, "radio.reply.prototype")
                raise
            log.write_text(output + "\n", encoding="utf-8")
            count = re.search(r"RR003_PASS_COUNT=(\d+)", output)
            assert count and "RR003_FAILED" not in output, f"Device test failed: {log.name}"
            names = re.findall(r"stream=PASS ([^\r\n]+)", output)
            assert len(names) == int(count.group(1))
            report["tests"].extend({"name": name, "stage": stage, "status": "PASS"} for name in names)
            if stage == "picker":
                report["picker"] = "PASS"
                png = call("exec-out", "run-as", "radio.reply.prototype", "--user", user, "cat", "no_backup/rr003-catalog.png", binary=True)
                assert png.startswith(b"\x89PNG"), "Catalog screenshot extraction did not return PNG"
                (ROOT / "evidence/rr003-catalog.png").write_bytes(png)
        report["status"] = "PASS_WITH_PLATFORM_LIMIT" if report["picker"] == "BLOCKED_PLATFORM_STUB" else "PASS"
    except Exception as failure:
        report["status"] = "FAIL"
        report["failure"] = str(failure)
        raise
    finally:
        (ROOT / "evidence" / f"rr003-device-{args.avd}.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"PASS: {len(report['tests'])} native checks on {args.avd}; picker={report['picker']}; real audio NOT_RUN")


if __name__ == "__main__":
    main()
