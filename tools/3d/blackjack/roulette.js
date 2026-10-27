// ZOHAR European roulette table, real size, in the shared table frame (see shared/layout.js → roul).
// Exports separate parts so the app can animate them: the table (with the wheel bowl), the rotor, the ball, the dolly.
import { L } from './shared/layout.js';
import { CHIPS, chipMesh } from './chips.js';
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js';

export const ORDER = [0, 32, 15, 19, 4, 21, 2, 25, 17, 34, 6, 27, 13, 36, 11, 30, 8, 23, 10, 5, 24, 16, 33, 1, 20, 14, 31, 9, 22, 18, 29, 7, 28, 12, 35, 3, 26];
const REDS = new Set([1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36]);
const RED = '#b0141e', BLACK = '#121214', GREEN = '#0d7a3e';
const colorOf = (n) => (n === 0 ? GREEN : REDS.has(n) ? RED : BLACK);
const R = L.roul, G = R.grid;
const TW = 1.0, ZB = -0.08, ZF = 1.0, CR = 0.26;          // felt half-width, back (dealer) and front edges, front corner radius
const RAIL = 0.11;

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

// table outline in (x, z), front corners rounded; `o` = outward offset
function outline(THREE, o, closedBack = true) {
  const s = new THREE.Shape(); const w = TW + o, f = ZF + o, r = CR + o;
  // shape space: (x, -z) so that rotateX(-90°) maps it back onto the table
  s.moveTo(-w, -ZB); s.lineTo(-w, -(f - r)); s.absarc(-w + r, -(f - r), r, Math.PI, Math.PI * 1.5, false);
  s.lineTo(w - r, -f); s.absarc(w - r, -(f - r), r, Math.PI * 1.5, Math.PI * 2, false); s.lineTo(w, -ZB);
  if (closedBack) s.lineTo(-w, -ZB);
  return s;
}

// ---------------- the felt ----------------
function feltTexture(kit) {
  const FW = 2560, FH = 1408, X0 = -TW, SPAN = 2 * TW, Z0 = ZB, ZSPAN = ZF - ZB;
  const px = (x) => (x - X0) / SPAN * FW, py = (z) => (z - Z0) / ZSPAN * FH, S = FW / SPAN;
  const tex = kit.canvasTexture(FW, FH, (c, w, h) => {
    c.fillStyle = '#0a4a2f'; c.fillRect(0, 0, w, h);
    const rg = c.createRadialGradient(px(0), py(0.55), 80, px(0), py(0.55), 1500); rg.addColorStop(0, 'rgba(40,150,95,0.28)'); rg.addColorStop(0.6, 'rgba(0,0,0,0)'); rg.addColorStop(1, 'rgba(0,10,5,0.5)');
    c.fillStyle = rg; c.fillRect(0, 0, w, h);
    let seed = 11; const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
    for (let i = 0; i < 170000; i++) { const x = rnd() * w, y = rnd() * h, a = rnd() * 0.05; c.fillStyle = rnd() > 0.5 ? `rgba(255,255,255,${a})` : `rgba(0,0,0,${a * 1.5})`; c.fillRect(x, y, 1 + rnd() * 2.5, 1); }
    const gold = '#e9c46a', goldDim = 'rgba(233,196,106,0.5)', ink = 'rgba(245,233,200,0.92)';
    const gGrad = (y0, y1) => { const lg = c.createLinearGradient(0, y0, 0, y1); lg.addColorStop(0, '#fff1b8'); lg.addColorStop(0.5, '#e9c46a'); lg.addColorStop(1, '#b8892c'); return lg; };
    // framing line along the rail
    c.save(); c.strokeStyle = goldDim; c.lineWidth = 3; c.beginPath();
    const m = 0.03; c.moveTo(px(-TW + m), py(ZB + 0.02)); c.lineTo(px(-TW + m), py(ZF - CR)); c.arc(px(-TW + CR), py(ZF - CR), (CR - m) * S, Math.PI, Math.PI / 2, true);
    c.lineTo(px(TW - CR), py(ZF - m)); c.arc(px(TW - CR), py(ZF - CR), (CR - m) * S, Math.PI / 2, 0, true); c.lineTo(px(TW - m), py(ZB + 0.02)); c.stroke(); c.restore();
    // soft shadow under the wheel cabinet
    { const sg = c.createRadialGradient(px(R.wheel[0]), py(R.wheel[2]), 0.28 * S, px(R.wheel[0]), py(R.wheel[2]), 0.38 * S); sg.addColorStop(0, 'rgba(0,0,0,0.55)'); sg.addColorStop(1, 'rgba(0,0,0,0)'); c.fillStyle = sg; c.fillRect(0, 0, w, h); }

    // ---- the betting layout ----
    const X = (u) => px(G.gx0 + u * G.cw), Z = (v) => py(G.gz0 + v * G.ch);
    const cellW = G.cw * S, cellH = G.ch * S;
    const numFont = (size) => `800 ${size}px Georgia, serif`;
    c.lineJoin = 'round';
    // number cells
    for (let col = 0; col < 12; col++) for (let row = 0; row < 3; row++) {
      const n = 3 * col + (3 - row), x0 = X(col), y0 = Z(row);
      const fill = REDS.has(n) ? ['#d0202c', '#8e0f18'] : ['#2a2a30', '#0b0b0e'];
      const lg = c.createLinearGradient(0, y0, 0, y0 + cellH); lg.addColorStop(0, fill[0]); lg.addColorStop(1, fill[1]);
      c.fillStyle = lg; c.fillRect(x0 + 4, y0 + 4, cellW - 8, cellH - 8);
      c.fillStyle = 'rgba(255,255,255,0.07)'; c.fillRect(x0 + 4, y0 + 4, cellW - 8, (cellH - 8) * 0.4);
      c.save(); c.fillStyle = '#ffffff'; c.font = numFont(46); c.textAlign = 'center'; c.textBaseline = 'middle'; c.shadowColor = 'rgba(0,0,0,0.6)'; c.shadowBlur = 4; c.shadowOffsetY = 2;
      c.fillText(String(n), x0 + cellW / 2, y0 + cellH / 2 + 2); c.restore();
    }
    // zero: a pointed green cell on the left
    { const x0 = X(-1), x1 = X(0), y0 = Z(0), y1 = Z(3);
      c.save(); c.beginPath(); c.moveTo(x1 - 4, y0 + 4); c.lineTo(x0 + cellW * 0.42, y0 + 4); c.lineTo(x0 + 6, (y0 + y1) / 2); c.lineTo(x0 + cellW * 0.42, y1 - 4); c.lineTo(x1 - 4, y1 - 4); c.closePath();
      const lg = c.createLinearGradient(0, y0, 0, y1); lg.addColorStop(0, '#16a457'); lg.addColorStop(1, '#0a5c2f'); c.fillStyle = lg; c.fill(); c.strokeStyle = gold; c.lineWidth = 5; c.stroke();
      c.fillStyle = '#ffffff'; c.font = numFont(54); c.textAlign = 'center'; c.textBaseline = 'middle'; c.shadowColor = 'rgba(0,0,0,0.6)'; c.shadowBlur = 4; c.fillText('0', (x0 + x1) / 2 + 8, (y0 + y1) / 2); c.restore(); }
    // gold grid lines
    c.strokeStyle = gold; c.lineWidth = 5;
    c.strokeRect(X(0), Z(0), 13 * cellW, 3 * cellH);
    for (let k = 1; k <= 12; k++) { c.beginPath(); c.moveTo(X(k), Z(0)); c.lineTo(X(k), Z(3)); c.stroke(); }
    for (let k = 1; k < 3; k++) { c.beginPath(); c.moveTo(X(0), Z(k)); c.lineTo(X(13), Z(k)); c.stroke(); }
    // 2 to 1
    c.save(); c.fillStyle = gold; c.font = '800 26px Georgia, serif'; c.textAlign = 'center'; c.textBaseline = 'middle';
    for (let row = 0; row < 3; row++) { c.save(); c.translate(X(12.5), Z(row + 0.5)); c.fillText('2 to 1', 0, 0); c.restore(); }
    c.restore();
    // dozens + even chances
    const dz0 = py(G.gz0 + 3 * G.ch), dz1 = py(G.gz0 + 3 * G.ch + G.dh), ez1 = py(G.gz0 + 3 * G.ch + G.dh + G.eh);
    c.strokeRect(X(0), dz0, 12 * cellW, dz1 - dz0); c.strokeRect(X(0), dz1, 12 * cellW, ez1 - dz1);
    for (let k = 1; k < 3; k++) { c.beginPath(); c.moveTo(X(4 * k), dz0); c.lineTo(X(4 * k), dz1); c.stroke(); }
    for (let k = 1; k < 6; k++) { c.beginPath(); c.moveTo(X(2 * k), dz1); c.lineTo(X(2 * k), ez1); c.stroke(); }
    c.save(); c.fillStyle = ink; c.textAlign = 'center'; c.textBaseline = 'middle'; c.shadowColor = 'rgba(0,0,0,0.5)'; c.shadowBlur = 3;
    c.font = '700 40px Georgia, serif'; ['1st 12', '2nd 12', '3rd 12'].forEach((t, k) => c.fillText(t, X(4 * k + 2), (dz0 + dz1) / 2 + 2));
    c.font = '700 34px Georgia, serif';
    ['1 – 18', 'EVEN', null, null, 'ODD', '19 – 36'].forEach((t, k) => { if (t) c.fillText(t, X(2 * k + 1), (dz1 + ez1) / 2 + 2); });
    c.restore();
    // red and black diamonds
    for (const [k, f] of [[2, ['#e0303c', '#9a111b']], [3, ['#33333a', '#0a0a0c']]]) {
      const cx = X(2 * k + 1), cy = (dz1 + ez1) / 2, dw = cellW * 0.62, dh = (ez1 - dz1) * 0.36;
      c.save(); c.beginPath(); c.moveTo(cx - dw, cy); c.lineTo(cx, cy - dh); c.lineTo(cx + dw, cy); c.lineTo(cx, cy + dh); c.closePath();
      const lg = c.createLinearGradient(0, cy - dh, 0, cy + dh); lg.addColorStop(0, f[0]); lg.addColorStop(1, f[1]); c.fillStyle = lg; c.fill(); c.strokeStyle = gold; c.lineWidth = 3; c.stroke(); c.restore();
    }

    // ---- printed information ----
    const label = (x, z, text, font, color, align = 'center') => { c.save(); c.fillStyle = color; c.font = font; c.textAlign = align; c.textBaseline = 'middle'; c.shadowColor = 'rgba(0,0,0,0.45)'; c.shadowBlur = 3; c.fillText(text, px(x), py(z)); c.restore(); };
    // pay table on the left, title on the right of the wheel
    { const bx = -0.62, bz = 0.42, bw = 0.34, rows = [['Straight up', '35 to 1'], ['Split', '17 to 1'], ['Street', '11 to 1'], ['Corner', '8 to 1'], ['Six line', '5 to 1'], ['Dozen · Column', '2 to 1'], ['Red · Black · Even · Odd · 1-18 · 19-36', '1 to 1']];
      const bh = 0.05 + rows.length * 0.026;
      c.save(); c.globalAlpha = 0.92; c.fillStyle = 'rgba(0,0,0,0.2)'; c.strokeStyle = goldDim; c.lineWidth = 2; c.beginPath(); c.roundRect(px(bx - bw / 2), py(bz - bh / 2), bw * S, bh * S, 14); c.fill(); c.stroke(); c.restore();
      label(bx, bz - bh / 2 + 0.024, 'PAYS', '800 26px Georgia, serif', gold);
      rows.forEach(([n, p], i) => { const z = bz - bh / 2 + 0.055 + i * 0.026; label(bx - bw / 2 + 0.018, z, n, '600 18px Arial', ink, 'left'); label(bx + bw / 2 - 0.018, z, p, '700 18px Arial', gold, 'right'); });
    }
    label(0.62, 0.36, 'ROULETTE', '700 70px Georgia, serif', gGrad(py(0.36) - 35, py(0.36) + 35));
    label(0.62, 0.43, 'EUROPEAN  ·  SINGLE ZERO', '600 24px Georgia, serif', ink);
    // crown + ZOHAR
    c.save(); c.translate(px(0.62), py(0.52)); c.scale(0.7, 0.7); c.globalAlpha = 0.5;
    c.fillStyle = gGrad(-60, 30); c.beginPath(); const cw = 46; c.moveTo(-cw, -18); c.lineTo(-cw, -52); c.lineTo(-cw / 2, -34); c.lineTo(0, -62); c.lineTo(cw / 2, -34); c.lineTo(cw, -52); c.lineTo(cw, -18); c.closePath(); c.fill();
    c.font = '900 62px Georgia, serif'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText('ZOHAR', 0, 26); c.restore();
  });
  tex.anisotropy = 8;
  return { tex, X0, SPAN, Z0, ZSPAN };
}

// ---------------- the wheel: static bowl (part of the table) ----------------
function bowl(THREE, kit, M) {
  const g = kit.group('WheelBowl', { pos: R.wheel });
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
    const m = new THREE.Matrix4().compose(new THREE.Vector3(Math.sin(a) * R.defR, R.defY - 0.0045, Math.cos(a) * R.defR), new THREE.Quaternion().setFromEuler(new THREE.Euler(0.21, a, 0, 'YXZ')), new THREE.Vector3(1, 1, 1));
    dg.push(geo.applyMatrix4(m).toNonIndexed());
  }
  const d = new THREE.Mesh(mergeGeometries(dg), M.chrome); d.name = 'WheelDeflectors'; g.add(d);
  // the floor under the rotor (dark), so nothing shows through the gap
  lathe([[0.0, -0.03], [0.197, -0.03], [0.197, -0.009]], M.apron, 'WheelBase', 64);
  return g;
}

// ---------------- the rotor (turns; origin on the axis) ----------------
export function rotor(THREE, kit) {
  const M = materials(THREE, kit);
  const g = kit.group('Rotor');
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
  g.traverse((o) => { if (o.isMesh) { o.castShadow = true; o.receiveShadow = true; } });
  return g;
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
  // felt
  const F = feltTexture(kit);
  const clothN = kit.canvasTexture(256, 256, (c, w, h) => { c.fillStyle = 'rgb(128,128,255)'; c.fillRect(0, 0, w, h); for (let y = 0; y < h; y += 4) for (let x = 0; x < w; x += 4) { const o = ((x + y) / 4) % 2 ? 12 : -12; c.fillStyle = `rgb(${128 + o},${128 - o},245)`; c.fillRect(x, y, 4, 2); } });
  clothN.colorSpace = THREE.NoColorSpace; clothN.wrapS = clothN.wrapT = THREE.RepeatWrapping; clothN.repeat.set(100, 54);
  const felt = new THREE.MeshStandardMaterial({ name: 'Felt', map: F.tex, normalMap: clothN, normalScale: new THREE.Vector2(0.35, 0.35), roughness: 0.96 });
  {
    const geo = new THREE.ShapeGeometry(outline(THREE, 0), 32);
    const pos = geo.attributes.position; const uv = new Float32Array(pos.count * 2);
    for (let i = 0; i < pos.count; i++) { const x = pos.getX(i), z = -pos.getY(i); uv[2 * i] = (x - F.X0) / F.SPAN; uv[2 * i + 1] = 1 - (z - F.Z0) / F.ZSPAN; }
    geo.setAttribute('uv', new THREE.BufferAttribute(uv, 2)); geo.rotateX(-Math.PI / 2); geo.computeVertexNormals();
    const m = new THREE.Mesh(geo, felt); m.name = 'Felt'; m.position.y = L.feltY; g.add(m);
  }
  // padded leather rail along the sides and the front (a U-shaped ring, extruded up with a deep round bevel)
  {
    const s = new THREE.Shape(); const o = RAIL, w = TW + o, f = ZF + o, r = CR + o, wi = TW + 0.012, fi = ZF + 0.012, ri = CR + 0.012;
    s.moveTo(-w, -ZB); s.lineTo(-w, -(f - r)); s.absarc(-w + r, -(f - r), r, Math.PI, Math.PI * 1.5, false); s.lineTo(w - r, -f); s.absarc(w - r, -(f - r), r, Math.PI * 1.5, Math.PI * 2, false); s.lineTo(w, -ZB);
    s.lineTo(wi, -ZB); s.lineTo(wi, -(fi - ri)); s.absarc(wi - ri, -(fi - ri), ri, 0, -Math.PI / 2, true); s.lineTo(-wi + ri, -fi); s.absarc(-wi + ri, -(fi - ri), ri, -Math.PI / 2, -Math.PI, true); s.lineTo(-wi, -ZB); s.closePath();
    const bev = 0.016; const geo = new THREE.ExtrudeGeometry(s, { depth: 0.012, bevelEnabled: true, bevelThickness: bev, bevelSize: bev * 0.9, bevelSegments: 8, curveSegments: 40 });
    geo.rotateX(-Math.PI / 2);
    // UVs for the grain: planar from above
    const p = geo.attributes.position; const uv = new Float32Array(p.count * 2); for (let i = 0; i < p.count; i++) { uv[2 * i] = p.getX(i) * 0.6; uv[2 * i + 1] = p.getZ(i) * 0.6 + p.getY(i) * 2; } geo.setAttribute('uv', new THREE.BufferAttribute(uv, 2));
    const m = new THREE.Mesh(geo, M.leather); m.name = 'Rail'; m.position.y = L.feltY + bev - 0.004; g.add(m);
  }
  // wood band under the rail + apron, all round
  {
    const band = (o, y0, y1, mat, name) => { const geo = new THREE.ExtrudeGeometry(outline(THREE, o), { depth: y1 - y0, bevelEnabled: true, bevelThickness: 0.004, bevelSize: 0.004, bevelSegments: 3, curveSegments: 40 }); geo.rotateX(-Math.PI / 2); const m = new THREE.Mesh(geo, mat); m.position.y = y0; m.name = name; g.add(m); return m; };
    band(RAIL + 0.004, L.feltY - 0.06, L.feltY - 0.014, M.wood, 'WoodBand');
    band(RAIL + 0.010, L.feltY - 0.066, L.feltY - 0.06, M.gold, 'GoldTrim');
    band(RAIL - 0.04, 0.62, L.feltY - 0.066, M.apron, 'Apron');
  }
  // dealer side: straight wooden edge with a brass strip
  g.add(kit.roundedBox(2 * (TW + RAIL) + 0.01, 0.05, 0.07, 0.008, M.wood, { name: 'DealerEdge', pos: [0, L.feltY - 0.024, ZB - 0.03] }));
  g.add(kit.box(2 * TW, 0.006, 0.004, M.gold, { name: 'BrassStrip', pos: [0, L.feltY + 0.0005, ZB + 0.002] }));
  // legs
  for (const x of [-0.7, 0.7]) for (const z of [0.15, 0.75]) g.add(kit.cylinder(0.035, 0.03, 0.62, M.black, { name: 'Leg', pos: [x, 0.31, z], segments: 24 }));
  // the wheel bowl
  g.add(bowl(THREE, kit, M));

  // dealer's chip float on the left of the wheel
  {
    const rackW = 0.40, rackD = 0.11, tubes = 8, tubeW = (rackW - 0.024) / tubes;
    const trayMat = new THREE.MeshPhysicalMaterial({ name: 'RackTray', color: 0x0c0b0d, roughness: 0.22, clearcoat: 1, clearcoatRoughness: 0.1 });
    const rack = kit.group('ChipRack', { pos: [R.rack[0], L.feltY, R.rack[2]] });
    const sh = new THREE.Shape(); const top = 0.026, bottom = 0.0, rr = tubeW / 2 - 0.0012;
    sh.moveTo(-rackW / 2, bottom); sh.lineTo(-rackW / 2, top); let x = -rackW / 2 + 0.012; sh.lineTo(x, top);
    for (let i = 0; i < tubes; i++) { const cx = x + tubeW / 2; sh.lineTo(cx - rr, top); sh.absarc(cx, top, rr, Math.PI, 0, false); sh.lineTo(x + tubeW, top); x += tubeW; }
    sh.lineTo(rackW / 2, top); sh.lineTo(rackW / 2, bottom); sh.closePath();
    // raise the tray so its channels sit above the felt
    const geo = new THREE.ExtrudeGeometry(sh, { depth: rackD, bevelEnabled: true, bevelThickness: 0.002, bevelSize: 0.002, bevelSegments: 2, curveSegments: 10 }); geo.translate(0, 0, -rackD / 2);
    const tm = new THREE.Mesh(geo, trayMat); tm.name = 'RackTray'; rack.add(tm);
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

  // the number board (marquee): the app paints the last numbers onto MarqueeScreen
  {
    const mq = kit.group('Marquee', { pos: R.marquee, rot: [0, -18, 0] });
    mq.add(kit.cylinder(0.012, 0.012, 0.42, M.chrome, { name: 'MarqueePole', pos: [0, 0.21, 0], segments: 24 }));
    mq.add(kit.cylinder(0.07, 0.08, 0.012, M.black, { name: 'MarqueeFoot', pos: [0, 0.006, 0], segments: 40 }));
    mq.add(kit.roundedBox(0.2, 0.32, 0.04, 0.012, M.apron, { name: 'MarqueeBody', pos: [0, 0.55, 0] }));
    const screenTex = kit.canvasTexture(256, 400, (c, w, h) => { c.fillStyle = '#05070a'; c.fillRect(0, 0, w, h); c.fillStyle = '#e9c46a'; c.font = '900 40px Georgia, serif'; c.textAlign = 'center'; c.fillText('ZOHAR', w / 2, 70); c.fillStyle = '#ffffff'; c.font = '700 30px Arial'; c.fillText('ROULETTE', w / 2, 120); });
    const screen = new THREE.Mesh(new THREE.PlaneGeometry(0.17, 0.29), new THREE.MeshBasicMaterial({ name: 'MarqueeScreen', map: screenTex }));
    screen.name = 'MarqueeScreen'; screen.position.set(0, 0.55, 0.0205); mq.add(screen);
    mq.add(kit.box(0.2, 0.004, 0.042, M.gold, { name: 'MarqueeTrim', pos: [0, 0.712, 0] }));
    g.add(mq);
  }
  // limit sign
  {
    const signTex = kit.canvasTexture(512, 256, (c, w, h) => {
      const lg = c.createLinearGradient(0, 0, 0, h); lg.addColorStop(0, '#16161c'); lg.addColorStop(1, '#050507'); c.fillStyle = lg; c.fillRect(0, 0, w, h);
      c.strokeStyle = '#c9a03c'; c.lineWidth = 6; c.strokeRect(8, 8, w - 16, h - 16);
      c.fillStyle = '#ffd36b'; c.font = '800 56px Arial'; c.textAlign = 'center'; c.fillText('ROULETTE', w / 2, 80);
      c.fillStyle = '#ffffff'; c.font = '700 38px Arial'; c.fillText('MIN 5  ·  MAX 2000', w / 2, 145);
      c.fillStyle = '#7fe0a8'; c.font = '600 30px Arial'; c.fillText('PLAY MONEY', w / 2, 205);
    });
    const sign = kit.group('LimitSign', { pos: [-0.86, L.feltY, 0.02], rot: [0, 22, 0] });
    sign.add(kit.box(0.16, 0.08, 0.008, new THREE.MeshStandardMaterial({ name: 'SignFace', map: signTex, emissive: 0xffffff, emissiveMap: signTex, emissiveIntensity: 0.6, roughness: 0.3 }), { name: 'SignFace', pos: [0, 0.07, 0] }));
    sign.add(kit.box(0.17, 0.09, 0.006, M.black, { name: 'SignFrame', pos: [0, 0.07, -0.006] }));
    sign.add(kit.cylinder(0.006, 0.006, 0.03, M.black, { name: 'SignPost', pos: [0, 0.015, -0.004], segments: 12 }));
    g.add(sign);
  }
  // soft floor shadow
  const shadowTex = kit.canvasTexture(256, 256, (c, w, h) => { const rg = c.createRadialGradient(w / 2, h / 2, 10, w / 2, h / 2, w / 2); rg.addColorStop(0, 'rgba(0,0,0,0.55)'); rg.addColorStop(0.6, 'rgba(0,0,0,0.3)'); rg.addColorStop(1, 'rgba(0,0,0,0)'); c.fillStyle = rg; c.fillRect(0, 0, w, h); });
  const shadow = new THREE.Mesh(new THREE.PlaneGeometry(2.6, 1.6), new THREE.MeshBasicMaterial({ name: 'FloorShadow', map: shadowTex, transparent: true, depthWrite: false }));
  shadow.rotation.x = -Math.PI / 2; shadow.position.set(0, 0.002, 0.45); shadow.name = 'FloorShadow'; g.add(shadow);
  g.traverse((o) => { if (o.isMesh && o.name !== 'FloorShadow' && o.name !== 'MarqueeScreen') { o.castShadow = true; o.receiveShadow = true; } });
  return g;
}
