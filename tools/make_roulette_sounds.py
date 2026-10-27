"""Roulette sounds, synthesized (no samples): the ball rolling round the track, and the drop (deflector, frets, settle).
Writes app/src/main/assets/sounds/ball_roll.wav and ball_drop.wav. Timing matches RouletteTable.kt (roll 6.5 s, drop 2.2 s)."""
import numpy as np, wave, os
from scipy.signal import butter, sosfilt

SR = 44100
rng = np.random.default_rng(7)
out = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'sounds')

def band(x, lo, hi, order=4):
    return sosfilt(butter(order, [lo / (SR / 2), hi / (SR / 2)], btype='band', output='sos'), x)

def save(name, x):
    x = x / (np.max(np.abs(x)) + 1e-9) * 0.85
    with wave.open(os.path.join(out, name), 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes((x * 32767).astype(np.int16).tobytes())

# ---- the roll: a hard ball on polished wood. Rumble + hiss, pulsing once per lap; laps slow down (same curve as the app) ----
T = 6.5; t = np.arange(int(SR * T)) / SR
w0, tau = 14.0, 4.0                                         # rad/s, s  (ball angular speed = w0·e^(−t/τ))
phase = w0 * tau * (1 - np.exp(-t / tau))                  # radians travelled
speed = np.exp(-t / tau)
n = rng.standard_normal(len(t))
rumble = band(n, 120, 520) * 1.0
hiss = band(n, 1800, 6500) * 0.35
lap = 0.75 + 0.25 * np.cos(phase)                           # louder on the near side of the wheel
grain = 1 + 0.25 * band(rng.standard_normal(len(t)), 20, 60)  # uneven wood
roll = (rumble * (0.6 + 0.4 * speed) + hiss * speed ** 1.5) * lap * grain
env = np.minimum(1, t / 0.15) * (0.35 + 0.65 * speed) * np.minimum(1, (T - t) / 0.4)
save('ball_roll.wav', roll * env)

# ---- the drop: off the track, a clack on a deflector, bounces, then rattling over the frets and settling ----
T = 2.4; t = np.arange(int(SR * T)) / SR
x = np.zeros(len(t))
def click(at, amp, f=3200, decay=0.012, metal=False):
    i = int(at * SR); k = np.arange(int(SR * 0.08)); tt = k / SR
    body = np.sin(2 * np.pi * f * tt) * np.exp(-tt / decay)
    if metal: body += 0.6 * np.sin(2 * np.pi * f * 1.73 * tt) * np.exp(-tt / (decay * 2.5))
    burst = band(rng.standard_normal(len(k)), 900, 7000) * np.exp(-tt / 0.004)
    s = amp * (0.7 * body + 0.5 * burst); j = min(len(x), i + len(k)); x[i:j] += s[: j - i]
# slide down the track
slide = band(rng.standard_normal(len(t)), 150, 600) * np.exp(-t / 0.35) * 0.5
x += slide
click(0.32, 1.0, 2700, 0.02, metal=True)                   # deflector
click(0.55, 0.55, 2300, 0.015)                             # lands on the cone
click(0.74, 0.45, 2500, 0.012)
# rattle over the frets (each a little softer and closer together), then the last settle clicks
at = 0.95; gap = 0.085; amp = 0.8
while at < 2.05:
    click(at, amp * (0.8 + 0.4 * rng.random()), 3600 + 800 * rng.random(), 0.006, metal=True)
    at += gap * (0.8 + 0.4 * rng.random()); gap *= 0.93; amp *= 0.9
save('ball_drop.wav', x * np.minimum(1, (T - t) / 0.2))
print('ok')
