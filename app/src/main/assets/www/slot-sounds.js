// slot-sounds.js — casino-style sounds synthesized with Web Audio (no files, no licences, ~0 KB).
// Call sounds.unlock() inside the first user gesture (iOS/Android keep audio muted until then).
// To use recorded sounds instead (e.g. Kenney "Casino Audio", CC0): sounds.useFiles({ spin:'spin.ogg', ... }).
export function createSlotSounds() {
  let ctx = null, master = null, muted = false, spinNode = null, files = null;
  const buffers = {};
  const ensure = () => {
    if (!ctx) { const AC = window.AudioContext || window.webkitAudioContext; if (!AC) return null; ctx = new AC(); master = ctx.createGain(); master.gain.value = 0.55; master.connect(ctx.destination); }
    if (ctx.state === 'suspended') ctx.resume();
    return ctx;
  };
  const tone = (freq, t0, dur, { type = 'sine', vol = 0.3, slide = 0, attack = 0.005 } = {}) => {
    const o = ctx.createOscillator(), g = ctx.createGain(); o.type = type; o.frequency.setValueAtTime(freq, t0);
    if (slide) o.frequency.exponentialRampToValueAtTime(Math.max(20, freq * slide), t0 + dur);
    g.gain.setValueAtTime(0.0001, t0); g.gain.exponentialRampToValueAtTime(vol, t0 + attack); g.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);
    o.connect(g).connect(master); o.start(t0); o.stop(t0 + dur + 0.02);
  };
  const noise = (t0, dur, { vol = 0.2, freq = 1200, q = 1 } = {}) => {
    const n = ctx.sampleRate * dur, b = ctx.createBuffer(1, n, ctx.sampleRate), d = b.getChannelData(0);
    for (let i = 0; i < n; i++) d[i] = (Math.random() * 2 - 1) * (1 - i / n);
    const s = ctx.createBufferSource(), f = ctx.createBiquadFilter(), g = ctx.createGain(); s.buffer = b; f.type = 'bandpass'; f.frequency.value = freq; f.Q.value = q; g.gain.value = vol;
    s.connect(f).connect(g).connect(master); s.start(t0);
  };
  const playFile = (k) => { if (!files?.[k] || !buffers[k]) return false; const s = ctx.createBufferSource(); s.buffer = buffers[k]; s.connect(master); s.start(); return true; };
  const N = { C5: 523.25, E5: 659.25, G5: 783.99, C6: 1046.5, E6: 1318.5, G6: 1568, A5: 880, D6: 1174.7 };
  const fx = {
    click() { const t = ctx.currentTime; tone(1800, t, 0.04, { type: 'square', vol: 0.08 }); },
    spinStart() {
      const t = ctx.currentTime; noise(t, 0.12, { vol: 0.25, freq: 900 }); tone(220, t, 0.18, { type: 'triangle', vol: 0.15, slide: 2 });
      // soft mechanical whirr while reels spin (stopped by spinEnd / last reel)
      const o = ctx.createOscillator(), lfo = ctx.createOscillator(), lg = ctx.createGain(), g = ctx.createGain(), f = ctx.createBiquadFilter();
      o.type = 'sawtooth'; o.frequency.value = 70; lfo.frequency.value = 22; lg.gain.value = 18; lfo.connect(lg).connect(o.frequency);
      f.type = 'lowpass'; f.frequency.value = 500; g.gain.setValueAtTime(0.0001, t); g.gain.exponentialRampToValueAtTime(0.05, t + 0.15);
      o.connect(f).connect(g).connect(master); o.start(t); lfo.start(t); spinNode = { o, lfo, g };
    },
    spinEnd() { if (!spinNode) return; const t = ctx.currentTime; spinNode.g.gain.exponentialRampToValueAtTime(0.0001, t + 0.12); spinNode.o.stop(t + 0.15); spinNode.lfo.stop(t + 0.15); spinNode = null; },
    reelStop(i, scatter) {
      const t = ctx.currentTime; noise(t, 0.07, { vol: 0.35, freq: 400 + i * 60, q: 2 }); tone(120 - i * 6, t, 0.09, { type: 'sine', vol: 0.3, slide: 0.6 });
      if (scatter) { tone(N.G5 + i * 90, t + 0.02, 0.35, { type: 'triangle', vol: 0.18 }); tone(N.D6 + i * 120, t + 0.06, 0.4, { type: 'sine', vol: 0.12 }); }
    },
    anticipation() { const t = ctx.currentTime; for (let k = 0; k < 8; k++) tone(600 + k * 70, t + k * 0.09, 0.08, { type: 'square', vol: 0.05 }); },
    winSmall() { const t = ctx.currentTime; [N.C6, N.E6, N.G6].forEach((f, k) => tone(f, t + k * 0.07, 0.25, { type: 'triangle', vol: 0.16 })); },
    tick() { const t = ctx.currentTime; tone(2400 + Math.random() * 300, t, 0.03, { type: 'sine', vol: 0.05 }); },
    bigWin() {
      const t = ctx.currentTime, mel = [N.C5, N.E5, N.G5, N.C6, N.G5, N.C6, N.E6, N.G6];
      mel.forEach((f, k) => { tone(f, t + k * 0.12, 0.3, { type: 'square', vol: 0.07 }); tone(f / 2, t + k * 0.12, 0.3, { type: 'triangle', vol: 0.12 }); });
      for (let k = 0; k < 24; k++) tone(3000 + Math.random() * 2000, t + 1 + k * 0.05, 0.05, { vol: 0.04 });   // coin shower
    },
    freeSpins() { const t = ctx.currentTime; [N.C5, N.E5, N.G5, N.C6, N.E6, N.G6, N.C6 * 2].forEach((f, k) => tone(f, t + k * 0.08, 0.5, { type: 'triangle', vol: 0.13 })); tone(80, t, 1.2, { type: 'sine', vol: 0.25, slide: 0.5 }); },
    noCredits() { const t = ctx.currentTime; tone(300, t, 0.25, { type: 'square', vol: 0.07, slide: 0.6 }); },
  };
  return {
    unlock() { ensure(); },
    play(name, ...args) { if (muted || !ensure()) return; if (playFile(name)) return; try { fx[name]?.(...args); } catch { /* audio is optional */ } },
    stopLoop() { if (ctx) fx.spinEnd(); },
    set muted(v) { muted = !!v; if (muted && ctx) fx.spinEnd(); }, get muted() { return muted; },
    async useFiles(map) { files = map; if (!ensure()) return; await Promise.all(Object.entries(map).map(async ([k, url]) => { try { buffers[k] = await ctx.decodeAudioData(await (await fetch(url)).arrayBuffer()); } catch { delete files[k]; } })); },
  };
}
