// video-slot-engine.js — modern 5×3 video slot rules (pure JS, browser + Node). PLAY MONEY ONLY.
// Left-to-right line pays with WILD substitution, SCATTER anywhere (pays × total bet + triggers free spins),
// free spins with a win multiplier and retriggers. The engine decides every result; visuals only show it.
//
//   import { VideoSlotEngine, ZOHAR } from './video-slot-engine.js';
//   const slot = new VideoSlotEngine(ZOHAR, { credits: 2000 });
//   const r = slot.spin();     // { stops, grid, lineWins, scatter, totalWin, mode, freeLeft, ... }

export const ZOHAR = {
  name: 'ZOHAR', rows: 3,
  wild: 'W', scatter: 'S',
  symbols: ['W', 'S', 'CROWN', 'DIAMOND', 'SEVEN', 'BELL', 'A', 'K', 'Q', 'J', 'TEN'],
  // pays × LINE bet for 3 / 4 / 5 of a kind (left to right, starting on reel 1)
  pays: {
    W: [100, 500, 2000], CROWN: [50, 200, 800], DIAMOND: [40, 150, 600], SEVEN: [30, 100, 400], BELL: [20, 60, 250],
    A: [10, 30, 125], K: [10, 30, 100], Q: [9, 20, 80], J: [7, 16, 60], TEN: [7, 16, 50],
  },
  scatterPays: { 3: 5, 4: 20, 5: 100 },        // × TOTAL bet, anywhere
  freeSpins: { trigger: 3, spins: 10, retrigger: 10, multiplier: 3 },
  // 20 classic paylines (row index per reel; 0 = top)
  lines: [
    [1,1,1,1,1], [0,0,0,0,0], [2,2,2,2,2], [0,1,2,1,0], [2,1,0,1,2],
    [0,0,1,2,2], [2,2,1,0,0], [1,0,0,0,1], [1,2,2,2,1], [0,1,1,1,0],
    [2,1,1,1,2], [1,0,1,2,1], [1,2,1,0,1], [0,1,0,1,0], [2,1,2,1,2],
    [1,1,0,1,1], [1,1,2,1,1], [0,2,0,2,0], [2,0,2,0,2], [0,2,2,2,0],
  ],
  lineBets: [1, 2, 5, 10],                      // total bet = lineBet × 20 lines
  // reel strips: every stop equally likely → symbol counts ARE the odds (1 scatter per reel, wilds 1-2, royals 4).
  // Tuned with vslot_math.mjs: RTP ≈ 95 %, feature ≈ 1 in 133. Edit, then re-run the maths — never guess payouts.
  reels: [
    'Q A TEN SEVEN K J W S A Q TEN BELL K J DIAMOND Q A TEN SEVEN K J BELL CROWN A Q TEN DIAMOND K J BELL',
    'W K J BELL DIAMOND Q A TEN SEVEN K J S W A Q TEN BELL K J DIAMOND TEN A Q SEVEN J K BELL CROWN Q A TEN',
    'BELL CROWN Q A TEN W K J BELL DIAMOND Q A TEN SEVEN K J S W A Q TEN BELL K J DIAMOND TEN A Q SEVEN J K',
    'A DIAMOND BELL K J SEVEN TEN Q A W CROWN K J BELL TEN Q A DIAMOND SEVEN K J S TEN Q A BELL W K J CROWN TEN Q',
    'J BELL TEN Q A CROWN SEVEN J K DIAMOND BELL Q A TEN W J K SEVEN CROWN TEN Q A BELL J K DIAMOND S TEN Q A SEVEN W K',
  ].map((s) => s.split(' ')),
};

// ---------- RNG ----------
export function cryptoRng() {
  const buf = new Uint32Array(1);
  return (n) => { const limit = Math.floor(2 ** 32 / n) * n; let x; do { globalThis.crypto.getRandomValues(buf); x = buf[0]; } while (x >= limit); return x % n; };
}
export function seededRng(seed = 1) {   // deterministic, for tests and replays only
  let a = 0x9e3779b9, b = 0x243f6a88, c = 0xb7e15162, d = seed >>> 0;
  const next = () => { a >>>= 0; b >>>= 0; c >>>= 0; d >>>= 0; let t = (a + b) | 0; a = b ^ (b >>> 9); b = (c + (c << 3)) | 0; c = (c << 21) | (c >>> 11); d = (d + 1) | 0; t = (t + d) | 0; c = (c + t) | 0; return t >>> 0; };
  for (let i = 0; i < 15; i++) next();
  return (n) => { const limit = Math.floor(2 ** 32 / n) * n; let x; do { x = next(); } while (x >= limit); return x % n; };
}

// ---------- evaluation ----------
export function gridFromStops(cfg, stops) {
  return Array.from({ length: cfg.rows }, (_, r) => cfg.reels.map((strip, i) => strip[(stops[i] + r) % strip.length]));
}

/** Best pay for one line of symbols (left to right). Returns { symbol, count, pay } or null. */
export function evalLine(cfg, syms) {
  const W = cfg.wild, S = cfg.scatter;
  // pure-wild run
  let w = 0; while (w < syms.length && syms[w] === W) w++;
  const wildPay = w >= 3 ? cfg.pays[W][w - 3] : 0;
  // target = first non-wild symbol; scatter never forms lines
  const target = syms.find((s) => s !== W);
  let best = wildPay ? { symbol: W, count: w, pay: wildPay } : null;
  if (target && target !== S && cfg.pays[target]) {
    let n = 0; while (n < syms.length && (syms[n] === target || syms[n] === W)) n++;
    if (n >= 3) { const p = cfg.pays[target][n - 3]; if (!best || p > best.pay) best = { symbol: target, count: n, pay: p }; }
  }
  return best;
}

export function evaluate(cfg, grid, lineBet) {
  const lineWins = [];
  cfg.lines.forEach((rows, li) => {
    const syms = rows.map((r, c) => grid[r][c]);
    const b = evalLine(cfg, syms);
    if (b) lineWins.push({ line: li, rows, symbol: b.symbol, count: b.count, win: b.pay * lineBet });
  });
  const cells = [];
  grid.forEach((row, r) => row.forEach((s, c) => { if (s === cfg.scatter) cells.push([r, c]); }));
  const totalBet = lineBet * cfg.lines.length;
  const scatter = { count: cells.length, cells, win: (cfg.scatterPays[cells.length] || 0) * totalBet };
  return { lineWins, scatter, baseWin: lineWins.reduce((s, w) => s + w.win, 0) + scatter.win };
}

export function validateConfig(cfg) {
  const errs = [], ids = new Set(cfg.symbols);
  cfg.reels.forEach((strip, i) => {
    strip.forEach((s, j) => { if (!ids.has(s)) errs.push(`reel ${i} stop ${j}: unknown symbol ${s}`); });
    // at most one scatter visible per reel (keeps scatter maths exact and the screen readable)
    strip.forEach((s, j) => { if (s !== cfg.scatter) return; for (let k = 1; k < cfg.rows; k++) if (strip[(j + k) % strip.length] === cfg.scatter) errs.push(`reel ${i}: scatters at ${j} and ${(j + k) % strip.length} can show together`); });
  });
  if (cfg.reels.length !== 5) errs.push('this engine expects 5 reels');
  cfg.lines.forEach((l, i) => { if (l.length !== cfg.reels.length || l.some((r) => r < 0 || r >= cfg.rows)) errs.push(`line ${i} invalid`); });
  for (const k of Object.keys(cfg.pays)) if (!ids.has(k) || cfg.pays[k].length !== 3) errs.push(`pays.${k} invalid`);
  return errs;
}

// ---------- game state ----------
export class VideoSlotEngine {
  constructor(cfg = ZOHAR, { credits = 2000, rng = cryptoRng(), betIndex = 0 } = {}) {
    const errs = validateConfig(cfg); if (errs.length) throw new Error('Invalid slot config:\n' + errs.join('\n'));
    Object.assign(this, { cfg, credits, rng, betIndex, mode: 'base', freeLeft: 0, freeWon: 0, spins: 0, totalBet: 0, totalWon: 0, lastResult: null });
  }
  get lineBet() { return this.cfg.lineBets[this.betIndex]; }
  get totalBetAmount() { return this.lineBet * this.cfg.lines.length; }
  get inFreeSpins() { return this.mode === 'free'; }
  setBetIndex(i) { if (this.mode === 'base') this.betIndex = Math.max(0, Math.min(this.cfg.lineBets.length - 1, i)); return this.betIndex; }
  canSpin() { return this.mode === 'free' || this.credits >= this.totalBetAmount; }
  spin() {
    const fs = this.cfg.freeSpins, free = this.mode === 'free';
    const cost = free ? 0 : this.totalBetAmount;
    if (!free && this.credits < cost) return { ok: false, reason: 'not-enough-credits', credits: this.credits };
    this.credits -= cost;
    const stops = this.cfg.reels.map((s) => this.rng(s.length));
    const grid = gridFromStops(this.cfg, stops);
    const ev = evaluate(this.cfg, grid, this.lineBet);
    const mult = free ? fs.multiplier : 1;
    const totalWin = ev.baseWin * mult;
    this.credits += totalWin;
    let triggered = 0, fsStarted = false, fsEnded = false;
    if (free) { this.freeLeft--; this.freeWon += totalWin; }
    if (ev.scatter.count >= fs.trigger) {
      triggered = free ? fs.retrigger : fs.spins;
      if (!free) { this.mode = 'free'; this.freeWon = 0; fsStarted = true; }
      this.freeLeft += triggered;
    }
    const fsTotal = this.freeWon;
    if (free && this.freeLeft <= 0) { this.mode = 'base'; this.freeLeft = 0; fsEnded = true; }
    this.spins++; this.totalBet += cost; this.totalWon += totalWin;
    this.lastResult = { ok: true, mode: free ? 'free' : 'base', multiplier: mult, cost, stops, grid, lineWins: ev.lineWins.map((w) => ({ ...w, win: w.win * mult })),
      scatter: { ...ev.scatter, win: ev.scatter.win * mult }, totalWin, triggered, fsStarted, fsEnded, fsTotal, freeLeft: this.freeLeft,
      totalBet: this.totalBetAmount, credits: this.credits };
    return this.lastResult;
  }
  refill(amount = 2000) { if (this.mode === 'base' && this.credits < this.totalBetAmount) this.credits += amount; return this.credits; }   // free play money
  stats() { return { spins: this.spins, totalBet: this.totalBet, totalWon: this.totalWon, observedRtp: this.totalBet ? this.totalWon / this.totalBet : 0 }; }
}
