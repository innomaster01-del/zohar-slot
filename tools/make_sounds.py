#!/usr/bin/env python3
"""Synthesize the casino sound set as 16-bit mono WAVs (no samples, no licences). python3 make_sounds.py OUT_DIR"""
import sys, wave, numpy as np
SR = 44100
rng = np.random.default_rng(7)
def t(d): return np.arange(int(SR * d)) / SR
def env(n, a=0.005, r=None):
    e = np.ones(n); na = max(1, int(SR * a)); e[:na] = np.linspace(0, 1, na)
    r = r if r is not None else n / SR; nr = min(n, int(SR * r)); e[-nr:] *= np.exp(-np.linspace(0, 6, nr)); return e
def tone(f, d, kind='sine', vol=0.3, slide=1.0, a=0.005):
    x = t(d); fr = f * (slide ** (x / d)); ph = 2 * np.pi * np.cumsum(fr) / SR
    w = {'sine': np.sin(ph), 'tri': 2 / np.pi * np.arcsin(np.sin(ph)), 'square': np.sign(np.sin(ph)) * 0.6, 'saw': 2 * ((ph / (2 * np.pi)) % 1) - 1}[kind]
    return w * env(len(x), a) * vol
def noise(d, vol=0.3, lo=200, hi=3000):
    n = rng.standard_normal(int(SR * d)); F = np.fft.rfft(n); fr = np.fft.rfftfreq(len(n), 1 / SR); F[(fr < lo) | (fr > hi)] = 0
    y = np.fft.irfft(F, len(n)); y /= max(1e-9, np.abs(y).max()); return y * env(len(y), 0.002) * vol
def mix(total, *parts):
    out = np.zeros(int(SR * total))
    for start, x in parts:
        i = int(SR * start); j = min(len(out), i + len(x)); out[i:j] += x[: j - i]
    return out
def reverb(x, wet=0.25):
    out = x.copy()
    for d, g in [(0.029, 0.5), (0.047, 0.4), (0.071, 0.3), (0.113, 0.22), (0.167, 0.15)]:
        k = int(SR * d); out[k:] += x[:-k] * g * wet * 2
    return out
def save(name, x):
    x = x / max(1.0, np.abs(x).max() / 0.92)
    with wave.open(f'{sys.argv[1]}/{name}.wav', 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes((x * 32767).astype('<i2').tobytes())

N = dict(C5=523.25, E5=659.25, G5=783.99, C6=1046.5, E6=1318.5, G6=1568.0, D6=1174.7, A5=880.0)
save('click', tone(1800, 0.04, 'square', 0.25))
save('lever', mix(0.7, (0, noise(0.12, 0.5, 200, 1800)), (0.0, tone(160, 0.35, 'tri', 0.5, 0.5)), (0.32, noise(0.08, 0.6, 100, 900)), (0.34, tone(90, 0.3, 'sine', 0.6, 0.7))))
spin = t(1.6); whirr = np.sign(np.sin(2 * np.pi * (70 + 18 * np.sin(2 * np.pi * 22 * spin)) * spin)) * 0.12
save('spin', mix(1.6, (0, noise(0.15, 0.4, 300, 2500)), (0, tone(220, 0.2, 'tri', 0.3, 2)), (0, whirr * env(len(spin), 0.1, 0.4))))
save('reel_stop', mix(0.25, (0, noise(0.07, 0.7, 150, 1200)), (0, tone(120, 0.12, 'sine', 0.7, 0.6))))
save('scatter_stop', reverb(mix(0.7, (0, noise(0.07, 0.6, 150, 1200)), (0, tone(110, 0.12, 'sine', 0.6, 0.6)), (0.02, tone(N['G5'], 0.45, 'tri', 0.35)), (0.06, tone(N['D6'], 0.5, 'sine', 0.25)))))
save('anticipation', mix(1.0, *[(k * 0.09, tone(600 + k * 70, 0.08, 'square', 0.12)) for k in range(10)]))
save('win_small', reverb(mix(0.8, *[(k * 0.07, tone(f, 0.35, 'tri', 0.35)) for k, f in enumerate([N['C6'], N['E6'], N['G6']])])))
mel = [N['C5'], N['E5'], N['G5'], N['C6'], N['G5'], N['C6'], N['E6'], N['G6']]
coins = [(1.0 + k * 0.05, tone(3000 + rng.random() * 2000, 0.06, 'sine', 0.12)) for k in range(30)]
save('big_win', reverb(mix(3.0, *[(k * 0.12, tone(f, 0.32, 'square', 0.14)) for k, f in enumerate(mel)], *[(k * 0.12, tone(f / 2, 0.32, 'tri', 0.25)) for k, f in enumerate(mel)], *coins), 0.35))
save('free_spins', reverb(mix(2.2, *[(k * 0.08, tone(f, 0.6, 'tri', 0.28)) for k, f in enumerate([N['C5'], N['E5'], N['G5'], N['C6'], N['E6'], N['G6'], N['C6'] * 2])], (0, tone(80, 1.4, 'sine', 0.5, 0.5))), 0.4))
save('coins', mix(1.6, *[(k * 0.045 + rng.random() * 0.02, tone(2500 + rng.random() * 2500, 0.07, 'sine', 0.15)) for k in range(30)]))
# thunder: sharp crack + long rumble
crack = noise(0.25, 1.0, 800, 8000); rumble = noise(2.2, 0.9, 30, 250) * env(int(SR * 2.2), 0.05, 1.8)
save('thunder', mix(2.5, (0, crack), (0.05, rumble)))
save('no_credits', tone(300, 0.3, 'square', 0.3, 0.6))
save('scratch', noise(0.14, 0.5, 1500, 7000) * (0.6 + 0.4 * np.sin(2 * np.pi * 38 * t(0.14))))
print('ok')
