"""Verify preserved source hashes and collect executed JVM/build evidence (stdlib only)."""
import argparse
import csv
import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DESIGN = ROOT / "docs/design"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_sources():
    manifest = json.loads((DESIGN / "reply_radio_bundle_manifest_v0.8.json").read_text(encoding="utf-8"))
    for entry in manifest["files"]:
        path = (DESIGN / entry["path"]).resolve()
        assert path.is_relative_to(DESIGN.resolve()), "Outside design directory"
        assert path.stat().st_size == entry["bytes"], entry["path"]
        assert digest(path) == entry["sha256"], entry["path"]
    with (DESIGN / "reply_radio_acceptance_matrix_v0.7.csv").open(encoding="utf-8", newline="") as stream:
        baseline = list(csv.DictReader(stream))
    road = json.loads((DESIGN / "reply_radio_road_story_acceptance_v0.8.json").read_text(encoding="utf-8"))["tests"]
    assert len(baseline) == 54 and len(road) == 40
    assert all(case["status"] == "NOT_RUN" for case in baseline + road)
    external = ROOT.parent / "reply_radio_road_story_extension_v0.8.md"
    if external.exists():
        assert digest(external) == digest(DESIGN / external.name)
    baseline_hashes = json.loads((ROOT / "evidence/rr001-test-hashes.json").read_text(encoding="utf-8-sig"))
    for entry in baseline_hashes:
        assert digest(ROOT / entry["path"]) == entry["sha256"], f"RR-001 test modified: {entry['path']}"
    for entry in json.loads((ROOT / "evidence/rr002-test-hashes.json").read_text(encoding="utf-8-sig")):
        assert digest(ROOT / entry["path"]) == entry["sha256"], f"RR-002 test modified: {entry['path']}"
    for entry in json.loads((ROOT / "evidence/rr003-test-hashes.json").read_text(encoding="utf-8-sig")):
        assert digest(ROOT / entry["path"]) == entry["sha256"], f"RR-003 test modified: {entry['path']}"
    for entry in json.loads((ROOT / "evidence/rr004-test-hashes.json").read_text(encoding="utf-8-sig")):
        assert digest(ROOT / entry["path"]) == entry["sha256"], f"RR-004 test modified: {entry['path']}"
    for entry in json.loads((ROOT / "evidence/rr005-test-hashes.json").read_text(encoding="utf-8-sig")):
        assert digest(ROOT / entry["path"]) == entry["sha256"], f"RR-005 test modified: {entry['path']}"
    for baseline_name in ["rr006-test-hashes", "rr006-backend-test-hashes"]:
        for entry in json.loads((ROOT / f"evidence/{baseline_name}.json").read_text(encoding="utf-8")):
            assert digest(ROOT / entry["path"]) == entry["sha256"], f"RR-006 test modified: {entry['path']}"
    return {"status": "PASS", "manifestFilesVerified": len(manifest["files"]),
            "baselineAcceptanceCasesPreserved": 54, "roadAcceptanceCasesPreserved": 40,
            "externalExtensionMatches": external.exists()}


def report(source_result, log, require_devices=False):
    assert log and (ROOT / log).is_file(), "Build log is required"
    assert "BUILD SUCCESSFUL" in (ROOT / log).read_text(encoding="utf-8-sig"), "Successful build log missing"
    suites = []
    for path in sorted((ROOT / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml")):
        suite = ET.parse(path).getroot()
        assert int(suite.attrib["failures"]) == 0 and int(suite.attrib["errors"]) == 0, path.name
        tests = []
        for case in suite.findall("testcase"):
            assert case.find("skipped") is None
            linked = [f"{prefix.upper()}-{number}" for prefix, number in re.findall(r"(ac|rs)(\d{3})", case.attrib["name"])]
            tests.append({"name": case.attrib["name"], "status": "PASS", "relatedDesignCases": linked})
        suites.append({"name": suite.attrib["name"], "tests": tests})
    required_suites = {"radio.reply." + name for name in ["RadioPlannerTest", "RadioReducerTest", "RoadStoryTest",
                       "EventInterleavingTest", "ContextEngineTest", "DjContractTest", "ContextDjIntegrationTest", "LocalMusicCatalogTest", "PlaybackEngineTest", "VoiceOutputTest", "UxSafetyGateTest", "BackendContractTest"]}
    assert required_suites.issubset({suite["name"] for suite in suites}), "Required test suite missing"
    baseline = json.loads((ROOT / "evidence/rr001.json").read_text(encoding="utf-8"))
    passed = {(suite["name"], test["name"]) for suite in suites for test in suite["tests"]}
    prior_tests = {(suite["name"], test["name"]) for suite in baseline["jvm"]["suites"] for test in suite["tests"]}
    assert len(prior_tests) == 58 and prior_tests.issubset(passed), "An original RR-001 test was not executed"
    rr002 = json.loads((ROOT / "evidence/rr002.json").read_text(encoding="utf-8"))
    previous_tests = {(suite["name"], test["name"]) for suite in rr002["jvm"]["suites"] for test in suite["tests"]}
    assert len(previous_tests) == 103 and previous_tests.issubset(passed), "An original RR-002 test was not executed"
    rr003 = json.loads((ROOT / "evidence/rr003.json").read_text(encoding="utf-8"))
    catalog_tests = {(suite["name"], test["name"]) for suite in rr003["jvm"]["suites"] for test in suite["tests"]}
    assert len(catalog_tests) == 127 and catalog_tests.issubset(passed), "An original RR-003 test was not executed"
    rr004 = json.loads((ROOT / "evidence/rr004.json").read_text(encoding="utf-8"))
    playback_tests = {(suite["name"], test["name"]) for suite in rr004["jvm"]["suites"] for test in suite["tests"]}
    assert len(playback_tests) == 144 and playback_tests.issubset(passed), "An original RR-004 test was not executed"
    rr005 = json.loads((ROOT / "evidence/rr005.json").read_text(encoding="utf-8"))
    voice_tests = {(suite["name"], test["name"]) for suite in rr005["jvm"]["suites"] for test in suite["tests"]}
    assert len(voice_tests) == 174 and voice_tests.issubset(passed), "An original RR-005 test was not executed"
    rr006 = json.loads((ROOT / "evidence/rr006.json").read_text(encoding="utf-8"))
    api_tests = {(suite["name"], test["name"]) for suite in rr006["jvm"]["suites"] for test in suite["tests"]}
    assert len(api_tests) == 177 and api_tests.issubset(passed), "An RR-006 test was not executed"
    lint = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot()
    issues = lint.findall("issue")
    assert not any(issue.attrib["severity"] in {"Error", "Fatal"} for issue in issues)
    apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    assert apk.is_file()
    test_apk = ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    assert test_apk.is_file()
    with zipfile.ZipFile(apk) as archive:
        assert not any(name.lower().endswith((".mp3", ".wav", ".m4a")) for name in archive.namelist()), "Audio packaged in main APK"
        for name in archive.namelist():
            if name.endswith(".dex"):
                assert b"FixtureDocumentsProvider" not in archive.read(name) and b"FixtureGrantActivity" not in archive.read(name), "Test fixture leaked into main APK"
    manifest = ET.parse(ROOT / "app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml").getroot()
    permissions = {p.attrib["{http://schemas.android.com/apk/res/android}name"] for p in manifest.findall("uses-permission")}
    assert permissions == {"android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK", "android.permission.WAKE_LOCK",
                           "pleos.car.permission.TTS_SERVICE", "radio.reply.prototype.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"}, permissions
    font_manifest = json.loads((ROOT / "docs/pretendard-provenance.json").read_text(encoding="utf-8"))
    for path, expected_hash in font_manifest["files"].items():
        assert digest(ROOT / path) == expected_hash, f"Bundled font/license changed: {path}"
    with zipfile.ZipFile(apk) as archive:
        assert archive.read("assets/licenses/Pretendard-OFL.txt") == (ROOT / "app/src/main/assets/licenses/Pretendard-OFL.txt").read_bytes()
    ui_controls = []
    for path in sorted((ROOT / "evidence").glob("ui002-controls-*.json")):
        controls = json.loads(path.read_text(encoding="utf-8"))
        controls["artifactMatchesCurrentBuild"] = controls["appSha256"] == digest(apk) and controls["testApkSha256"] == digest(test_apk)
        ui_controls.append(controls)
    if require_devices:
        assert {item["avd"] for item in ui_controls} == {"ReplyRadio_RR003_API34", "ReplyRadio_RR003_API37"}
        assert all(item["status"] == "PASS" and item["testCount"] == 7 and item["artifactMatchesCurrentBuild"] for item in ui_controls)
    devices = []
    for path in sorted((ROOT / "evidence").glob("ui002-device-*.json")):
        device = json.loads(path.read_text(encoding="utf-8"))
        device["artifactMatchesCurrentBuild"] = device["appSha256"] == digest(apk) and device["testApkSha256"] == digest(test_apk)
        devices.append(device)
    if require_devices:
        expected = {"ReplyRadio_RR003_API34": 38, "ReplyRadio_RR003_API37": 38}
        for avd, count in expected.items():
            device = next(item for item in devices if item["avd"] == avd)
            assert device["artifactMatchesCurrentBuild"], f"Stale device APK evidence: {avd}"
            assert device["status"] in {"PASS", "PASS_WITH_PLATFORM_LIMIT"}, f"Device check failed: {avd}"
            assert len(device["tests"]) == count and all(test["status"] == "PASS" for test in device["tests"])
            names = {test["name"] for test in device["tests"]}
            previous_device = json.loads((ROOT / "evidence" / f"rr004-device-{avd}.json").read_text(encoding="utf-8"))
            assert {test["name"] for test in previous_device["tests"]}.issubset(names), "Native regression case missing"
            assert {"actual_voice_done_then_media3_audio_without_overlap", "actual_voice_pause_stop_ack_and_no_auto_resume",
                    "actual_voice_resume_then_next_uses_introduced_track", "actual_voice_stop_never_starts_next_music",
                    "actual_focus_loss_stops_voice_gain_restarts_serially", "user_pause_during_voice_focus_loss_blocks_gain_resume",
                    "three_natural_music_completions_trigger_serial_era_bridge", "dj_off_makes_no_additional_voice_request"}.issubset(names)
            assert device["audio"]["audioTrackAdvances"] > 0 and device["audio"]["renderedDecoderBuffers"] > 0
            assert device["voice"]["done"] > 0 and device["voice"]["stopAcks"] > 0 and device["voice"]["stopTimeouts"] == 0
            if "API37" in avd: assert device["voice"]["audioBytes"] > 0
            if "API34" in avd:
                assert device["vehicleDrivingUx"] == "EMULATED_PLATFORM_RESTRICTION_ON_OFF"
                assert "pleos_driving_restriction_locks_details_and_service_settings" in names
    backend = json.loads((ROOT / "evidence/ui002-backend.json").read_text(encoding="utf-8"))
    assert backend["status"] == "PASS"
    prior_backend = json.loads((ROOT / "evidence/rr006-backend.json").read_text(encoding="utf-8"))
    assert len(prior_backend["tests"]) == 115
    assert {t["name"] for t in prior_backend["tests"]}.issubset({t["name"] for t in backend["tests"]})
    for path, expected_hash in backend["sourceHashes"].items():
        assert digest(ROOT / path) == expected_hash, f"Stale backend evidence: {path}"
    value = {"ticket": "UI-002", "recordedAtUtc": datetime.now(timezone.utc).isoformat(),
             "scope": "JVM_AND_ANDROID_BUILD_WITH_SEPARATE_NATIVE_DEVICE_EVIDENCE", "sourceIntegrity": source_result,
             "jvm": {"status": "PASS", "testCount": sum(len(s["tests"]) for s in suites), "suites": suites},
             "rr001Regression": {"status": "PASS", "testsPreservedAndExecuted": len(prior_tests), "sourceHashesUnchanged": True},
             "rr002Regression": {"status": "PASS", "testsPreservedAndExecuted": len(previous_tests), "sourceHashesUnchanged": True},
             "rr003Regression": {"status": "PASS", "testsPreservedAndExecuted": len(catalog_tests), "sourceHashesUnchanged": True},
             "rr004Regression": {"status": "PASS", "testsPreservedAndExecuted": len(playback_tests), "sourceHashesUnchanged": True},
             "rr005Regression": {"status": "PASS", "testsPreservedAndExecuted": len(voice_tests), "sourceHashesUnchanged": True},
             "backend": {"status": "PASS", "testCount": backend["testCount"], "evidence": "evidence/ui002-backend.json", "liveHttpCases": 9},
             "rr006Regression": {"status": "PASS", "jvmPreserved": 177, "backendPreserved": 115},
             "contractVerification": "Original v0.5/v0.6 preserved; 9 Uvicorn HTTP captures checked by existing Kotlin decoder/editorial validator",
             "debugApk": {"status": "PASS", "path": apk.relative_to(ROOT).as_posix(), "sha256": digest(apk)},
             "testApk": {"path": test_apk.relative_to(ROOT).as_posix(), "sha256": digest(test_apk)},
             "catalogArtifactIsolation": "PASS_NO_AUDIO_OR_TEST_PROVIDER_IN_MAIN_APK_NO_BROAD_PERMISSIONS",
             "lint": {"status": "PASS", "errors": 0, "warnings": len(issues)},
             "buildLog": log, "deviceEvidence": devices, "uiControls": ui_controls, "bundledFontLicense": "OFL_1_1_UNMODIFIED_PRETENDARD_V1_3_9", "realAudioPlayback": "SEE_DEVICE_MP3_AND_PLATFORM_TTS_EVIDENCE", "vehicle": "EMULATOR_ONLY_SEE_DEVICE_EVIDENCE",
             "acceptanceMatricesPromoted": False,
             "limitations": ["Pleos ON_DEVICE and Android offline TTS; physical speaker listening not tested", "Pleos API34 picker resolves to platform stub; picker roundtrip only on regular Android",
                             "Physical USB removal, actual vehicle driving and physical media buttons not tested", "RR-006 backend is localhost Mock with approved templates; no external model/deployment/Android HTTP connection",
                             "Actual road geometry/editorial/music rights unverified", "History is process-local; no replay or 7-day persistence",
                             "Empty-cache build and supplier signature verification not performed"]}
    (ROOT / "evidence/latest.json").write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (ROOT / "evidence/ui002.json").write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"PASS: {value['jvm']['testCount']} JVM tests (177 preserved); backend {backend['testCount']}; APK + test APK built; lint {len(issues)} warnings. Native evidence recorded under UI-002.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", action="store_true")
    parser.add_argument("--log")
    parser.add_argument("--require-devices", action="store_true")
    args = parser.parse_args()
    verified = verify_sources()
    if args.report:
        report(verified, args.log, args.require_devices)
    else:
        print(f"PASS: {verified['manifestFilesVerified']} source hashes; 54+40 acceptance cases preserved.")
