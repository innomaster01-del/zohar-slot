#!/usr/bin/env python3
"""Synthesize the craps dice sounds as 16-bit mono WAVs (no samples, no licences). python3 make_dice_sounds.py OUT_DIR
dice_shake: two dice rattling in a closed hand · dice_wall: both dice striking the rubber wall · dice_hit: one die landing on the felt"""
import sys, wave, numpy as np
SR = 44100
rng = np.random.default_rng(11)
def band(n, lo, hi):
    x = rng.standard_normal(n); F = np.fft.rfft(x); fr = np.fft.rfftfreq(n, 1 / SR); F[(fr < lo) | (fr > hi)] = 0
    y = np.fft.irfft(F, n); return y / max(1e-9, np.abs(y).max())
def clack(vol=1.0, bright=1.0, d=0.05):
    """A hard plastic cube hitting something: a sharp click with a short woody body."""
    n = int(SR * d); t = np.arange(n) / SR
    click = band(n, 2500, 9000 * bright) * np.exp(-t * 420)
    body = np.sin(2 * np.pi * (1500 + 500 * rng.random()) * t) * np.exp(-t * 160) * 0.5 + np.sin(2 * np.pi * (2600 + 600 * rng.random()) * t) * np.exp(-t * 240) * 0.3
    return (click + body) * vol
def thud(vol=1.0, d=0.09):
    """The soft part of a landing on cloth."""
    n = int(SR * d); t = np.arange(n) / SR
    return (np.sin(2 * np.pi * 150 * t * (1 - 0.5 * t / d)) * np.exp(-t * 60) * 0.7 + band(n, 100, 700) * np.exp(-t * 90) * 0.5) * vol
def mix(total, parts):
    out = np.zeros(int(SR * total))
    for start, x in parts:
        i = int(SR * start); j = min(len(out), i + len(x)); out[i:j] += x[: j - i]
    return out
def save(name, x, peak=0.9):
    x = x / np.abs(x).max() * peak
    with wave.open(f'{sys.argv[1]}/{name}.wav', 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes((x * 32767).astype('<i2').tobytes())
# rattle: irregular clacks, muffled by the hand
parts = []; t = 0.0
while t < 0.62: parts.append((t, clack(0.35 + 0.5 * rng.random(), 0.45, 0.035))); t += 0.035 + 0.06 * rng.random()
save('dice_shake', mix(0.7, parts), 0.6)
# wall: two dice a few milliseconds apart, duller (rubber), each with a small thud
save('dice_wall', mix(0.22, [(0.0, clack(1.0, 0.6)), (0.0, thud(0.6, 0.06)), (0.034, clack(0.85, 0.6)), (0.036, thud(0.5, 0.06))]), 0.85)
# one landing on the felt
save('dice_hit', mix(0.14, [(0.0, clack(0.8, 0.8, 0.04)), (0.0, thud(1.0))]), 0.8)
