#!/usr/bin/env python3
"""Synthesize the SKY RUSH sounds as 16-bit mono WAVs (no samples, no licences). python3 make_crash_sounds.py OUT_DIR
jet_loop: a jet engine at speed, loops seamlessly (the app raises its pitch as the multiplier climbs) ·
jet_takeoff: the spool-up and roll down the runway · boom: the crash, a blast with a low rumble and falling debris ·
cash_out: a bright two-note chime with a shimmer"""
import sys, wave, numpy as np
SR = 44100
rng = np.random.default_rng(7)
def band(n, lo, hi):
    x = rng.standard_normal(n); F = np.fft.rfft(x); fr = np.fft.rfftfreq(n, 1 / SR); F[(fr < lo) | (fr > hi)] = 0
    y = np.fft.irfft(F, n); return y / max(1e-9, np.abs(y).max())
def save(name, x, peak=0.9):
    x = x / np.abs(x).max() * peak
    with wave.open(f'{sys.argv[1]}/{name}.wav', 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes((x * 32767).astype('<i2').tobytes())
def env(n, a, r):
    e = np.ones(n); ia = int(SR * a); ir = int(SR * r)
    if ia: e[:ia] = np.linspace(0, 1, ia)
    if ir: e[-ir:] *= np.linspace(1, 0, ir)
    return e

# the engine: broadband roar (filtered noise) + a turbine whine; a whole number of whine cycles so it loops without a click
d = 2.0; n = int(SR * d); t = np.arange(n) / SR
roar = band(n, 60, 900) * 0.8 + band(n, 900, 4000) * 0.25
f = 1400.0 / d * d; whine = np.sin(2 * np.pi * round(1400 * d) / d * t) * 0.10 + np.sin(2 * np.pi * round(2100 * d) / d * t) * 0.05
hum = np.sin(2 * np.pi * round(110 * d) / d * t) * 0.25
loop = roar + whine + hum
# make the noise loop too: cross-fade the end into the start
k = int(SR * 0.25); fade = np.linspace(0, 1, k); loop[:k] = loop[:k] * fade + loop[-k:] * (1 - fade); loop = loop[: n - k]
save('jet_loop', loop, 0.7)

# take-off: the engine spooling up (pitch and level rising) for 2.2 s
d = 2.2; n = int(SR * d); t = np.arange(n) / SR; p = t / d
ph = 2 * np.pi * np.cumsum(500 + 1300 * p ** 1.5) / SR
take = band(n, 50, 1200) * (0.2 + 0.8 * p) + band(n, 1200, 5000) * 0.3 * p ** 2 + np.sin(ph) * 0.12 * (0.3 + p)
save('jet_takeoff', take * env(n, 0.15, 0.2), 0.75)

# the crash: a sharp crack, a fireball roar, a long low rumble, pieces falling
d = 2.6; n = int(SR * d); t = np.arange(n) / SR
crack = band(n, 1500, 9000) * np.exp(-t * 60)
blast = band(n, 40, 1500) * np.exp(-t * 3.2)
rumble = np.sin(2 * np.pi * 42 * t * (1 - 0.15 * t)) * np.exp(-t * 1.6) * 0.8 + band(n, 20, 160) * np.exp(-t * 1.2) * 0.9
debris = np.zeros(n)
for _ in range(18):
    i = int(SR * (0.35 + rng.random() * 1.6)); m = int(SR * 0.04); tt = np.arange(m) / SR
    debris[i:i + m] += band(m, 1800, 7000) * np.exp(-tt * 140) * (0.15 + rng.random() * 0.25)
save('boom', crack * 0.7 + blast + rumble + debris, 0.95)

# cash-out: two bright bells a fifth apart with a sparkle tail
d = 1.2; n = int(SR * d); t = np.arange(n) / SR
def bell(f0, start, amp):
    x = np.zeros(n); i = int(SR * start); tt = t[: n - i]
    x[i:] = amp * np.exp(-tt * 4.5) * (np.sin(2 * np.pi * f0 * tt) + 0.4 * np.sin(2 * np.pi * f0 * 2.76 * tt) * np.exp(-tt * 6) + 0.2 * np.sin(2 * np.pi * f0 * 5.4 * tt) * np.exp(-tt * 10))
    return x
sparkle = band(n, 6000, 12000) * np.exp(-t * 5) * 0.12
save('cash_out', bell(1318.5, 0, 0.8) + bell(1975.5, 0.09, 0.7) + sparkle, 0.85)
