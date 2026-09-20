#!/usr/bin/env python3
"""Download the touch sounds listed in scripts/sounds.json and turn them into the files the Android app plays.

Every source is a CC0 (public domain) recording from BigSoundBank. For each texture this script:

  * slide and ambient sounds: picks the steadiest stretch of the recording and joins its end to its start
    with a short crossfade, so the loop repeats without a click or a change in level;
  * tap sounds: finds the individual hits in the recording and keeps a few, so repeated taps are not identical;
  * removes rumble, evens out loudness, and writes Ogg Vorbis files to app/src/main/assets/sounds;
  * writes docs/sounds.md and the in-app Sound-credits.txt with where each sound came from.

Downloads are cached in ~/.cache/mural-sounds, so a second run needs no network.

    python3 scripts/build-sounds.py
"""
import json
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import wave
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "scripts/sounds.json"
OUT = ROOT / "apps/android/app/src/main/assets/sounds"
DOC = ROOT / "docs/sounds.md"
NOTICE = ROOT / "apps/android/app/src/main/assets/Sound-credits.txt"
CACHE = Path.home() / ".cache/mural-sounds"
SR = 44100
UA = {"User-Agent": "Mozilla/5.0"}

# Seconds of audio in a loop, and how much of its end is blended into its start.
# Kept short on purpose: Android's SoundPool holds every sound decoded in memory and is meant for clips under about
# 1 MB decoded, so an ambient loop is 7 seconds of 32 kHz stereo (about 0.9 MB) and a slide loop 6 seconds of mono.
LOOP = {"slide": (6.0, 0.5), "ambient": (7.0, 1.0)}
OUTPUT_RATE = {"ambient": 32000}
# Target average level (dBFS) for loops, and the peak level (dBFS) for taps.
LOOP_RMS_DB = {"slide": -20.0, "ambient": -24.0}
TAP_PEAK_DB = -3.0
TAP_LENGTH = 0.5
TAP_PRE_ROLL = 0.008


rng = np.random.default_rng(20260920)


def band_noise(seconds, lo, hi, slope=0.0):
    """Periodic noise with energy between lo and hi Hz; slope>0 tilts it toward low frequencies."""
    n = int(SR * seconds)
    freqs = np.fft.rfftfreq(n, 1 / SR)
    mag = np.zeros_like(freqs)
    band = (freqs >= lo) & (freqs <= hi)
    mag[band] = 1.0 / np.maximum(freqs[band], 1.0) ** slope
    phase = rng.uniform(0, 2 * np.pi, len(freqs))
    return np.fft.irfft(mag * np.exp(1j * phase), n)


def periodic_mod(seconds, cycles, depth, phase=0.0):
    """Amplitude modulation with a whole number of cycles, so it wraps around cleanly."""
    t = np.arange(int(SR * seconds)) / (SR * seconds)
    return 1.0 - depth + depth * 0.5 * (1 + np.sin(2 * np.pi * cycles * t + phase))


def bursts(seconds, per_second, decay_ms, lo, hi, amp_spread=1.0):
    """Randomly placed short filtered bursts, wrapped around the loop."""
    n = int(SR * seconds)
    out = np.zeros(n)
    length = int(SR * decay_ms * 6 / 1000)
    env = np.exp(-np.arange(length) / (SR * decay_ms / 1000))
    for _ in range(int(per_second * seconds)):
        start = rng.integers(0, n)
        tone = band_noise(length / SR, lo, hi)[:length] if length > 64 else rng.standard_normal(length)
        size = min(len(tone), length)  # the noise can come out a sample short after rounding
        burst = tone[:size] / (np.max(np.abs(tone[:size])) + 1e-9) * env[:size] * rng.uniform(1 - amp_spread, 1)
        out[(start + np.arange(size)) % n] += burst
    return out


def normalise(x, peak=0.8):
    return x / (np.max(np.abs(x)) + 1e-9) * peak


def download(sound_id):
    """Return a local file for a BigSoundBank sound, preferring lossless."""
    CACHE.mkdir(parents=True, exist_ok=True)
    for kind in ("flac", "ogg", "mp3"):
        target = CACHE / f"{sound_id}.{kind}"
        if target.exists() and target.stat().st_size > 0:
            return target
    for kind in ("flac", "ogg", "mp3"):
        request = urllib.request.Request(f"https://bigsoundbank.com/UPLOAD/{kind}/{sound_id}.{kind}", headers=UA)
        try:
            data = urllib.request.urlopen(request, timeout=60).read()
        except urllib.error.HTTPError:
            continue
        if data[:4] in (b"fLaC", b"OggS") or data[:3] == b"ID3" or data[:2] == b"\xff\xfb":
            target = CACHE / f"{sound_id}.{kind}"
            target.write_bytes(data)
            return target
    raise SystemExit(f"Could not download sound {sound_id}")


def decode(path, channels):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", str(path), "-f", "f32le", "-acodec", "pcm_f32le",
                          "-ac", str(channels), "-ar", str(SR), "-"], capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype="<f4").reshape(-1, channels).astype(np.float64)


def remove_rumble(x, cutoff=40.0):
    spectrum = np.fft.rfft(x, axis=0)
    freqs = np.fft.rfftfreq(len(x), 1 / SR)
    spectrum[freqs < cutoff] = 0
    return np.fft.irfft(spectrum, len(x), axis=0)


def frame_rms(x, frame=0.05):
    size = int(SR * frame)
    mono = x.mean(axis=1)
    count = len(mono) // size
    return np.sqrt((mono[: count * size].reshape(count, size) ** 2).mean(axis=1) + 1e-12)


def steadiest_window(x, length, fade):
    """Start of the stretch that is loud enough and changes least in level, so a loop of it sounds continuous."""
    total = length + fade
    frames = frame_rms(x)
    per_second = int(1 / 0.05)
    quiet = np.percentile(frames, 90) * 0.5
    best, best_score = 0, None
    step = int(0.25 * SR)
    for start in range(0, max(1, len(x) - int(total * SR)), step):
        window = frames[start // int(SR * 0.05): (start + int(total * SR)) // int(SR * 0.05)]
        if len(window) < per_second or window.mean() < quiet:
            continue
        score = window.std() / window.mean()
        if best_score is None or score < best_score:
            best, best_score = start, score
    return best


def make_loop(x, kind):
    length, fade = LOOP[kind]
    if len(x) < int((length + fade) * SR):
        # Short recording: use most of it as the loop and the rest as the crossfade.
        length_samples = int(len(x) * 0.75)
        fade_samples = min(int(fade * SR), len(x) - length_samples)
        start = 0
    else:
        length_samples, fade_samples = int(length * SR), int(fade * SR)
        start = steadiest_window(x, length, fade)
    segment = x[start: start + length_samples + fade_samples]
    loop = segment[:length_samples].copy()
    ramp = np.linspace(0, np.pi / 2, fade_samples)[:, None]
    # The stretch after the loop is blended over its start, so the end runs straight into the beginning.
    loop[:fade_samples] = segment[length_samples: length_samples + fade_samples] * np.cos(ramp) + segment[:fade_samples] * np.sin(ramp)
    return loop


def normalise_loop(loop, kind):
    rms = np.sqrt((loop ** 2).mean())
    loop = loop * (10 ** (LOOP_RMS_DB[kind] / 20) / (rms + 1e-12))
    peak = np.abs(loop).max()
    return loop * (0.89 / peak) if peak > 0.89 else loop


def find_hits(x, wanted):
    """Start positions of the most distinct hits in a recording of taps."""
    mono = np.abs(x.mean(axis=1))
    window = int(SR * 0.003)
    envelope = np.convolve(mono, np.ones(window) / window, mode="same")
    peak = envelope.max()
    refractory = int(0.25 * SR)
    hits = []
    for threshold in (0.35, 0.2, 0.1):
        hits, position = [], 0
        while position < len(envelope):
            above = np.nonzero(envelope[position:] > threshold * peak)[0]
            if len(above) == 0:
                break
            start = position + above[0]
            # A hit begins where the sound first rises; climb to the true peak within a few milliseconds.
            top = start + int(np.argmax(envelope[start: start + int(0.02 * SR)]))
            hits.append((top, envelope[top]))
            position = start + refractory
        if len(hits) >= wanted:
            break
    strongest = sorted(hits, key=lambda hit: -hit[1])[:wanted]
    return sorted(position for position, _ in strongest)


def cut_hit(x, top):
    start = max(0, top - int(TAP_PRE_ROLL * SR))
    hit = x[start: start + int(TAP_LENGTH * SR)].copy()
    fade = min(len(hit), int(0.02 * SR))
    hit[-fade:] *= np.linspace(1, 0, fade)[:, None]
    return hit * (10 ** (TAP_PEAK_DB / 20) / (np.abs(hit).max() + 1e-12))


def encode(x, path, rate=None):
    channels = x.shape[1]
    with tempfile.TemporaryDirectory() as tmp:
        wav = Path(tmp) / "in.wav"
        with wave.open(str(wav), "wb") as handle:
            handle.setnchannels(channels)
            handle.setsampwidth(2)
            handle.setframerate(SR)
            handle.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())
        subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", str(wav), *(["-ar", str(rate)] if rate else []), "-c:a", "libvorbis", "-q:a", "4", str(path)], check=True)


def seam_ratio(path):
    """Jump at the loop point relative to the typical step between samples; about 1 or less means no click."""
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", str(path), "-f", "s16le", "-ac", "1", "-ar", str(SR), "-"],
                         capture_output=True, check=True).stdout
    y = np.frombuffer(raw, dtype="<i2").astype(float)
    return abs(y[0] - y[-1]) / (np.mean(np.abs(np.diff(y))) + 1e-9)


def synth_squish(index):
    """A wet, bubbly squelch: a short rising bloop, a splash of filtered noise and a soft thud, sometimes doubled."""
    total = int(SR * 0.5)
    out = np.zeros(total)
    for start, level in ((0.0, 1.0), (rng.uniform(0.06, 0.14), rng.uniform(0.3, 0.6)) if index % 2 else (None, 0)):
        if start is None:
            continue
        n = int(SR * 0.32)
        t = np.arange(n) / SR
        f0, f1 = rng.uniform(130, 230), rng.uniform(420, 760)
        freq = f0 * (f1 / f0) ** np.minimum(t / 0.14, 1.0)
        tone = np.sin(2 * np.pi * np.cumsum(freq) / SR) * np.exp(-t / 0.09) * np.minimum(t / 0.004, 1.0)
        splash = band_noise(n / SR, 700, 3600)[:n] * np.exp(-t / 0.035) * 0.5
        thud = np.sin(2 * np.pi * 85 * t) * np.exp(-t / 0.05) * 0.6
        piece = (tone + splash + thud) * level
        offset = int(start * SR)
        out[offset: offset + n] += piece[: total - offset]
    fade = int(SR * 0.02)
    out[-fade:] *= np.linspace(1, 0, fade)
    return normalise(out, 0.85)


def synth_stretch():
    """A creaky, wet stretch: soft filtered noise that swells and slackens, with small crackles in it."""
    seconds = 6
    body = band_noise(seconds, 250, 2400, 0.6) * periodic_mod(seconds, 11, 0.5) * periodic_mod(seconds, 17, 0.35, 1.3)
    crackle = bursts(seconds, 16, 6, 600, 3800, 0.9) * 0.5
    return normalise(body / (np.abs(body).max() + 1e-9) + crackle)


def build(name, texture):
    kind = texture["kind"]
    made = []
    if kind == "synth":
        if texture["recipe"] == "squish":
            made = [synth_squish(i)[:, None] for i in range(texture["variants"])]
        else:
            made = [normalise_loop(make_loop(synth_stretch()[:, None], "slide"), "slide")]
    elif kind == "tap":
        for source in texture["sources"]:
            x = remove_rumble(decode(download(source["id"]), 1))
            for top in find_hits(x, source.get("hits", 1)):
                made.append(cut_hit(x, top))
    else:
        # Ambient recordings keep their stereo image; everything else is mono so that panning is exact.
        channels = 2 if kind == "ambient" else 1
        x = remove_rumble(decode(download(texture["sources"][0]["id"]), channels))
        made.append(normalise_loop(make_loop(x, kind), kind))
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob(f"{name}.ogg"): old.unlink()
    for old in OUT.glob(f"{name}_[0-9]*.ogg"): old.unlink()
    files = []
    for index, audio in enumerate(made, start=1):
        path = OUT / (f"{name}.ogg" if len(made) == 1 else f"{name}_{index}.ogg")
        encode(audio, path, OUTPUT_RATE.get(kind))
        files.append(path)
    return files


def write_credits(manifest):
    source = manifest["source"]
    lines = [
        "# Sound credits", "",
        f"Every touch sound comes from [{source['name']}]({source['url']}) and is released under "
        f"[{source['license']}]({source['licenseUrl']}). Nothing is required in return; the site's author suggests "
        f"crediting \"{source['credit']}\" and the app does. The recordings are trimmed, looped, filtered and "
        "levelled by `scripts/build-sounds.py`.", "",
        "| Sound | Kind | Source recordings |", "|---|---|---|",
    ]
    for name, texture in manifest["textures"].items():
        if texture["kind"] == "synth":
            lines.append(f"| `{name}` | generated | Made by the script, no recording |")
            continue
        recordings = ", ".join(f"[{s['title']}]({source['url']}/s{s['id']}.html)" for s in texture["sources"])
        lines.append(f"| `{name}` | {texture['kind']} | {recordings} |")
    DOC.write_text("\n".join(lines) + "\n")
    notice = [
        f"Touch sounds: recordings from {source['name']} ({source['url']}), released under {source['license']}.",
        f"{source['credit']}",
        "The recordings were trimmed, looped, filtered and levelled for this app.",
        "Recordings used: " + "; ".join(f"{s['title']} (s{s['id']})" for t in manifest["textures"].values() for s in t["sources"]) + ".",
        "The slime sounds were generated for this app.",
    ]
    NOTICE.write_text("\n\n".join(notice) + "\n")


def main():
    manifest = json.loads(MANIFEST.read_text())
    only = set(sys.argv[1:])
    total = 0
    for name, texture in manifest["textures"].items():
        if only and name not in only:
            continue
        files = build(name, texture)
        size = sum(f.stat().st_size for f in files)
        total += size
        loops = texture["kind"] in ("slide", "ambient") or texture.get("recipe") == "stretch"
        note = f"seam x{seam_ratio(files[0]):.1f}" if loops else ""
        print(f"{name:11s} {texture['kind']:8s} {len(files)} file(s) {size // 1024:4d} KB {note}")
    print(f"total {total // 1024} KB")
    if not only:
        write_credits(manifest)
    return 0


if __name__ == "__main__":
    sys.exit(main())
