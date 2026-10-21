// reels-screen.js — the machine's video screen: 5×3 reels, symbol art, spin/stop animation, anticipation,
// win lines, win count-up, free-spins intro, big-win celebration and the credit/bet/win meters.
// Pure canvas 2D (no three.js): use it flat, or as a CanvasTexture on the cabinet's curved screen.
// It ONLY shows engine results: startSpin(result) animates the reels to land exactly on result.stops.
//
//   const screen = createReelScreen({ cfg: ZOHAR });
//   screen.startSpin(engine.spin());          // animate to the engine's result
//   if (screen.update(dt)) texture.needsUpdate = true;   // true only when something was redrawn
//   screen.visibleGrid()                      // what is ACTUALLY drawn (tests compare it with result.grid)

const TAU = Math.PI * 2;
const ease = { outBack: (x, s = 1.2) => 1 + (s + 1) * (x - 1) ** 3 + s * (x - 1) ** 2, outCubic: (x) => 1 - (1 - x) ** 3 };

// ---------------------------------------------------------------- symbol art (original designs, vector, no files)
function grad(c, x0, y0, x1, y1, stops) { const g = c.createLinearGradient(x0, y0, x1, y1); stops.forEach(([o, col]) => g.addColorStop(o, col)); return g; }
function rgrad(c, x, y, r0, r1, stops) { const g = c.createRadialGradient(x, y, r0, x, y, r1); stops.forEach(([o, col]) => g.addColorStop(o, col)); return g; }
const GOLD = [[0, '#fff6c2'], [0.35, '#ffd34d'], [0.6, '#d99a14'], [1, '#8a5a06']];

function drawSymbol(c, id, w, h, font) {
  const cx = w / 2, cy = h / 2, u = Math.min(w, h);
  c.save(); c.lineJoin = 'round';
  const glow = (col, blur) => { c.shadowColor = col; c.shadowBlur = blur; };
  if (['A', 'K', 'Q', 'J', 'TEN'].includes(id)) {
    const col = { A: ['#ff6b6b', '#b3001b'], K: ['#d58cff', '#6a1b9a'], Q: ['#7cc6ff', '#0b5394'], J: ['#7dffa8', '#0b7a3b'], TEN: ['#ffc46b', '#b35900'] }[id];
    const txt = id === 'TEN' ? '10' : id;
    c.font = `900 ${u * (id === 'TEN' ? 0.56 : 0.66)}px ${font}`; c.textAlign = 'center'; c.textBaseline = 'middle';
    glow(col[0], u * 0.12);
    c.lineWidth = u * 0.07; c.strokeStyle = '#1a0d00'; c.strokeText(txt, cx, cy + u * 0.03);
    c.fillStyle = grad(c, 0, cy - u * 0.3, 0, cy + u * 0.3, [[0, '#ffffff'], [0.25, col[0]], [0.75, col[1]], [1, '#2a0010']]);
    c.fillText(txt, cx, cy + u * 0.03); c.shadowBlur = 0;
    c.lineWidth = u * 0.018; c.strokeStyle = 'rgba(255,255,255,0.65)'; c.strokeText(txt, cx, cy + u * 0.03);
  } else if (id === 'SEVEN') {
    c.font = `900 ${u * 0.86}px Georgia, 'Times New Roman', serif`; c.textAlign = 'center'; c.textBaseline = 'middle';
    glow('#ff2a3a', u * 0.22); c.lineWidth = u * 0.1; c.strokeStyle = '#ffe9a8'; c.strokeText('7', cx, cy + u * 0.05);
    c.fillStyle = grad(c, 0, cy - u * 0.4, 0, cy + u * 0.4, [[0, '#ff9a9a'], [0.45, '#ff1a2c'], [1, '#7a0010']]); c.fillText('7', cx, cy + u * 0.05);
    c.shadowBlur = 0; c.lineWidth = u * 0.02; c.strokeStyle = '#8a0010'; c.strokeText('7', cx, cy + u * 0.05);
  } else if (id === 'BELL') {
    c.translate(cx, cy); glow('#ffcf33', u * 0.15);
    c.beginPath(); c.moveTo(-u * 0.34, u * 0.22); c.bezierCurveTo(-u * 0.32, -u * 0.05, -u * 0.26, -u * 0.36, 0, -u * 0.36);
    c.bezierCurveTo(u * 0.26, -u * 0.36, u * 0.32, -u * 0.05, u * 0.34, u * 0.22); c.quadraticCurveTo(0, u * 0.3, -u * 0.34, u * 0.22); c.closePath();
    c.fillStyle = grad(c, -u * 0.3, 0, u * 0.3, 0, [[0, '#8a5a06'], [0.3, '#ffd34d'], [0.5, '#fff3b0'], [0.75, '#e0a21a'], [1, '#7a4c04']]); c.fill();
    c.shadowBlur = 0; c.lineWidth = u * 0.02; c.strokeStyle = '#5c3a00'; c.stroke();
    c.fillStyle = grad(c, 0, u * 0.2, 0, u * 0.36, GOLD); c.beginPath(); c.arc(0, u * 0.29, u * 0.07, 0, TAU); c.fill();
    c.fillStyle = 'rgba(255,255,255,0.55)'; c.beginPath(); c.ellipse(-u * 0.12, -u * 0.12, u * 0.04, u * 0.14, 0.3, 0, TAU); c.fill();
  } else if (id === 'DIAMOND') {
    c.translate(cx, cy + u * 0.02); glow('#4fd8ff', u * 0.2);
    const top = -u * 0.24, mid = -u * 0.06, bot = u * 0.36, hw = u * 0.38, tw = u * 0.2;
    const facets = [
      [[-hw, mid], [-tw, top], [0, mid], '#bff4ff'], [[0, mid], [-tw, top], [tw, top], '#e9fbff'], [[0, mid], [tw, top], [hw, mid], '#8fe6ff'],
      [[-hw, mid], [0, mid], [0, bot], '#1aa7e0'], [[0, mid], [hw, mid], [0, bot], '#0a6fb8'],
    ];
    facets.forEach(([a, b, d, col]) => { c.beginPath(); c.moveTo(...a); c.lineTo(...b); c.lineTo(...d); c.closePath(); c.fillStyle = col; c.fill(); });
    c.shadowBlur = 0; c.strokeStyle = 'rgba(255,255,255,0.8)'; c.lineWidth = u * 0.012;
    c.beginPath(); c.moveTo(-hw, mid); c.lineTo(-tw, top); c.lineTo(tw, top); c.lineTo(hw, mid); c.lineTo(0, bot); c.closePath(); c.moveTo(-hw, mid); c.lineTo(hw, mid); c.stroke();
    c.fillStyle = '#fff'; [[-u * 0.1, top + u * 0.06, u * 0.025], [u * 0.2, mid + u * 0.08, u * 0.018]].forEach(([x, y, r]) => { c.beginPath(); c.arc(x, y, r, 0, TAU); c.fill(); });
  } else if (id === 'CROWN') {
    c.translate(cx, cy + u * 0.04); glow('#ffd34d', u * 0.18);
    c.beginPath(); c.moveTo(-u * 0.38, u * 0.2); c.lineTo(-u * 0.42, -u * 0.18); c.lineTo(-u * 0.2, u * 0.0); c.lineTo(0, -u * 0.3); c.lineTo(u * 0.2, u * 0.0); c.lineTo(u * 0.42, -u * 0.18); c.lineTo(u * 0.38, u * 0.2); c.closePath();
    c.fillStyle = grad(c, 0, -u * 0.3, 0, u * 0.22, GOLD); c.fill(); c.shadowBlur = 0; c.lineWidth = u * 0.02; c.strokeStyle = '#6b4300'; c.stroke();
    c.fillStyle = grad(c, 0, u * 0.18, 0, u * 0.3, GOLD); c.beginPath(); c.roundRect(-u * 0.4, u * 0.18, u * 0.8, u * 0.1, u * 0.03); c.fill(); c.stroke();
    [[-u * 0.42, -u * 0.2, '#ff3b5c'], [0, -u * 0.32, '#3bc7ff'], [u * 0.42, -u * 0.2, '#ff3b5c']].forEach(([x, y, col]) => { c.fillStyle = rgrad(c, x - u * 0.015, y - u * 0.015, 1, u * 0.055, [[0, '#fff'], [0.4, col], [1, '#300']]); c.beginPath(); c.arc(x, y, u * 0.05, 0, TAU); c.fill(); });
    c.fillStyle = rgrad(c, 0, u * 0.08, 1, u * 0.07, [[0, '#fff'], [0.4, '#2dff9a'], [1, '#003a1c']]); c.beginPath(); c.arc(0, u * 0.08, u * 0.06, 0, TAU); c.fill();
  } else if (id === 'W') {
    c.translate(cx, cy); glow('#c46bff', u * 0.25);
    c.fillStyle = grad(c, 0, -u * 0.4, 0, u * 0.4, [[0, '#e3b5ff'], [0.5, '#7a1fd1'], [1, '#2a0057']]);
    c.beginPath(); c.roundRect(-u * 0.44, -u * 0.32, u * 0.88, u * 0.64, u * 0.12); c.fill(); c.shadowBlur = 0;
    c.lineWidth = u * 0.035; c.strokeStyle = grad(c, 0, -u * 0.32, 0, u * 0.32, GOLD); c.stroke();
    c.font = `900 ${u * 0.3}px ${font}`; c.textAlign = 'center'; c.textBaseline = 'middle';
    c.lineWidth = u * 0.05; c.strokeStyle = '#3a1500'; c.strokeText('WILD', 0, u * 0.02);
    c.fillStyle = grad(c, 0, -u * 0.12, 0, u * 0.14, GOLD); c.fillText('WILD', 0, u * 0.02);
    for (let i = 0; i < 6; i++) { const a = i / 6 * TAU, x = Math.cos(a) * u * 0.36, y = Math.sin(a) * u * 0.25; c.fillStyle = 'rgba(255,240,180,0.9)'; star(c, x, y, u * 0.035, u * 0.012); }
  } else if (id === 'S') {
    c.translate(cx, cy); glow('#ffe066', u * 0.28);
    c.fillStyle = grad(c, -u * 0.3, -u * 0.3, u * 0.3, u * 0.3, GOLD); c.beginPath(); c.arc(0, 0, u * 0.4, 0, TAU); c.fill(); c.shadowBlur = 0;
    c.lineWidth = u * 0.03; c.strokeStyle = '#7a4c04'; c.stroke();
    c.beginPath(); c.arc(0, 0, u * 0.32, 0, TAU); c.strokeStyle = 'rgba(122,76,4,0.7)'; c.lineWidth = u * 0.015; c.stroke();
    c.fillStyle = grad(c, 0, -u * 0.25, 0, u * 0.25, [[0, '#fff8d6'], [1, '#ffb300']]); star(c, 0, -u * 0.06, u * 0.2, u * 0.085);
    c.font = `900 ${u * 0.13}px ${font}`; c.textAlign = 'center'; c.textBaseline = 'middle'; c.lineWidth = u * 0.03; c.strokeStyle = '#5c3a00';
    c.strokeText('BONUS', 0, u * 0.2); c.fillStyle = '#fff2b3'; c.fillText('BONUS', 0, u * 0.2);
  }
  c.restore();
}
function star(c, x, y, r, r2, n = 5) { c.beginPath(); for (let i = 0; i < n * 2; i++) { const a = -Math.PI / 2 + i * Math.PI / n, rr = i % 2 ? r2 : r; c.lineTo(x + Math.cos(a) * rr, y + Math.sin(a) * rr); } c.closePath(); c.fill(); }

// ---------------------------------------------------------------- the screen
export function createReelScreen({ cfg, width = 640, height = 960, font = 'Heebo, Rubik, "Segoe UI", Arial, sans-serif', labels = {}, events = {} } = {}) {
  const L = { credit: 'יתרה', bet: 'הימור', win: 'זכייה', playMoney: 'כסף משחק בלבד · לכיף', freeSpins: 'סיבובים חינם', fsIntro: 'סיבובים חינם!', fsMult: 'כל זכייה ×3',
    fsOutro: 'סה״כ בסיבובים החינמיים', collected: 'נצבר:', big: 'זכייה גדולה!', mega: 'זכייה ענקית!', epic: 'זכייה אדירה!', tapSpin: 'לחצו סובב', noCredit: 'נגמרה היתרה · לחצו על המסך למילוי חינם', ...labels };
  const R = cfg.reels.length, ROWS = cfg.rows;
  const canvas = (typeof OffscreenCanvas !== 'undefined' && !globalThis.document) ? new OffscreenCanvas(width, height) : Object.assign(document.createElement('canvas'), { width, height });
  const c = canvas.getContext('2d');
  // layout (all in canvas px)
  const pad = Math.round(width * 0.035), top = Math.round(height * 0.2), reelsH = Math.round(height * 0.6);
  const gap = Math.round(width * 0.008), reelW = (width - pad * 2 - gap * (R - 1)) / R, cellH = reelsH / ROWS;
  const reelX = (i) => pad + i * (reelW + gap), metersY = top + reelsH + Math.round(height * 0.025);
  // symbol atlas: sharp + motion-blurred versions, rendered once
  const atlas = {}, blur = {};
  const sw = Math.round(reelW), sh = Math.round(cellH);
  for (const id of cfg.symbols) {
    const a = mk(sw, sh); drawSymbol(a.getContext('2d'), id, sw, sh, font); atlas[id] = a;
    const b = mk(sw, sh), bc = b.getContext('2d'); bc.globalAlpha = 0.28;
    for (let k = -3; k <= 3; k++) bc.drawImage(a, 0, k * sh * 0.06); blur[id] = b;
  }
  function mk(w, h) { return (typeof OffscreenCanvas !== 'undefined' && !globalThis.document) ? new OffscreenCanvas(w, h) : Object.assign(document.createElement('canvas'), { width: w, height: h }); }

  const reels = cfg.reels.map((strip, i) => ({ strip, n: strip.length, pos: (i * 7) % strip.length, from: 0, to: 0, t: 0, dur: 0, spinning: false, stopped: true, antic: false }));
  let busy = false, result = null, phase = 'idle', phaseT = 0, dirty = true, winShown = 0, winTarget = 0, lineCycle = 0, lineT = 0;
  let hud = { credits: 0, bet: 0, win: 0, freeLeft: 0, freeTotal: 0, inFree: false, message: '' };
  let overlay = null;                 // { kind: 'fsIntro'|'fsOutro'|'big', t, dur, amount, title }
  const particles = [];
  const now = () => (typeof performance !== 'undefined' ? performance.now() : Date.now());

  function startSpin(res, { turbo = false } = {}) {
    if (busy || !res || !res.ok) return false;
    result = res; busy = true; phase = 'spin'; phaseT = 0; winShown = 0; winTarget = res.totalWin; lineCycle = 0; overlay = null;
    const scat = res.grid.map((row) => row.map((s) => s === cfg.scatter));
    let scattersSoFar = 0, extra = 0;
    reels.forEach((r, i) => {
      // anticipation: once 2 scatters have landed, the remaining reels spin longer with a glow (they can still miss)
      const anticip = scattersSoFar >= cfg.freeSpins.trigger - 1; if (anticip) extra += turbo ? 0.25 : 0.9;
      r.antic = anticip && !turbo;
      if (scat.some((row) => row[i])) scattersSoFar++;
      const turns = (turbo ? 1 : 2) + i + (anticip ? 2 : 0);
      const d = (((r.pos - res.stops[i]) % r.n) + r.n) % r.n;                  // steps down to the target stop
      r.from = r.pos; r.to = r.pos - d - turns * r.n;                             // decreasing pos = symbols move down
      r.dur = (turbo ? 0.35 : 0.75) + i * (turbo ? 0.08 : 0.17) + extra; r.t = 0; r.spinning = true; r.stopped = false;
    });
    events.spinStart?.(res); dirty = true; return true;
  }
  function slamStop() { if (phase !== 'spin') return; reels.forEach((r) => { if (r.spinning) r.dur = Math.min(r.dur, r.t + 0.12 + 0.03 * reels.indexOf(r)); }); }

  function update(dt, { draw: doDraw = true } = {}) {
    dt = Math.min(dt, 0.1); phaseT += dt;
    if (phase === 'spin') {
      let all = true;
      reels.forEach((r, i) => {
        if (!r.spinning) return; all = false; r.t = Math.min(r.dur, r.t + dt);
        const x = r.t / r.dur;
        r.pos = r.from + (r.to - r.from) * (x < 1 ? ease.outBack(x, 0.6) : 1);
        if (x >= 1) { r.spinning = false; r.stopped = true; r.pos = ((Math.round(r.to) % r.n) + r.n) % r.n; r.antic = false; events.reelStop?.(i, result.grid.some((row) => row[i] === cfg.scatter)); }
      });
      dirty = true;
      if (all) {
        phase = 'result'; phaseT = 0;
        const tb = result.totalBet, x = result.totalWin / tb;
        if (result.fsStarted) overlay = { kind: 'fsIntro', t: 0, dur: 2.6, amount: result.triggered };
        else if (x >= 15) overlay = { kind: 'big', t: 0, dur: x >= 80 ? 6 : x >= 40 ? 4.5 : 3.2, amount: result.totalWin, title: x >= 80 ? L.epic : x >= 40 ? L.mega : L.big };
        events.result?.(result, overlay?.kind || null);
        if (overlay?.kind === 'big') burst(60);
      }
    }
    if (phase === 'show') {                       // after a win: keep cycling the lines until the next spin (not busy)
      if (result.lineWins.length > 1) { lineT += dt; if (lineT > 0.9) { lineT = 0; lineCycle = (lineCycle + 1) % (result.lineWins.length + 1); dirty = true; } }
      dirty = dirty || Math.floor(phaseT * 8) !== Math.floor((phaseT - dt) * 8);
    }
    if (phase === 'result') {
      // count the win up (fast for small wins, slower for big ones)
      if (winShown < winTarget) { winShown = Math.min(winTarget, winShown + Math.max(1, winTarget * dt / (overlay?.kind === 'big' ? overlay.dur * 0.7 : 0.8))); dirty = true; }
      if (result.lineWins.length > 1) { lineT += dt; if (lineT > 0.9) { lineT = 0; lineCycle = (lineCycle + 1) % (result.lineWins.length + 1); dirty = true; } }
      if (result.lineWins.length || result.scatter.win) dirty = dirty || Math.floor(phaseT * 8) !== Math.floor((phaseT - dt) * 8);   // pulse at 8 fps
      const settled = !overlay && winShown >= winTarget && phaseT > (result.totalWin ? 1.0 : 0.15);
      if (settled || (overlay && overlay.t >= overlay.dur && winShown >= winTarget)) {
        if (overlay?.kind !== 'fsOutro' && result.fsEnded) { overlay = { kind: 'fsOutro', t: 0, dur: 2.8, amount: result.fsTotal }; burst(40); events.fsOutro?.(result); }
        else if (settled || overlay) { overlay = null; busy = false; phase = result.totalWin > 0 ? 'show' : 'idle'; phaseT = 0; dirty = true; events.done?.(result); }
      }
    }
    if (overlay) { overlay.t += dt; dirty = true; }
    for (let i = particles.length - 1; i >= 0; i--) { const p = particles[i]; p.vy += 900 * dt; p.x += p.vx * dt; p.y += p.vy * dt; p.a += p.va * dt; p.life -= dt; if (p.life <= 0 || p.y > height + 40) particles.splice(i, 1); }
    if (particles.length) dirty = true;
    if (phase === 'idle' && reels.some((r) => r.antic)) dirty = true;
    if (!dirty) return false;
    if (doDraw) { draw(); dirty = false; }          // logic-only frames keep `dirty`, so the next drawn frame is current
    return true;
  }
  function burst(n) { for (let i = 0; i < n; i++) particles.push({ x: width / 2 + (Math.random() - 0.5) * width * 0.3, y: top + reelsH * 0.4, vx: (Math.random() - 0.5) * 900, vy: -300 - Math.random() * 700, a: Math.random() * TAU, va: (Math.random() - 0.5) * 12, life: 1.6 + Math.random() * 1.2, r: 8 + Math.random() * 10 }); }

  // ---------------------------------------------------------------- drawing
  // Static parts are drawn ONCE into cached layers; text layers re-render only when their content changes.
  // A frame is then ~25 drawImage calls (measured necessary: drawing everything per frame was far too slow).
  const layer = (w, h, fn) => { const cv = mk(w, h); fn(cv.getContext('2d')); return cv; };
  const bgLayer = layer(width, height, (g) => {
    g.fillStyle = grad(g, 0, 0, 0, height, [[0, '#120424'], [0.5, '#1d0636'], [1, '#07010f']]); g.fillRect(0, 0, width, height);
    g.beginPath(); g.roundRect(pad - 6, top - 6, width - pad * 2 + 12, reelsH + 12, 16); g.fillStyle = '#05010a'; g.fill();
    g.lineWidth = 6; g.strokeStyle = grad(g, 0, top, 0, top + reelsH, GOLD); g.stroke();
    for (let i = 0; i < R; i++) { g.fillStyle = grad(g, 0, top, 0, top + reelsH, [[0, '#1b0a33'], [0.5, '#2a1150'], [1, '#14062a']]); g.fillRect(reelX(i), top, reelW, reelsH); }
  });
  const glassLayer = layer(width, reelsH, (g) => {
    g.fillStyle = grad(g, 0, 0, 0, reelsH, [[0, 'rgba(0,0,0,0.55)'], [0.18, 'rgba(0,0,0,0)'], [0.82, 'rgba(0,0,0,0)'], [1, 'rgba(0,0,0,0.6)']]);
    for (let i = 0; i < R; i++) g.fillRect(reelX(i), 0, reelW, reelsH);
    g.fillStyle = 'rgba(255,215,90,0.35)'; for (let i = 1; i < R; i++) g.fillRect(reelX(i) - gap / 2 - 1, 0, 2, reelsH);
  });
  const cachedLayer = (w, h) => ({ key: null, cv: mk(w, h) });
  const headerL = cachedLayer(width, top), metersL = cachedLayer(width, height - metersY);
  function draw() {
    c.drawImage(bgLayer, 0, 0);
    const hk = hud.inFree ? `F${hud.freeLeft}|${Math.round(hud.freeTotal)}` : 'B';
    if (headerL.key !== hk) { headerL.key = hk; const g = headerL.cv.getContext('2d'); g.clearRect(0, 0, width, top); drawHeader(g); }
    c.drawImage(headerL.cv, 0, 0);
    for (let i = 0; i < R; i++) drawReel(i);
    c.drawImage(glassLayer, 0, top);
    if (phase === 'result' || phase === 'show') drawWins();
    const winVal = phase !== 'spin' && result ? Math.floor(winShown) : 0;
    const mk2 = `${Math.round(hud.credits)}|${hud.bet}|${winVal}|${hud.message || ''}`;
    if (metersL.key !== mk2) { metersL.key = mk2; const g = metersL.cv.getContext('2d'); g.clearRect(0, 0, width, height - metersY); drawMeters(g, winVal); }
    c.drawImage(metersL.cv, 0, metersY);
    if (overlay) drawOverlay();
    drawParticles();
  }
  function drawHeader(c) {
    const h = top - pad * 0.6;
    c.save(); c.textAlign = 'center'; c.textBaseline = 'middle';
    if (hud.inFree) {
      c.fillStyle = grad(c, 0, 0, width, 0, [[0, '#ff3d7f'], [0.5, '#ffb400'], [1, '#ff3d7f']]); c.globalAlpha = 0.18; c.fillRect(0, 0, width, top - 4); c.globalAlpha = 1;
      c.font = `900 ${h * 0.34}px ${font}`; c.direction = 'rtl'; c.fillStyle = '#ffe680'; c.shadowColor = '#ff7a00'; c.shadowBlur = 18;
      c.fillText(`${L.freeSpins}: ${hud.freeLeft}`, width / 2, h * 0.38);
      c.font = `700 ${h * 0.2}px ${font}`; c.shadowBlur = 0; c.fillStyle = '#ffd1f0'; c.fillText(`${L.fsMult} · ${L.collected} ${fmt(hud.freeTotal)}`, width / 2, h * 0.78);
    } else {
      c.font = `900 ${h * 0.56}px ${font}`; c.direction = 'ltr';
      c.shadowColor = '#c46bff'; c.shadowBlur = 26; c.lineWidth = 8; c.strokeStyle = '#3a0a66'; c.strokeText('ZOHAR', width / 2, h * 0.48);
      c.fillStyle = grad(c, 0, h * 0.2, 0, h * 0.75, GOLD); c.fillText('ZOHAR', width / 2, h * 0.48); c.shadowBlur = 0;
      c.font = `700 ${h * 0.15}px ${font}`; c.fillStyle = '#e8c7ff'; c.direction = 'rtl'; c.fillText(L.playMoney, width / 2, h * 0.9);
    }
    c.restore();
  }
  function drawReel(i) {
    const r = reels[i], x = reelX(i), base = Math.floor(r.pos), frac = r.pos - base;
    const speed = r.spinning ? Math.abs((r.to - r.from) / r.dur) : 0;
    c.save(); c.beginPath(); c.rect(x, top, reelW, reelsH); c.clip();
    c.drawImage(bgLayer, x, top, reelW, reelsH, x, top, reelW, reelsH);          // reel background from the cached layer
    // rows −1..ROWS: symbol at strip[base + row] is drawn at row − frac (pos decreases → content moves down)
    for (let row = -1; row <= ROWS; row++) {
      const id = r.strip[(((base + row) % r.n) + r.n) % r.n];
      const y = top + (row - frac) * cellH;
      c.drawImage(r.spinning && speed > 12 ? blur[id] : atlas[id], x, y, reelW, cellH);
    }
    if (r.antic) { const p = 0.5 + 0.5 * Math.sin(now() / 90); c.lineWidth = 6; c.strokeStyle = `rgba(255,${180 + 60 * p | 0},40,${0.6 + 0.4 * p})`; c.shadowColor = '#ffb400'; c.shadowBlur = 25; c.strokeRect(x + 3, top + 3, reelW - 6, reelsH - 6); }
    c.restore();
  }
  const cellCenter = (row, col) => [reelX(col) + reelW / 2, top + row * cellH + cellH / 2];
  function drawWins() {
    const pulse = 0.5 + 0.5 * Math.sin(phaseT * 8);
    const wins = result.lineWins, showAll = lineCycle === wins.length || wins.length === 1;
    const shown = showAll ? wins : [wins[lineCycle]];
    const cells = new Set();
    shown.forEach((w) => { for (let k = 0; k < w.count; k++) cells.add(`${w.rows[k]},${k}`); });
    if (result.scatter.win || result.fsStarted) result.scatter.cells.forEach(([r, col]) => cells.add(`${r},${col}`));
    // dim non-winning cells, frame winning ones
    if (cells.size) {
      for (let row = 0; row < ROWS; row++) for (let col = 0; col < R; col++) if (!cells.has(`${row},${col}`)) { c.fillStyle = 'rgba(5,0,15,0.55)'; c.fillRect(reelX(col), top + row * cellH, reelW, cellH); }
      cells.forEach((k) => { const [row, col] = k.split(',').map(Number); const x = reelX(col), y = top + row * cellH;
        c.save(); c.lineWidth = 5; c.strokeStyle = `rgba(255,${200 + 55 * pulse | 0},80,1)`; c.shadowColor = '#ffcc00'; c.shadowBlur = 12 + 14 * pulse;
        c.beginPath(); c.roundRect(x + 5, y + 5, reelW - 10, cellH - 10, 12); c.stroke();
        const id = result.grid[row][col], s = 1 + 0.06 * pulse; c.shadowBlur = 0;
        c.drawImage(atlas[id], x + reelW * (1 - s) / 2, y + cellH * (1 - s) / 2, reelW * s, cellH * s); c.restore(); });
    }
    // line paths
    const colors = ['#ff4d6d', '#ffd166', '#06d6a0', '#4cc9f0', '#c77dff'];
    shown.forEach((w) => { c.save(); c.lineWidth = 6; c.lineCap = 'round'; c.lineJoin = 'round'; c.strokeStyle = colors[w.line % colors.length]; c.shadowColor = c.strokeStyle; c.shadowBlur = 14; c.globalAlpha = 0.9;
      c.beginPath(); w.rows.forEach((row, col) => { const [x, y] = cellCenter(row, col); col ? c.lineTo(x, y) : c.moveTo(x - reelW / 2, y); }); const [lx, ly] = cellCenter(w.rows[R - 1], R - 1); c.lineTo(lx + reelW / 2, ly); c.stroke(); c.restore(); });
  }
  function meter(c, x, w, label, value, hot) {
    const y = 0, h = height - metersY - pad * 0.6;
    c.save(); c.fillStyle = 'rgba(0,0,0,0.55)'; c.beginPath(); c.roundRect(x, y, w, h * 0.62, 12); c.fill(); c.lineWidth = 2; c.strokeStyle = hot ? '#ffd34d' : 'rgba(255,211,77,0.45)'; c.stroke();
    c.textAlign = 'center'; c.textBaseline = 'middle'; c.direction = 'rtl'; c.fillStyle = '#d8b4ff'; c.font = `700 ${h * 0.14}px ${font}`; c.fillText(label, x + w / 2, y + h * 0.16);
    c.direction = 'ltr'; c.font = `800 ${h * 0.24}px ${font}`; c.fillStyle = hot ? '#fff2a8' : '#ffffff'; if (hot) { c.shadowColor = '#ffb400'; c.shadowBlur = 14; }
    c.fillText(fmt(value), x + w / 2, y + h * 0.42); c.restore();
  }
  function drawMeters(c, winVal) {
    const w3 = (width - pad * 2 - 2 * 12) / 3;
    // RTL order: credit on the right, bet in the middle, win on the left
    meter(c, pad + 2 * (w3 + 12), w3, L.credit, hud.credits);
    meter(c, pad + (w3 + 12), w3, L.bet, hud.bet);
    meter(c, pad, w3, L.win, winVal, winVal > 0);
    const msg = hud.message || '';
    if (msg) { c.save(); c.textAlign = 'center'; c.direction = 'rtl'; c.font = `700 ${(height - metersY) * 0.1}px ${font}`; c.fillStyle = '#ffd6f5'; c.fillText(msg, width / 2, height - metersY - pad * 1.1); c.restore(); }
  }
  function drawOverlay() {
    const o = overlay, k = Math.min(1, o.t / 0.35), s = ease.outBack(k, 1.6);
    c.save(); c.fillStyle = `rgba(10,0,25,${0.72 * k})`; c.fillRect(0, 0, width, height);
    c.translate(width / 2, top + reelsH * 0.45); c.scale(s, s); c.textAlign = 'center'; c.textBaseline = 'middle'; c.direction = 'rtl';
    const title = o.kind === 'fsIntro' ? L.fsIntro : o.kind === 'fsOutro' ? L.fsOutro : o.title;
    c.font = `900 ${width * (o.kind === 'fsOutro' ? 0.075 : 0.11)}px ${font}`; c.shadowColor = '#ff8a00'; c.shadowBlur = 30;
    c.lineWidth = 10; c.strokeStyle = '#4a0a00'; c.strokeText(title, 0, -width * 0.12); c.fillStyle = grad(c, 0, -width * 0.2, 0, -width * 0.05, GOLD); c.fillText(title, 0, -width * 0.12);
    c.direction = 'ltr'; c.font = `900 ${width * 0.16}px ${font}`;
    const amount = o.kind === 'fsIntro' ? `${o.amount}` : fmt(o.kind === 'big' ? Math.floor(winShown) : o.amount);
    c.strokeText(amount, 0, width * 0.07); c.fillStyle = '#fff7cc'; c.fillText(amount, 0, width * 0.07);
    c.shadowBlur = 0; c.direction = 'rtl'; c.font = `700 ${width * 0.05}px ${font}`; c.fillStyle = '#ffd1f0';
    c.fillText(o.kind === 'fsIntro' ? `${L.freeSpins} · ${L.fsMult}` : L.playMoney, 0, width * 0.2);
    c.restore();
  }
  function drawParticles() { particles.forEach((p) => { c.save(); c.translate(p.x, p.y); c.rotate(p.a); c.scale(Math.cos(p.a * 2), 1); c.fillStyle = grad(c, -p.r, 0, p.r, 0, GOLD); c.beginPath(); c.arc(0, 0, p.r, 0, TAU); c.fill(); c.restore(); }); }
  function fmt(n) { return Math.round(n).toLocaleString('en-US'); }

  function visibleGrid() { return Array.from({ length: ROWS }, (_, row) => reels.map((r) => r.strip[(((Math.round(r.pos) + row) % r.n) + r.n) % r.n])); }
  function setHud(h) { hud = { ...hud, ...h }; dirty = true; }
  draw();
  return { canvas, startSpin, slamStop, update, visibleGrid, setHud, get busy() { return busy; }, get phase() { return phase; }, get overlay() { return overlay?.kind || null; }, layout: { pad, top, reelsH, reelW, cellH, metersY } };
}
