// ZOHAR European roulette table, real size and laid out like a casino table (see shared/layout.js → roul):
// wheel sunk in a wooden well at the left end, croupier beside it, layout along the table with zero by the wheel, racetrack.
// Exports separate parts so the app can animate them: the table (with the wheel bowl), the rotor, the ball, the dolly.
import { L } from './shared/layout.js';
import { CHIPS, chipMesh } from './chips.js';
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js';

export const ORDER = [0, 32, 15, 19, 4, 21, 2, 25, 17, 34, 6, 27, 13, 36, 11, 30, 8, 23, 10, 5, 24, 16, 33, 1, 20, 14, 31, 9, 22, 18, 29, 7, 28, 12, 35, 3, 26];
const REDS = new Set([1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36]);
const RED = '#b0141e', BLACK = '#121214', GREEN = '#0d7a3e';
const colorOf = (n) => (n === 0 ? GREEN : REDS.has(n) ? RED : BLACK);
const R = L.roul, G = R.grid;
const T = R.table, XL = T.xl, XR = T.xr, ZB = T.zb, ZF = T.zf, CR = T.corner, RAIL = T.rail, WELL = T.well;
const RC = R.race;

// ---------------- racetrack geometry (the app classifies taps with the same maths) ----------------
// a stadium round the segment (cx ± hl, cz); numbers run clockwise seen from above, 0 at the right end
const RM = (RC.rOut + RC.rIn) / 2;
const RACE_LEN = 4 * RC.hl + 2 * Math.PI * RM;
export function raceParam(x, z) {
  const xa = RC.cx - RC.hl, xb = RC.cx + RC.hl, q = Math.PI * RM / 2;
  if (x > xb) { const th = Math.atan2(z - RC.cz, x - xb); return th >= 0 ? th * RM : RACE_LEN + th * RM; }
  if (x < xa) { let th = Math.atan2(z - RC.cz, x - xa); if (th < 0) th += 2 * Math.PI; return q + 2 * RC.hl + (th - Math.PI / 2) * RM; }
  return z > RC.cz ? q + (xb - x) : q + 2 * RC.hl + Math.PI * RM + (x - xa);
}
export function raceDist(x, z) { const qx = Math.min(Math.max(x, RC.cx - RC.hl), RC.cx + RC.hl); return Math.hypot(x - qx, z - RC.cz); }
const raceIndex = (s) => ((Math.floor(s / RACE_LEN * 37 + 0.5) % 37) + 37) % 37;
// the point at path length s, at distance r from the centre line
function racePoint(s, r) {
  const xa = RC.cx - RC.hl, xb = RC.cx + RC.hl, q = Math.PI * RM / 2;
  s = ((s % RACE_LEN) + RACE_LEN) % RACE_LEN;
  if (s < q) { const th = s / RM; return [xb + Math.cos(th) * r, RC.cz + Math.sin(th) * r]; }
  if (s < q + 2 * RC.hl) return [xb - (s - q), RC.cz + r];
  if (s < q + 2 * RC.hl + Math.PI * RM) { const th = Math.PI / 2 + (s - q - 2 * RC.hl) / RM; return [xa + Math.cos(th) * r, RC.cz + Math.sin(th) * r]; }
  if (s < q + 4 * RC.hl + Math.PI * RM) return [xa + (s - q - 2 * RC.hl - Math.PI * RM), RC.cz - r];
  const th = -Math.PI / 2 + (s - q - 4 * RC.hl - Math.PI * RM) / RM; return [xb + Math.cos(th) * r, RC.cz + Math.sin(th) * r];
}
const ZERO_GAME = new Set([12, 35, 3, 26, 0, 32, 15]);
const VOISINS = new Set([22, 18, 29, 7, 28, 12, 35, 3, 26, 0, 32, 15, 19, 4, 21, 2, 25]);
const TIERS = new Set([27, 13, 36, 11, 30, 8, 23, 10, 5, 24, 16, 33]);
const sectorOf = (n) => (ZERO_GAME.has(n) ? 0 : VOISINS.has(n) ? 1 : TIERS.has(n) ? 3 : 2);   // 0 zero, 1 voisins, 2 orphelins, 3 tiers

// table outline in (x, −z) shape space; o = outward offset; from x0 (left) to the right end, rounded front corners
function outline(THREE, o, x0 = XL, x1 = XR, roundLeft = true, roundRight = true, close = true) {
  const s = new THREE.Shape(); const l = x0 - o, r = x1 + o, f = ZF + o, c = CR + o;
  s.moveTo(l, -ZB);
  if (roundLeft) { s.lineTo(l, -(f - c)); s.absarc(l + c, -(f - c), c, Math.PI, Math.PI * 1.5, false); } else s.lineTo(l, -f);
  if (roundRight) { s.lineTo(r - c, -f); s.absarc(r - c, -(f - c), c, Math.PI * 1.5, Math.PI * 2, false); } else s.lineTo(r, -f);
  s.lineTo(r, -ZB); if (close) s.lineTo(l, -ZB);
  return s;
}

// ---------------- the felt: layout + racetrack ----------------
function feltTexture(kit) {
  const X0 = WELL, X1 = XR, SPAN = X1 - X0, Z0 = ZB, ZSPAN = ZF - ZB;
  const FW = 2560, FH = Math.round(FW * ZSPAN / SPAN);
  const S = FW / SPAN, px = (x) => (x - X0) * S, py = (z) => (z - Z0) * S;
  const tex = kit.canvasTexture(FW, FH, (c, w, h) => {
    c.fillStyle = '#0b5132'; c.fillRect(0, 0, w, h);
    const rg = c.createRadialGradient(px(0.1), py(0.6), 80, px(0.1), py(0.6), 1700); rg.addColorStop(0, 'rgba(50,160,100,0.26)'); rg.addColorStop(0.65, 'rgba(0,0,0,0)'); rg.addColorStop(1, 'rgba(0,10,5,0.45)');
    c.fillStyle = rg; c.fillRect(0, 0, w, h);
    let seed = 11; const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
    for (let i = 0; i < 190000; i++) { const x = rnd() * w, y = rnd() * h, a = rnd() * 0.05; c.fillStyle = rnd() > 0.5 ? `rgba(255,255,255,${a})` : `rgba(0,0,0,${a * 1.5})`; c.fillRect(x, y, 1 + rnd() * 2.5, 1); }
    const LINE = '#f1e8cf', gold = '#e9c46a', ink = 'rgba(245,236,210,0.95)';
    const REDINK = '#d8202c', BLACKINK = '#0c0c0e';
    const digit = (n, x, y, size, rot = 0) => {
      c.save(); c.translate(x, y); c.rotate(rot); c.font = `900 ${size}px Georgia, serif`; c.textAlign = 'center'; c.textBaseline = 'middle';
      c.lineJoin = 'round'; c.lineWidth = Math.max(3, size * 0.09); c.strokeStyle = n === 0 ? 'rgba(4,40,20,0.9)' : 'rgba(245,236,210,0.9)'; c.strokeText(String(n), 0, 2);
      c.fillStyle = n === 0 ? '#ffffff' : REDS.has(n) ? REDINK : BLACKINK; c.fillText(String(n), 0, 2); c.restore();
    };
    // ---- the betting layout: zero by the wheel, numbers along the table, 2 to 1 at the far end ----
    const X = (u) => px(G.gx0 + u * G.cw), Z = (v) => py(G.gz0 + v * G.ch);
    const cw = G.cw * S, ch = G.ch * S;
    // zero: green, pointed toward the wheel
    { const x0 = X(-1), x1 = X(0), y0 = Z(0), y1 = Z(3);
      c.save(); c.beginPath(); c.moveTo(x1, y0); c.lineTo(x0 + cw * 0.45, y0); c.lineTo(x0, (y0 + y1) / 2); c.lineTo(x0 + cw * 0.45, y1); c.lineTo(x1, y1); c.closePath();
      c.fillStyle = 'rgba(30,150,80,0.55)'; c.fill(); c.strokeStyle = LINE; c.lineWidth = 6; c.stroke(); c.restore();
      digit(0, (x0 + x1) / 2 + 6, (y0 + y1) / 2, 64); }
    for (let col = 0; col < 12; col++) for (let row = 0; row < 3; row++) { const n = 3 * col + (3 - row); digit(n, X(col + 0.5), Z(row + 0.5), 54); }
    c.strokeStyle = LINE; c.lineWidth = 6; c.strokeRect(X(0), Z(0), 13 * cw, 3 * ch);
    c.lineWidth = 4;
    for (let k = 1; k <= 12; k++) { c.beginPath(); c.moveTo(X(k), Z(0)); c.lineTo(X(k), Z(3)); c.stroke(); }
    for (let k = 1; k < 3; k++) { c.beginPath(); c.moveTo(X(0), Z(k)); c.lineTo(X(13), Z(k)); c.stroke(); }
    c.save(); c.fillStyle = ink; c.font = '700 26px Georgia, serif'; c.textAlign = 'center'; c.textBaseline = 'middle';
    for (let row = 0; row < 3; row++) { c.save(); c.translate(X(12.5), Z(row + 0.5)); c.rotate(-Math.PI / 2); c.fillText('2 to 1', 0, 0); c.restore(); }
    c.restore();
    const dz0 = Z(3), dz1 = py(G.gz0 + 3 * G.ch + G.dh), ez1 = py(G.gz0 + 3 * G.ch + G.dh + G.eh);
    c.lineWidth = 5; c.strokeRect(X(0), dz0, 12 * cw, dz1 - dz0); c.strokeRect(X(0), dz1, 12 * cw, ez1 - dz1); c.lineWidth = 4;
    for (let k = 1; k < 3; k++) { c.beginPath(); c.moveTo(X(4 * k), dz0); c.lineTo(X(4 * k), dz1); c.stroke(); }
    for (let k = 1; k < 6; k++) { c.beginPath(); c.moveTo(X(2 * k), dz1); c.lineTo(X(2 * k), ez1); c.stroke(); }
    c.save(); c.fillStyle = ink; c.textAlign = 'center'; c.textBaseline = 'middle'; c.shadowColor = 'rgba(0,0,0,0.45)'; c.shadowBlur = 3;
    c.font = '700 40px Georgia, serif'; ['1st 12', '2nd 12', '3rd 12'].forEach((t, k) => c.fillText(t, X(4 * k + 2), (dz0 + dz1) / 2 + 2));
    c.font = '700 33px Georgia, serif'; ['1 to 18', 'EVEN', null, null, 'ODD', '19 to 36'].forEach((t, k) => { if (t) c.fillText(t, X(2 * k + 1), (dz1 + ez1) / 2 + 2); });
    c.restore();
    for (const [k, f] of [[2, ['#e0303c', '#9a111b']], [3, ['#2a2a30', '#050506']]]) {
      const cx = X(2 * k + 1), cy = (dz1 + ez1) / 2, dw = cw * 0.66, dh = (ez1 - dz1) * 0.34;
      c.save(); c.beginPath(); c.moveTo(cx - dw, cy); c.lineTo(cx, cy - dh); c.lineTo(cx + dw, cy); c.lineTo(cx, cy + dh); c.closePath();
      const lg = c.createLinearGradient(0, cy - dh, 0, cy + dh); lg.addColorStop(0, f[0]); lg.addColorStop(1, f[1]); c.fillStyle = lg; c.fill(); c.strokeStyle = LINE; c.lineWidth = 3; c.stroke(); c.restore();
    }

    // ---- the racetrack ----
    { const bx0 = Math.floor(px(RC.cx - RC.hl - RC.rOut)) - 2, by0 = Math.floor(py(RC.cz - RC.rOut)) - 2, bw = Math.ceil((2 * RC.hl + 2 * RC.rOut) * S) + 4, bh = Math.ceil(2 * RC.rOut * S) + 4;
      // inner sectors: every pixel classified with the same rule the app uses for taps, then tinted and outlined
      const img = c.getImageData(bx0, by0, bw, bh); const sec = new Int8Array(bw * bh).fill(-1);
      const tint = [[200, 170, 60, 0.20], [30, 90, 60, 0.0], [20, 60, 40, 0.18], [0, 40, 25, 0.28]];
      const cent = [[], [], [], []].map(() => ({ top: [0, 0, 0], bot: [0, 0, 0] }));
      for (let j = 0; j < bh; j++) for (let i = 0; i < bw; i++) {
        const x = (bx0 + i) / S + X0, z = (by0 + j) / S + Z0; const d = raceDist(x, z); if (d >= RC.rIn) continue;
        const k = sectorOf(ORDER[raceIndex(raceParam(x, z))]); sec[j * bw + i] = k;
        const t = tint[k], o = (j * bw + i) * 4; for (let q = 0; q < 3; q++) img.data[o + q] = img.data[o + q] * (1 - t[3]) + t[q] * t[3];
        const cc = z < RC.cz ? cent[k].top : cent[k].bot; cc[0] += x; cc[1] += z; cc[2]++;
      }
      for (let j = 1; j < bh; j++) for (let i = 1; i < bw; i++) {
        const a = sec[j * bw + i]; if (a < 0) continue;
        if ((sec[j * bw + i - 1] >= 0 && sec[j * bw + i - 1] !== a) || (sec[(j - 1) * bw + i] >= 0 && sec[(j - 1) * bw + i] !== a)) {
          for (const [di, dj] of [[0, 0], [1, 0], [0, 1], [-1, 0], [0, -1]]) { const o = ((j + dj) * bw + i + di) * 4; img.data[o] = 241; img.data[o + 1] = 232; img.data[o + 2] = 207; }
        }
      }
      c.putImageData(img, bx0, by0);
      // outlines of the ring and the cells
      const path = (r) => { c.beginPath(); for (let k = 0; k <= 240; k++) { const [x, z] = racePoint(k / 240 * RACE_LEN, r); k ? c.lineTo(px(x), py(z)) : c.moveTo(px(x), py(z)); } c.closePath(); };
      c.strokeStyle = LINE; c.lineWidth = 6; path(RC.rOut); c.stroke(); c.lineWidth = 5; path(RC.rIn); c.stroke();
      c.lineWidth = 3;
      for (let i = 0; i < 37; i++) {
        const sb = (i + 0.5) / 37 * RACE_LEN; const [x0, z0] = racePoint(sb, RC.rIn), [x1, z1] = racePoint(sb, RC.rOut);
        c.beginPath(); c.moveTo(px(x0), py(z0)); c.lineTo(px(x1), py(z1)); c.stroke();
        const [nx, nz] = racePoint(i / 37 * RACE_LEN, RM); const n = ORDER[i];
        if (n === 0) {   // the zero cell is green
          c.save(); c.beginPath(); for (let k = 0; k <= 12; k++) { const [x, z] = racePoint((i - 0.5 + k / 12) / 37 * RACE_LEN, RC.rOut - 0.002); k ? c.lineTo(px(x), py(z)) : c.moveTo(px(x), py(z)); }
          for (let k = 12; k >= 0; k--) { const [x, z] = racePoint((i - 0.5 + k / 12) / 37 * RACE_LEN, RC.rIn + 0.002); c.lineTo(px(x), py(z)); } c.closePath(); c.fillStyle = 'rgba(30,150,80,0.7)'; c.fill(); c.restore();
        }
        digit(n, px(nx), py(nz), 40);
      }
      // sector names
      const names = ['ZERO', 'VOISINS DU ZERO', 'ORPHELINS', 'TIER'];
      c.save(); c.fillStyle = ink; c.textAlign = 'center'; c.textBaseline = 'middle'; c.shadowColor = 'rgba(0,0,0,0.5)'; c.shadowBlur = 3;
      cent.forEach((cc, k) => {
        const parts = [cc.top, cc.bot].filter((p) => p[2] > 400);
        const all = [parts.reduce((a, p) => a + p[0], 0), parts.reduce((a, p) => a + p[1], 0), parts.reduce((a, p) => a + p[2], 0)];
        const far = parts.length === 2 && Math.abs(parts[0][0] / parts[0][2] - parts[1][0] / parts[1][2]) > 0.15;
        for (const p of far ? parts : [all]) {
          if (!p[2]) continue; const x = p[0] / p[2], z = p[1] / p[2];
          c.font = `700 ${k === 1 ? 22 : 24}px Georgia, serif`;
          if (k === 1) { c.fillText('VOISINS', px(x), py(z) - 12); c.font = '600 16px Georgia, serif'; c.fillText('DU ZERO', px(x), py(z) + 12); }
          else c.fillText(names[k], px(x), py(z));
        }
      });
      c.restore();
    }

    // ---- printed information on the right end ----
    const label = (x, z, text, font, color, align = 'center') => { c.save(); c.fillStyle = color; c.font = font; c.textAlign = align; c.textBaseline = 'middle'; c.shadowColor = 'rgba(0,0,0,0.45)'; c.shadowBlur = 3; c.fillText(text, px(x), py(z)); c.restore(); };
    const gGrad = (y0, y1) => { const lg = c.createLinearGradient(0, y0, 0, y1); lg.addColorStop(0, '#fff1b8'); lg.addColorStop(0.5, '#e9c46a'); lg.addColorStop(1, '#b8892c'); return lg; };
    label(0.92, 0.56, 'ROULETTE', '700 62px Georgia, serif', gGrad(py(0.56) - 32, py(0.56) + 32));
    label(0.92, 0.615, 'EUROPEAN  ·  SINGLE ZERO', '600 21px Georgia, serif', ink);
    { const bx = 0.92, bz = 0.79, bw = 0.42, rows = [['Straight up', '35 to 1'], ['Split', '17 to 1'], ['Street', '11 to 1'], ['Corner', '8 to 1'], ['Six line', '5 to 1'], ['Dozen · Column', '2 to 1'], ['Even chances', '1 to 1']];
      const bh = 0.035 + rows.length * 0.024;
      c.save(); c.globalAlpha = 0.92; c.fillStyle = 'rgba(0,0,0,0.18)'; c.strokeStyle = 'rgba(241,232,207,0.6)'; c.lineWidth = 2; c.beginPath(); c.roundRect(px(bx - bw / 2), py(bz - bh / 2), bw * S, bh * S, 14); c.fill(); c.stroke(); c.restore();
      rows.forEach(([n, p], i) => { const z = bz - bh / 2 + 0.03 + i * 0.024; label(bx - bw / 2 + 0.02, z, n, '600 21px Arial', ink, 'left'); label(bx + bw / 2 - 0.02, z, p, '700 21px Arial', gold, 'right'); });
    }
    // crown + ZOHAR in the free felt by the dealer
    c.save(); c.translate(px(-0.12), py(0.30)); c.scale(0.8, 0.8); c.globalAlpha = 0.42;
    c.fillStyle = gGrad(-60, 30); c.beginPath(); const k = 46; c.moveTo(-k, -18); c.lineTo(-k, -52); c.lineTo(-k / 2, -34); c.lineTo(0, -62); c.lineTo(k / 2, -34); c.lineTo(k, -52); c.lineTo(k, -18); c.closePath(); c.fill();
    c.font = '900 62px Georgia, serif'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText('ZOHAR', 0, 26); c.restore();
  });
  tex.anisotropy = 8;
  return { tex, X0, SPAN, Z0, ZSPAN };
}

// ---------------- shared materials ----------------
function materials(THREE, kit) {
  const leatherTex = kit.canvasTexture(1024, 256, (c, w, h) => {
    c.fillStyle = '#141113'; c.fillRect(0, 0, w, h);
    let seed = 3; const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
    for (let i = 0; i < 40000; i++) { const x = rnd() * w, y = rnd() * h, r = 0.5 + rnd() * 1.4; c.fillStyle = rnd() > 0.5 ? 'rgba(255,240,220,0.03)' : 'rgba(0,0,0,0.18)'; c.beginPath(); c.arc(x, y, r, 0, 7); c.fill(); }
  });
  leatherTex.wrapS = leatherTex.wrapT = THREE.RepeatWrapping; leatherTex.repeat.set(8, 8);
  const woodTex = (a, b, cc) => {
    const t = kit.canvasTexture(1024, 256, (c, w, h) => {
      const lg = c.createLinearGradient(0, 0, 0, h); lg.addColorStop(0, a); lg.addColorStop(0.5, b); lg.addColorStop(1, cc); c.fillStyle = lg; c.fillRect(0, 0, w, h);
      for (let i = 0; i < 140; i++) { const y = (i * 37.7) % h; c.strokeStyle = `rgba(25,10,3,${0.07 + (i % 7) / 55})`; c.lineWidth = 1 + (i % 3); c.beginPath(); c.moveTo(0, y); for (let x = 0; x <= w; x += 24) c.lineTo(x, y + Math.sin(x / 90 + i * 1.7) * 5); c.stroke(); }
    });
    t.wrapS = t.wrapT = THREE.RepeatWrapping; return t;
  };
  const wt = woodTex('#2c160c', '#4a2614', '#22100a'); wt.repeat.set(6, 1);
  const mt = woodTex('#3a1608', '#6a2a12', '#2a0f06'); mt.repeat.set(3, 1);
  return {
    leather: new THREE.MeshPhysicalMaterial({ name: 'Leather', map: leatherTex, color: 0xffffff, roughness: 0.5, clearcoat: 0.3, clearcoatRoughness: 0.5, sheen: 0.4, sheenColor: new THREE.Color(0x3a302a) }),
    wood: new THREE.MeshPhysicalMaterial({ name: 'Wood', map: wt, roughness: 0.32, clearcoat: 1, clearcoatRoughness: 0.12 }),
    mahogany: new THREE.MeshPhysicalMaterial({ name: 'Mahogany', map: mt, roughness: 0.25, clearcoat: 1, clearcoatRoughness: 0.06 }),
    trackWood: new THREE.MeshPhysicalMaterial({ name: 'Track', color: 0x2a140b, roughness: 0.18, clearcoat: 1, clearcoatRoughness: 0.04 }),
    chrome: new THREE.MeshStandardMaterial({ name: 'Chrome', color: 0xe8eaee, roughness: 0.08, metalness: 1 }),
    gold: kit.mat.metal('#d4a94a', 0.2),
    apron: new THREE.MeshPhysicalMaterial({ name: 'Apron', color: 0x0b0a0c, roughness: 0.3, clearcoat: 0.8, clearcoatRoughness: 0.2 }),
    black: kit.mat.metal('#1b1b1e', 0.45),
  };
}

// ---------------- the wheel: static bowl (part of the table) ----------------
function bowl(THREE, kit, M) {
  const outer = kit.group('WheelBowl', { pos: R.wheel }); const g = kit.group('WheelBowlBody'); g.scale.setScalar(R.k); outer.add(g);
  const lathe = (pts, mat, name, seg = 148) => { mat.side = THREE.DoubleSide; const m = new THREE.Mesh(new THREE.LatheGeometry(pts.map(([r, y]) => new THREE.Vector2(r, y)), seg), mat); m.name = name; g.add(m); return m; };
  // cabinet: polished mahogany, from the felt up to the rim, with a gold ring
  lathe([[0.300, 0.034], [0.318, 0.031], [0.330, 0.020], [0.336, 0.0], [0.338, -0.030], [0.334, -0.040]], M.mahogany, 'WheelCabinet');
  lathe([[0.3005, 0.0345], [0.3045, 0.0372], [0.3085, 0.0360], [0.309, 0.0335]].reverse(), M.gold, 'WheelRimGold');
  // the ball track: inner wall, then the lower track sloping to the rotor (lathe profile from the inside out so normals face up/in)
  const track = [];
  track.push([0.196, -0.009], [0.205, -0.006]);
  for (let i = 0; i <= 12; i++) { const r = 0.205 + (0.283 - 0.205) * i / 12; track.push([r, -0.006 + (0.012 + 0.006) * (i / 12) ** 1.15]); }
  // fillet into the wall
  for (let i = 1; i <= 6; i++) { const a = (Math.PI / 2) * i / 6; track.push([0.283 + 0.007 * Math.sin(a), 0.012 + 0.007 * (1 - Math.cos(a))]); }
  track.push([0.290, 0.031], [0.2925, 0.034], [0.3005, 0.0345]);
  lathe(track.map(([r, y]) => [r, y]), M.trackWood, 'WheelTrack');
  // deflectors: 8 chrome diamonds on the lower track, alternating along / across the slope
  const dg = [];
  for (let i = 0; i < 8; i++) {
    const a = i / 8 * Math.PI * 2 + Math.PI / 8, along = i % 2 === 0;
    const geo = new THREE.OctahedronGeometry(1, 0); geo.scale(along ? 0.004 : 0.0105, 0.004, along ? 0.0105 : 0.004);
    const m = new THREE.Matrix4().compose(new THREE.Vector3(Math.sin(a) * 0.24, 0.012 - 0.0045, Math.cos(a) * 0.24), new THREE.Quaternion().setFromEuler(new THREE.Euler(0.21, a, 0, 'YXZ')), new THREE.Vector3(1, 1, 1));
    dg.push(geo.applyMatrix4(m).toNonIndexed());
  }
  const d = new THREE.Mesh(mergeGeometries(dg), M.chrome); d.name = 'WheelDeflectors'; g.add(d);
  // the floor under the rotor (dark), so nothing shows through the gap
  lathe([[0.0, -0.03], [0.197, -0.03], [0.197, -0.009]], M.apron, 'WheelBase', 64);
  return outer;
}

// ---------------- the rotor (turns; origin on the axis) ----------------
export function rotor(THREE, kit) {
  const M = materials(THREE, kit);
  const outer = kit.group('Rotor'); const g = kit.group('RotorBody'); g.scale.setScalar(R.k); outer.add(g);
  const N = 37, step = Math.PI * 2 / N;
  // pocket i is centred at phi = -i·step (clockwise seen from above); lathe u = phi / 2π
  const cellU = (i) => (((-i * step) / (Math.PI * 2)) % 1 + 1) % 1;
  const ringTex = kit.canvasTexture(2048, 96, (c, w, h) => {
    c.fillStyle = '#c9a03c'; c.fillRect(0, 0, w, h);
    for (let i = 0; i < N; i++) for (const off of [-w, 0, w]) {
      const cx = cellU(i) * w + off, cw = w / N, n = ORDER[i];
      c.fillStyle = colorOf(n); c.fillRect(cx - cw / 2 + 1.5, 6, cw - 3, h - 12);
      c.save(); c.translate(cx, h / 2); c.scale(-1, 1); c.fillStyle = '#ffffff'; c.font = `800 ${n > 9 ? 40 : 46}px Georgia, serif`; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText(String(n), 0, 3); c.restore();
    }
  });
  ringTex.anisotropy = 8;
  const pocketTex = kit.canvasTexture(1024, 16, (c, w, h) => { for (let i = 0; i < N; i++) for (const off of [-w, 0, w]) { const cx = cellU(i) * w + off, cw = w / N; c.fillStyle = colorOf(ORDER[i]); c.fillRect(cx - cw / 2, 0, cw, h); } });
  const lathe = (pts, mat, name, seg = 37 * 6) => { mat.side = THREE.DoubleSide; const m = new THREE.Mesh(new THREE.LatheGeometry(pts.map(([r, y]) => new THREE.Vector2(r, y)), seg), mat); m.name = name; g.add(m); return m; };
  const ringMat = new THREE.MeshPhysicalMaterial({ name: 'NumberRing', map: ringTex, roughness: 0.25, clearcoat: 1, clearcoatRoughness: 0.08 });
  const pocketMat = new THREE.MeshPhysicalMaterial({ name: 'Pockets', map: pocketTex, roughness: 0.35, clearcoat: 0.8, clearcoatRoughness: 0.1 });
  // outer chrome edge, number ring (sloping a little toward the pockets), pocket walls and floor
  lathe([[0.1935, -0.022], [0.1945, -0.006], [0.1925, -0.0028], [0.191, -0.0028]].reverse(), M.chrome, 'RotorEdge');
  lathe([[0.163, -0.0062], [0.1912, -0.0029]], ringMat, 'NumberRing');
  lathe([[0.124, -0.002], [0.1236, -0.0118], [0.1245, -0.0122]], M.chrome, 'PocketInnerWall');
  lathe([[0.1245, -0.0122], [0.1625, -0.0122]], pocketMat, 'PocketFloor');
  lathe([[0.1625, -0.0122], [0.1632, -0.0062]], M.chrome, 'PocketOuterWall');
  // frets: thin chrome separators between the pockets
  const fg = [];
  for (let i = 0; i < N; i++) {
    const a = -(i + 0.5) * step;
    const geo = new THREE.BoxGeometry(0.0022, 0.0105, 0.039); geo.translate(0, -0.0065, 0.1433);
    geo.rotateY(a); fg.push(geo.toNonIndexed());
  }
  // little rounded caps on the frets toward the number ring
  const frets = new THREE.Mesh(mergeGeometries(fg), M.chrome); frets.name = 'Frets'; g.add(frets);
  // the cone: polished wood rising to the turret, with a gold ring
  const cone = []; for (let i = 0; i <= 14; i++) { const t = i / 14; cone.push([0.124 - (0.124 - 0.045) * t, -0.002 + 0.034 * (1 - (1 - t) ** 1.6)]); }
  lathe(cone.reverse(), M.mahogany, 'RotorCone', 96);
  lathe([[0.1215, -0.0005], [0.1225, 0.001], [0.1255, 0.0], [0.126, -0.002]], M.gold, 'RotorGold', 96);
  // turret: chrome dome, spindle, four arms with balls
  lathe([[0.0465, 0.032], [0.044, 0.038], [0.034, 0.044], [0.018, 0.048], [0.0085, 0.05], [0.0085, 0.052]].reverse(), M.chrome, 'TurretBase', 64);
  g.add(kit.cylinder(0.0055, 0.0075, 0.06, M.chrome, { name: 'Spindle', pos: [0, 0.08, 0], segments: 32 }));
  g.add(kit.sphere(0.0105, M.chrome, { name: 'TurretKnob', pos: [0, 0.112, 0], segments: 32 }));
  for (let k = 0; k < 4; k++) {
    const a = k * Math.PI / 2; const arm = kit.cylinder(0.0032, 0.0032, 0.052, M.chrome, { name: 'TurretArm', segments: 16 });
    arm.rotation.set(0, a, Math.PI / 2, 'YXZ'); arm.position.set(Math.sin(a) * 0.028, 0.096, Math.cos(a) * 0.028);
    // lathe convention: a cylinder along Y rotated onto the radial direction
    arm.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), new THREE.Vector3(Math.sin(a), 0, Math.cos(a)));
    g.add(arm); g.add(kit.sphere(0.0062, M.chrome, { name: 'TurretBall', pos: [Math.sin(a) * 0.056, 0.096, Math.cos(a) * 0.056], segments: 24 }));
  }
  outer.traverse((o) => { if (o.isMesh) { o.castShadow = true; o.receiveShadow = true; } });
  return outer;
}

export function ball(THREE, kit) {
  const g = kit.group('Ball');
  const m = kit.sphere(R.ballR, new THREE.MeshPhysicalMaterial({ name: 'Ball', color: 0xf7f4ec, roughness: 0.12, clearcoat: 1, clearcoatRoughness: 0.03 }), { name: 'BallMesh', segments: 32 });
  g.add(m); return g;
}

export function dolly(THREE, kit) {
  const g = kit.group('Dolly');
  const glass = new THREE.MeshPhysicalMaterial({ name: 'DollyGlass', color: 0xd8f0ff, roughness: 0.02, metalness: 0, transmission: 0, transparent: true, opacity: 0.55, clearcoat: 1 });
  g.add(kit.lathe([[0.0, 0.0], [0.016, 0.0], [0.0168, 0.002], [0.0165, 0.006], [0.0135, 0.007], [0.0, 0.007]], kit.mat.metal('#d4a94a', 0.18), { name: 'DollyBase', segments: 48 }));
  g.add(kit.lathe([[0.0, 0.007], [0.012, 0.007], [0.0095, 0.02], [0.006, 0.03], [0.0035, 0.038], [0.0, 0.04]], glass, { name: 'DollyTop', segments: 48 }));
  g.add(kit.sphere(0.0055, glass, { name: 'DollyCap', pos: [0, 0.04, 0], segments: 24 }));
  return g;
}

// ---------------- the whole table ----------------
export async function build(THREE, kit) {
  const g = kit.group('RouletteTable');
  const M = materials(THREE, kit);
  const flat = (shape, name, mat, y, uvFn) => {
    const geo = new THREE.ShapeGeometry(shape, 48); const pos = geo.attributes.position; const uv = new Float32Array(pos.count * 2);
    for (let i = 0; i < pos.count; i++) { const [u, v] = uvFn(pos.getX(i), -pos.getY(i)); uv[2 * i] = u; uv[2 * i + 1] = v; }
    geo.setAttribute('uv', new THREE.BufferAttribute(uv, 2)); geo.rotateX(-Math.PI / 2); geo.computeVertexNormals();
    const m = new THREE.Mesh(geo, mat); m.name = name; m.position.y = y; g.add(m); return m;
  };
  // felt: from the wheel well to the right end
  const F = feltTexture(kit);
  const clothN = kit.canvasTexture(256, 256, (c, w, h) => { c.fillStyle = 'rgb(128,128,255)'; c.fillRect(0, 0, w, h); for (let y = 0; y < h; y += 4) for (let x = 0; x < w; x += 4) { const o = ((x + y) / 4) % 2 ? 12 : -12; c.fillStyle = `rgb(${128 + o},${128 - o},245)`; c.fillRect(x, y, 4, 2); } });
  clothN.colorSpace = THREE.NoColorSpace; clothN.wrapS = clothN.wrapT = THREE.RepeatWrapping; clothN.repeat.set(100, 60);
  const felt = new THREE.MeshStandardMaterial({ name: 'Felt', map: F.tex, normalMap: clothN, normalScale: new THREE.Vector2(0.35, 0.35), roughness: 0.96 });
  flat(outline(THREE, 0, WELL, XR, false, true), 'Felt', felt, L.feltY, (x, z) => [(x - F.X0) / F.SPAN, 1 - (z - F.Z0) / F.ZSPAN]);
  // the wheel well: polished wood top round the sunk wheel, a little proud of the felt
  {
    const s = outline(THREE, 0.004, XL, WELL - 0.004, true, false);
    const hole = new THREE.Path(); hole.absarc(R.wheel[0], -R.wheel[2], 0.338 * R.k + 0.004, 0, Math.PI * 2, true); s.holes.push(hole);
    const geo = new THREE.ExtrudeGeometry(s, { depth: 0.012, bevelEnabled: true, bevelThickness: 0.004, bevelSize: 0.004, bevelSegments: 3, curveSegments: 96 });
    geo.rotateX(-Math.PI / 2);
    const p = geo.attributes.position; const uv = new Float32Array(p.count * 2); for (let i = 0; i < p.count; i++) { uv[2 * i] = p.getX(i) * 1.4; uv[2 * i + 1] = p.getZ(i) * 0.35 + p.getY(i); } geo.setAttribute('uv', new THREE.BufferAttribute(uv, 2));
    const m = new THREE.Mesh(geo, M.mahogany); m.name = 'WheelWell'; m.position.y = L.feltY - 0.012; g.add(m);
    // brass strip where the wood meets the felt, and a brass ring round the wheel
    g.add(kit.box(0.008, 0.004, ZF - ZB - 0.02, M.gold, { name: 'WellStrip', pos: [WELL - 0.002, L.feltY + 0.004, (ZF + ZB) / 2 - 0.01] }));
    const ring = new THREE.Mesh(new THREE.TorusGeometry(0.338 * R.k + 0.006, 0.0035, 12, 160), M.gold); ring.rotation.x = Math.PI / 2; ring.position.set(R.wheel[0], L.feltY + 0.0045, R.wheel[2]); ring.name = 'WellRing'; g.add(ring);
    // dark floor of the well under the wheel
    g.add(kit.cylinder(0.338 * R.k + 0.004, 0.338 * R.k + 0.004, 0.004, M.apron, { name: 'WellFloor', pos: [R.wheel[0], L.feltY - 0.075, R.wheel[2]], segments: 96 }));
  }
  // padded leather armrest: left end, players' side, right end (not the dealer's side)
  {
    const s = new THREE.Shape(); const o = RAIL, l = XL - o, r = XR + o, f = ZF + o, c = CR + o, li = XL - 0.012, ri = XR + 0.012, fi = ZF + 0.012, ci = CR + 0.012;
    s.moveTo(l, -ZB); s.lineTo(l, -(f - c)); s.absarc(l + c, -(f - c), c, Math.PI, Math.PI * 1.5, false); s.lineTo(r - c, -f); s.absarc(r - c, -(f - c), c, Math.PI * 1.5, Math.PI * 2, false); s.lineTo(r, -ZB);
    s.lineTo(ri, -ZB); s.lineTo(ri, -(fi - ci)); s.absarc(ri - ci, -(fi - ci), ci, 0, -Math.PI / 2, true); s.lineTo(li + ci, -fi); s.absarc(li + ci, -(fi - ci), ci, -Math.PI / 2, -Math.PI, true); s.lineTo(li, -ZB); s.closePath();
    const bev = 0.017; const geo = new THREE.ExtrudeGeometry(s, { depth: 0.014, bevelEnabled: true, bevelThickness: bev, bevelSize: bev * 0.9, bevelSegments: 8, curveSegments: 40 });
    geo.rotateX(-Math.PI / 2);
    const p = geo.attributes.position; const uv = new Float32Array(p.count * 2); for (let i = 0; i < p.count; i++) { uv[2 * i] = p.getX(i) * 0.6; uv[2 * i + 1] = p.getZ(i) * 0.6 + p.getY(i) * 2; } geo.setAttribute('uv', new THREE.BufferAttribute(uv, 2));
    const m = new THREE.Mesh(geo, M.leather); m.name = 'Rail'; m.position.y = L.feltY + bev - 0.004; g.add(m);
  }
  // wood band, gold line and apron all round, dealer's straight edge
  {
    const band = (o, y0, y1, mat, name) => { const geo = new THREE.ExtrudeGeometry(outline(THREE, o), { depth: y1 - y0, bevelEnabled: true, bevelThickness: 0.004, bevelSize: 0.004, bevelSegments: 3, curveSegments: 40 }); geo.rotateX(-Math.PI / 2); const m = new THREE.Mesh(geo, mat); m.position.y = y0; m.name = name; g.add(m); return m; };
    band(RAIL + 0.004, L.feltY - 0.065, L.feltY - 0.016, M.wood, 'WoodBand');
    band(RAIL + 0.010, L.feltY - 0.071, L.feltY - 0.065, M.gold, 'GoldTrim');
    band(RAIL - 0.04, 0.62, L.feltY - 0.085, M.apron, 'Apron');
  }
  g.add(kit.roundedBox(XR - XL + 2 * RAIL + 0.01, 0.05, 0.07, 0.008, M.wood, { name: 'DealerEdge', pos: [(XL + XR) / 2, L.feltY - 0.024, ZB - 0.03] }));
  g.add(kit.box(XR - WELL, 0.006, 0.004, M.gold, { name: 'BrassStrip', pos: [(WELL + XR) / 2, L.feltY + 0.0005, ZB + 0.002] }));
  for (const x of [XL + 0.25, -0.2, XR - 0.25]) for (const z of [0.12, 0.8]) g.add(kit.cylinder(0.035, 0.03, 0.62, M.black, { name: 'Leg', pos: [x, 0.31, z], segments: 24 }));
  // the wheel bowl, sunk in the well
  g.add(bowl(THREE, kit, M));

  // the dealer's chip float, set into the table in front of the croupier
  {
    const rackW = 0.40, rackD = 0.11, tubes = 8, tubeW = (rackW - 0.024) / tubes;
    const trayMat = new THREE.MeshPhysicalMaterial({ name: 'RackTray', color: 0x0c0b0d, roughness: 0.22, clearcoat: 1, clearcoatRoughness: 0.1 });
    const rack = kit.group('ChipRack', { pos: [R.rack[0], L.feltY - 0.012, R.rack[2]] });
    const sh = new THREE.Shape(); const top = 0.026, bottom = 0.0, rr = tubeW / 2 - 0.0012;
    sh.moveTo(-rackW / 2, bottom); sh.lineTo(-rackW / 2, top); let x = -rackW / 2 + 0.012; sh.lineTo(x, top);
    for (let i = 0; i < tubes; i++) { const cx = x + tubeW / 2; sh.lineTo(cx - rr, top); sh.absarc(cx, top, rr, Math.PI, 0, false); sh.lineTo(x + tubeW, top); x += tubeW; }
    sh.lineTo(rackW / 2, top); sh.lineTo(rackW / 2, bottom); sh.closePath();
    const geo = new THREE.ExtrudeGeometry(sh, { depth: rackD, bevelEnabled: true, bevelThickness: 0.002, bevelSize: 0.002, bevelSegments: 2, curveSegments: 10 }); geo.translate(0, 0, -rackD / 2);
    const tm = new THREE.Mesh(geo, trayMat); tm.name = 'RackTray'; rack.add(tm);
    rack.add(kit.box(rackW + 0.006, 0.004, 0.006, M.gold, { name: 'RackTrim', pos: [0, top + 0.001, rackD / 2 + 0.003] }));
    const order = [0, 1, 1, 2, 2, 3, 1, 0];
    let seed = 5; const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
    const proto = CHIPS.map((ch) => chipMesh(THREE, kit, ch, true)); const per = CHIPS.map(() => ({ rim: [], face: [] }));
    const part = (k, s) => proto[k].children.find((o) => o.name.endsWith(s));
    x = -rackW / 2 + 0.012;
    for (let i = 0; i < tubes; i++) {
      const k = order[i]; const n = 22 + Math.floor(rnd() * 5); const cx = x + tubeW / 2; x += tubeW; const z0 = -n * L.chipH / 2;
      for (let j = 0; j < n; j++) {
        const m = new THREE.Matrix4().compose(new THREE.Vector3(cx, top - rr + L.chipD / 2 + 0.0015, z0 + j * L.chipH), new THREE.Quaternion().setFromEuler(new THREE.Euler(Math.PI / 2, rnd() * Math.PI * 2, 0, 'XZY')), new THREE.Vector3(1, 1, 1));
        per[k].rim.push(part(k, 'Rim').geometry.clone().applyMatrix4(m));
        if (j === 0) per[k].face.push(part(k, 'Bottom').geometry.clone().applyMatrix4(m));
        if (j === n - 1) per[k].face.push(part(k, 'Top').geometry.clone().applyMatrix4(m));
      }
    }
    per.forEach((p, k) => { if (!p.rim.length) return; const r = new THREE.Mesh(mergeGeometries(p.rim), part(k, 'Rim').material); r.name = `RackChips${CHIPS[k].v}`; rack.add(r); const f = new THREE.Mesh(mergeGeometries(p.face), part(k, 'Top').material); f.name = `RackChipFaces${CHIPS[k].v}`; rack.add(f); });
    g.add(rack);
  }

  // the number board (marquee) behind the wheel: the app paints the last numbers onto MarqueeScreen
  {
    const mq = kit.group('Marquee', { pos: R.marquee, rot: [0, 28, 0] });
    mq.add(kit.cylinder(0.012, 0.012, 0.42, M.chrome, { name: 'MarqueePole', pos: [0, 0.21, 0], segments: 24 }));
    mq.add(kit.cylinder(0.07, 0.08, 0.012, M.black, { name: 'MarqueeFoot', pos: [0, 0.006, 0], segments: 40 }));
    mq.add(kit.roundedBox(0.2, 0.32, 0.04, 0.012, M.apron, { name: 'MarqueeBody', pos: [0, 0.55, 0] }));
    const screenTex = kit.canvasTexture(256, 400, (c, w, h) => { c.fillStyle = '#05070a'; c.fillRect(0, 0, w, h); c.fillStyle = '#e9c46a'; c.font = '900 40px Georgia, serif'; c.textAlign = 'center'; c.fillText('ZOHAR', w / 2, 70); c.fillStyle = '#ffffff'; c.font = '700 30px Arial'; c.fillText('ROULETTE', w / 2, 120); });
    const screen = new THREE.Mesh(new THREE.PlaneGeometry(0.17, 0.29), new THREE.MeshBasicMaterial({ name: 'MarqueeScreen', map: screenTex }));
    screen.name = 'MarqueeScreen'; screen.position.set(0, 0.55, 0.0205); mq.add(screen);
    mq.add(kit.box(0.2, 0.004, 0.042, M.gold, { name: 'MarqueeTrim', pos: [0, 0.712, 0] }));
    g.add(mq);
  }
  // limit sign on the right end
  {
    const signTex = kit.canvasTexture(512, 256, (c, w, h) => {
      const lg = c.createLinearGradient(0, 0, 0, h); lg.addColorStop(0, '#16161c'); lg.addColorStop(1, '#050507'); c.fillStyle = lg; c.fillRect(0, 0, w, h);
      c.strokeStyle = '#c9a03c'; c.lineWidth = 6; c.strokeRect(8, 8, w - 16, h - 16);
      c.fillStyle = '#ffd36b'; c.font = '800 56px Arial'; c.textAlign = 'center'; c.fillText('ROULETTE', w / 2, 80);
      c.fillStyle = '#ffffff'; c.font = '700 38px Arial'; c.fillText('MIN 5  ·  MAX 2000', w / 2, 145);
      c.fillStyle = '#7fe0a8'; c.font = '600 30px Arial'; c.fillText('PLAY MONEY', w / 2, 205);
    });
    const sign = kit.group('LimitSign', { pos: R.sign, rot: [0, -20, 0] });
    sign.add(kit.box(0.16, 0.08, 0.008, new THREE.MeshStandardMaterial({ name: 'SignFace', map: signTex, emissive: 0xffffff, emissiveMap: signTex, emissiveIntensity: 0.6, roughness: 0.3 }), { name: 'SignFace', pos: [0, 0.07, 0] }));
    sign.add(kit.box(0.17, 0.09, 0.006, M.black, { name: 'SignFrame', pos: [0, 0.07, -0.006] }));
    sign.add(kit.cylinder(0.006, 0.006, 0.03, M.black, { name: 'SignPost', pos: [0, 0.015, -0.004], segments: 12 }));
    g.add(sign);
  }
  const shadowTex = kit.canvasTexture(256, 256, (c, w, h) => { const rg = c.createRadialGradient(w / 2, h / 2, 10, w / 2, h / 2, w / 2); rg.addColorStop(0, 'rgba(0,0,0,0.55)'); rg.addColorStop(0.6, 'rgba(0,0,0,0.3)'); rg.addColorStop(1, 'rgba(0,0,0,0)'); c.fillStyle = rg; c.fillRect(0, 0, w, h); });
  const shadow = new THREE.Mesh(new THREE.PlaneGeometry(3.4, 1.6), new THREE.MeshBasicMaterial({ name: 'FloorShadow', map: shadowTex, transparent: true, depthWrite: false }));
  shadow.rotation.x = -Math.PI / 2; shadow.position.set((XL + XR) / 2, 0.002, 0.45); shadow.name = 'FloorShadow'; g.add(shadow);
  g.traverse((o) => { if (o.isMesh && o.name !== 'FloorShadow' && o.name !== 'MarqueeScreen') { o.castShadow = true; o.receiveShadow = true; } });
  return g;
}
