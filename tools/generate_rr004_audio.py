"""Original synthesized test music only. Generated MP3 assets stay under ignored build/ and test APK."""
from pathlib import Path
import array
import hashlib
import json
import math
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "app/build/rr004-tools"))
import lameenc


def tag(key, value):
    data = b"\x01" + value.encode("utf-16")
    return key.encode("ascii") + len(data).to_bytes(4, "big") + b"\0\0" + data


def main():
    output = ROOT / "app/build/generated/rr004-assets"
    output.mkdir(parents=True, exist_ok=True)
    rate = 44100
    seconds = 12
    # Original arpeggio sequence, soft envelope, no sampled or downloaded recordings.
    notes = [261.63, 329.63, 392.0, 523.25, 440.0, 349.23, 293.66, 392.0]
    pcm = array.array("h")
    for i in range(rate * seconds):
        t = i / rate
        phase = t % .5
        envelope = min(phase / .025, 1) * min((.5 - phase) / .08, 1)
        freq = notes[int(t * 2) % len(notes)]
        pcm.append(int(6000 * envelope * (math.sin(2 * math.pi * freq * t) + .25 * math.sin(4 * math.pi * freq * t))))
    encoder = lameenc.Encoder()
    encoder.set_bit_rate(128); encoder.set_in_sample_rate(rate); encoder.set_channels(1); encoder.set_quality(2)
    encoded = encoder.encode(pcm.tobytes()) + encoder.flush()
    hashes = {}
    for n in range(1, 7):
        tags = tag("TIT2", f"RR004 original melody {n}") + tag("TPE1", "Reply Radio synthetic fixture") + tag("TYER", "1988" if n % 2 else "2014")
        header = b"ID3\x03\0\0" + bytes((len(tags) >> shift) & 127 for shift in (21, 14, 7, 0))
        path = output / f"song{n}.mp3"
        path.write_bytes(header + tags + encoded)
        hashes[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
    report = {"source": "ORIGINAL_SYNTHESIZED_ARPEGGIO_NO_USER_MUSIC", "encoder": "lameenc 1.8.5",
              "sampleRate": rate, "durationSeconds": seconds, "nonzeroPcmSamples": sum(x != 0 for x in pcm), "testAssetsSha256": hashes}
    (ROOT / "evidence/rr004-audio-fixture.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Generated 6 tagged MP3 test fixtures; nonzero PCM; no main APK audio")


if __name__ == "__main__": main()
