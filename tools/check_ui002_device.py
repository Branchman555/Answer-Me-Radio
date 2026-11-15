"""UI-002: real transport button clicks on disposable, named ReplyRadio AVDs."""
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
    parser.add_argument('--serial', required=True)
    args = parser.parse_args()
    assert args.serial in {'emulator-5584', 'emulator-5586'}
    adb = str(Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk/platform-tools/adb.exe')
    def call(*cmd):
        return subprocess.check_output([adb, '-s', args.serial, *cmd], timeout=60).decode('utf-8', errors='replace').strip()
    avd = call('emu', 'avd', 'name').splitlines()[0]
    assert avd in {'ReplyRadio_RR003_API34', 'ReplyRadio_RR003_API37'}
    user = call('shell', 'am', 'get-current-user')
    apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    tests = ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    for path in (apk, tests): assert 'Success' in call('install', '-r', str(path))
    call('shell', 'am', 'force-stop', '--user', user, 'radio.reply.prototype')
    result = subprocess.run([adb, '-s', args.serial, 'shell', 'am', 'instrument', '--user', user, '-w', '-r', '-e', 'stage', 'ui002',
                            'radio.reply.prototype.test/radio.reply.catalog.CatalogInstrumentation'], capture_output=True, timeout=180)
    log = ROOT / f'evidence/ui002-controls-{avd}.log'
    log.write_bytes(result.stdout + result.stderr)
    text = result.stdout.decode('utf-8', errors='replace')
    names = re.findall(r'stream=PASS ([^\r\n]+)', text)
    success = result.returncode == 0 and 'UI002_PASS_COUNT=7' in text and 'UI002_FAILED' not in text and len(names) == 7
    report = {'ticket': 'UI-002', 'recordedAtUtc': datetime.now(timezone.utc).isoformat(), 'avd': avd,
              'status': 'PASS' if success else 'FAIL', 'testCount': len(names), 'tests': names,
              'appSha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'testApkSha256': hashlib.sha256(tests.read_bytes()).hexdigest(),
              'log': log.relative_to(ROOT).as_posix(), 'scope': 'EMPTY_STATE_AND_ACTUAL_TRANSPORT_BUTTONS_EMULATOR_ONLY'}
    (ROOT / f'evidence/ui002-controls-{avd}.json').write_text(json.dumps(report, indent=2)+'\n')
    assert success, text
    screenshot = subprocess.check_output([adb, '-s', args.serial, 'exec-out', 'run-as', 'radio.reply.prototype', '--user', user,
                                          'cat', 'no_backup/ui002-empty.png'], timeout=15)
    assert screenshot.startswith(b'\x89PNG')
    (ROOT / f'evidence/ui002-empty-{avd}.png').write_bytes(screenshot)
    print(f'PASS {avd}: actual UI controls 7/7')

if __name__ == '__main__': main()
