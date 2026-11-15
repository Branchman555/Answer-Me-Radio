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
    parser.add_argument("--voice-only", action="store_true")
    parser.add_argument("--evidence-prefix", choices=["rr005", "ui001"], default="rr005")
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
    report = {"ticket": "UI-001" if args.evidence_prefix == "ui001" else "RR-005", "recordedAtUtc": datetime.now(timezone.utc).isoformat(), "avd": args.avd,
              "serial": args.serial, "user": user, "androidApi": call("shell", "getprop", "ro.build.version.sdk"),
              "fingerprint": call("shell", "getprop", "ro.build.fingerprint"),
              "appSha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "testApkSha256": hashlib.sha256(tests.read_bytes()).hexdigest(),
              "audioFixture": "ORIGINAL_SYNTHESIZED_MP3_TEST_APK_ONLY", "tests": [], "status": "RUNNING",
              "physicalSpeakerListening": "NOT_RUN_EMULATOR_NO_AUDIO", "physicalUsbUnplug": "NOT_RUN", "vehicleDrivingUx": "NOT_RUN"}
    stages = ["voiceux"] if args.voice_only else ["write", "restore"] + (["picker"] if args.picker else []) + ["playback", "voiceux"]
    try:
        for stage in stages:
            call("shell", "am", "force-stop", "--user", user, "radio.reply.prototype")
            log = ROOT / "evidence" / f"{args.evidence_prefix}-{args.avd}-{stage}.log"
            with log.open("wb") as output:
                process = subprocess.Popen([adb, "-s", args.serial, "shell", "am", "instrument", "--user", user, "-w", "-r", "-e", "stage", stage,
                    "radio.reply.prototype.test/radio.reply.catalog.CatalogInstrumentation"], stdout=output, stderr=subprocess.STDOUT)
                try:
                    for _ in range(8):
                        try: process.wait(timeout=45); break
                        except subprocess.TimeoutExpired: continue
                    else: raise TimeoutError(f"Instrumentation timeout: {stage}")
                finally:
                    if process.poll() is None: process.kill(); call("shell", "am", "force-stop", "--user", user, "radio.reply.prototype")
            text = log.read_text(encoding="utf-8")
            names = re.findall(r"stream=PASS ([^\r\n]+)", text)
            report["tests"].extend({"name": name, "stage": stage, "status": "PASS"} for name in names)
            expected = {"write": 7, "restore": 5, "picker": 1, "playback": 14, "voiceux": 12 if "API34" in args.avd else 11}[stage]
            assert f"RR003_PASS_COUNT={expected} STAGE={stage}" in text and "RR003_FAILED" not in text, f"Native failure: {log.name}"
            if stage == "playback":
                metrics = re.search(r"RR004_AUDIO advances=(\d+) renderedBuffers=(\d+) errors=(\d+)", text)
                assert metrics and all(int(value) > 0 for value in metrics.groups())
                report["audio"] = dict(zip(("audioTrackAdvances", "renderedDecoderBuffers", "verifiedDecoderOrIoErrors"), map(int, metrics.groups())))
                screenshot = subprocess.check_output([adb, "-s", args.serial, "exec-out", "run-as", "radio.reply.prototype", "--user", user,
                    "cat", "no_backup/rr004-playback.png"], timeout=15)
                assert screenshot.startswith(b"\x89PNG")
                (ROOT / "evidence" / f"{args.evidence_prefix}-{args.avd}.png").write_bytes(screenshot)
            if stage == "voiceux":
                required_voice_checks = {"actual_voice_done_then_media3_audio_without_overlap", "actual_voice_pause_stop_ack_and_no_auto_resume",
                    "actual_voice_resume_then_next_uses_introduced_track", "actual_voice_stop_never_starts_next_music",
                    "actual_focus_loss_stops_voice_gain_restarts_serially", "user_pause_during_voice_focus_loss_blocks_gain_resume",
                    "three_natural_music_completions_trigger_serial_era_bridge", "dj_off_makes_no_additional_voice_request"}
                if "API34" in args.avd: required_voice_checks.add("pleos_driving_restriction_locks_details_and_service_settings")
                assert required_voice_checks.issubset(names), "Required native voice/UX case missing"
                metrics = re.search(r"RR005_VOICE available=true starts=(\d+) done=(\d+) stopAcks=(\d+) errors=(\d+) timeouts=(\d+) audioBytes=(\d+) rmsUpdates=(\d+)", text)
                assert metrics, "Actual voice unavailable; do not promote fallback to speech success"
                report["voice"] = dict(zip(("starts", "done", "stopAcks", "errors", "stopTimeouts", "audioBytes", "rmsUpdates"), map(int, metrics.groups())))
                assert report["voice"]["done"] > 0 and report["voice"]["stopAcks"] > 0 and report["voice"]["stopTimeouts"] == 0
                report["ux"] = re.search(r"RR005_UX=(\w+)", text).group(1)
                report["voiceBackend"] = re.search(r"RR005_BACKEND=(\w+)", text).group(1)
                report["vehicleDrivingUx"] = "EMULATED_PLATFORM_RESTRICTION_ON_OFF" if "API34" in args.avd else "NON_VEHICLE"
                for image in (["speaking", "restricted"] if "API34" in args.avd else ["speaking"]):
                    screenshot = subprocess.check_output([adb, "-s", args.serial, "exec-out", "run-as", "radio.reply.prototype", "--user", user,
                        "cat", f"no_backup/rr005-{image}.png"], timeout=15)
                    assert screenshot.startswith(b"\x89PNG")
                    (ROOT / "evidence" / f"{args.evidence_prefix}-{args.avd}-{image}.png").write_bytes(screenshot)
            print(f"PASS {args.avd}: {stage} {expected}", flush=True)
        report["status"] = "PASS"
    except Exception as failure:
        report["status"] = "FAIL"; report["failure"] = str(failure)
        raise
    finally:
        (ROOT / "evidence" / f"{args.evidence_prefix}-device-{args.avd}.json").write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__": main()
