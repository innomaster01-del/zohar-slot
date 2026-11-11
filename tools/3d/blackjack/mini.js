// ZOHAR bonus mini-games: light, glowing pieces with no table and no frame (see shared/layout.js → bonus).
// A ring to rest on, a large card, a neon die, the two call tiles, and the bare Plinko triangle. No third-party art.
import { L } from './shared/layout.js';
import { cardMesh } from './cards.js';
import { POCKET_COLORS } from './plinko.js';
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js';
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js';

const B = L.bonus, P = B.plinko;
const glow = (THREE, name, color, strength = 1.0) => new THREE.MeshStandardMaterial({ name, color: new THREE.Color(color).multiplyScalar(0.15), emissive: new THREE.Color(color), emissiveIntensity: strength, roughness: 0.4, metalness: 0 });
const lit = (THREE, name, tex, strength = 1.0) => new THREE.MeshStandardMaterial({ name, map: tex, emissive: 0xffffff, emissiveMap: tex, emissiveIntensity: strength, roughness: 0.5 });

/** The ring everything rests on: a thin neon circle and a faint dark disc, so the pieces read on any table. */
export function ring(THREE, kit) {
  const g = kit.group('BonusRing');
  const t = new THREE.Mesh(new THREE.TorusGeometry(B.ringR, 0.004, 10, 96), glow(THREE, 'RingCyan', '#12d8ff', 1.3)); t.rotation.x = Math.PI / 2; t.position.y = 0.004; t.name = 'Ring'; g.add(t);
  const t2 = new THREE.Mesh(new THREE.TorusGeometry(B.ringR - 0.014, 0.0018, 8, 96), glow(THREE, 'RingPink', '#ff2f92', 1.3)); t2.rotation.x = Math.PI / 2; t2.position.y = 0.003; t2.name = 'RingInner'; g.add(t2);
  const d = new THREE.Mesh(new THREE.CircleGeometry(B.ringR - 0.004, 64), new THREE.MeshBasicMaterial({ name: 'RingDisc', color: 0x05070f, transparent: true, opacity: 0.42, depthWrite: false })); d.rotation.x = -Math.PI / 2; d.position.y = 0.0006; d.name = 'RingDisc'; g.add(d);
  return g;
}
/** A playing card 1.7 times life size (the same named parts as the table cards: CardFace / CardBack take the images). */
export function card(THREE, faceTex, backTex) {
  const c = cardMesh(THREE, faceTex, backTex); c.name = 'BonusCard';
  c.traverse((o) => { if (o.isMesh) o.geometry.scale(B.cardK, B.cardK, B.cardK); });
  return c;
}
/** The glowing outline a card lies in. */
export function cardFrame(THREE, kit, color) {
  const w = L.cardW * B.cardK + 0.014, h = L.cardH * B.cardK + 0.014, r = 0.012, pts = [];
  const corner = (cx, cz, a0) => { for (let i = 0; i <= 8; i++) { const a = a0 + i / 8 * Math.PI / 2; pts.push(new THREE.Vector3(cx + Math.cos(a) * r, 0.003, cz + Math.sin(a) * r)); } };
  corner(w / 2 - r, h / 2 - r, 0); corner(-w / 2 + r, h / 2 - r, Math.PI / 2); corner(-w / 2 + r, -h / 2 + r, Math.PI); corner(w / 2 - r, -h / 2 + r, Math.PI * 1.5);
  const m = new THREE.Mesh(new THREE.TubeGeometry(new THREE.CatmullRomCurve3(pts, true, 'catmullrom', 0.1), 120, 0.0026, 8, true), glow(THREE, 'Frame' + color, color, 1.3));
  const g = kit.group('BonusCardFrame'); m.name = 'Frame'; g.add(m); return g;
}
/** A call tile lying on the ring: LOWER (blue, arrow toward the player) or HIGHER (pink, arrow away). */
export function tile(THREE, kit, higher) {
  const col = higher ? '#ff2f92' : '#2f8bff', W = 512, H = 300;
  const tex = kit.canvasTexture(W, H, (c, w, h) => {
    c.fillStyle = '#05060c'; c.fillRect(0, 0, w, h);
    c.fillStyle = col; c.globalAlpha = 0.2; c.beginPath(); c.roundRect(10, 10, w - 20, h - 20, 40); c.fill(); c.globalAlpha = 1;
    c.strokeStyle = col; c.lineWidth = 10; c.shadowColor = col; c.shadowBlur = 24; c.beginPath(); c.roundRect(14, 14, w - 28, h - 28, 38); c.stroke();
    c.fillStyle = '#ffffff'; c.beginPath(); const ax = w / 2, s = 46;
    if (higher) { c.moveTo(ax, 44); c.lineTo(ax + s, 44 + s * 1.5); c.lineTo(ax - s, 44 + s * 1.5); } else { c.moveTo(ax, 44 + s * 1.5); c.lineTo(ax + s, 44); c.lineTo(ax - s, 44); }
    c.closePath(); c.fill();
    c.font = '900 84px Arial'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText(higher ? 'HIGHER' : 'LOWER', w / 2, 214);
  });
  tex.anisotropy = 8;
  const geo = new THREE.PlaneGeometry(B.tile[0] * 2, B.tile[1] * 2); geo.rotateX(-Math.PI / 2);
  const m = new THREE.Mesh(geo, lit(THREE, higher ? 'TileHigher' : 'TileLower', tex, 1.0)); m.position.y = 0.002; m.name = 'Tile';
  const g = kit.group(higher ? 'BonusHigher' : 'BonusLower'); g.add(m); return g;
}
/** A neon die, 4 cm: dark body, glowing pips. Faces as on the craps die: +Y 1, −Y 6, +Z 2, −Z 5, +X 3, −X 4. */
export function die(THREE, kit) {
  const s = B.die;
  const face = (n) => { const t = kit.canvasTexture(128, 128, (c, w) => {
    c.fillStyle = '#080a14'; c.fillRect(0, 0, w, w); c.strokeStyle = '#ff2f92'; c.lineWidth = 7; c.shadowColor = '#ff2f92'; c.shadowBlur = 10; c.beginPath(); c.roundRect(7, 7, w - 14, w - 14, 16); c.stroke();
    const q = w * 0.26, m = w / 2, r = w * 0.095;
    const at = { 1: [[m, m]], 2: [[m - q, m - q], [m + q, m + q]], 3: [[m - q, m - q], [m, m], [m + q, m + q]], 4: [[m - q, m - q], [m + q, m - q], [m - q, m + q], [m + q, m + q]], 5: [[m - q, m - q], [m + q, m - q], [m, m], [m - q, m + q], [m + q, m + q]], 6: [[m - q, m - q], [m + q, m - q], [m - q, m], [m + q, m], [m - q, m + q], [m + q, m + q]] }[n];
    c.shadowColor = '#12d8ff'; c.shadowBlur = 12; c.fillStyle = '#d8fbff'; for (const [x, y] of at) { c.beginPath(); c.arc(x, y, r, 0, 7); c.fill(); } });
    return lit(THREE, `NeonDie${n}`, t, 1.1); };
  const m = new THREE.Mesh(new RoundedBoxGeometry(s, s, s, 3, s * 0.07), [3, 4, 1, 6, 2, 5].map(face)); m.name = 'Die';
  const g = kit.group('BonusDie'); g.add(m); return g;
}
/** The bare Plinko triangle: glowing pegs, glowing pockets, the bonus of each pocket under it. Nothing else. */
export function triangle(THREE, kit) {
  const g = kit.group('BonusPlinko'); const n = P.rows + 1, half = P.rows / 2;
  const colorOf = (k) => POCKET_COLORS[Math.round(Math.abs(k - half) * (POCKET_COLORS.length - 1) / half)];
  // the pegs run from cyan at the top to pink at the bottom, one colour a row
  for (let r = 0; r < P.rows; r++) {
    const heads = []; for (let i = 0; i < r + 3; i++) { const h = new THREE.SphereGeometry(P.pegR, 16, 12); h.translate((i - (r + 2) / 2) * P.dx, P.topY - r * P.dy, 0); heads.push(h); }
    const col = '#' + new THREE.Color('#3fe6ff').lerp(new THREE.Color('#ff4fa6'), r / (P.rows - 1)).getHexString();
    const pm = new THREE.Mesh(mergeGeometries(heads), glow(THREE, 'Peg' + r, col, 1.5)); pm.name = 'Pegs' + r; g.add(pm);
  }
  const h = P.cupTop - P.cupFloor, d = 0.05, by = new Map(); const add = (col, geo) => { if (!by.has(col)) by.set(col, []); by.get(col).push(geo); };
  for (let k = 0; k <= n; k++) { const x = (k - n / 2) * P.dx, col = colorOf(k <= n / 2 ? Math.min(k, n - 1) : k - 1); const b = new THREE.BoxGeometry(0.005, h, d); b.translate(x, P.cupFloor + h / 2, 0); add(col, b); }
  for (let k = 0; k < n; k++) { const f = new THREE.BoxGeometry(P.dx - 0.005, 0.005, d); f.translate((k - half) * P.dx, P.cupFloor - 0.0025, 0); add(colorOf(k), f); }
  for (const [col, geos] of by) { const m = new THREE.Mesh(mergeGeometries(geos), glow(THREE, 'Pocket' + col, col, 1.1)); m.name = 'Pockets' + col; g.add(m); }
  const W = 2048, H = 150;
  const tex = kit.canvasTexture(W, H, (c, w, hh) => {
    c.fillStyle = '#05060c'; c.fillRect(0, 0, w, hh);
    for (let k = 0; k < n; k++) { const pct = P.pct[Math.min(k, P.rows - k)], col = colorOf(k), txt = '+' + pct + '%', x0 = k * w / n;
      c.fillStyle = col; c.globalAlpha = 0.18; c.beginPath(); c.roundRect(x0 + 8, 10, w / n - 16, hh - 20, 18); c.fill(); c.globalAlpha = 1;
      c.strokeStyle = col; c.lineWidth = 5; c.beginPath(); c.roundRect(x0 + 8, 10, w / n - 16, hh - 20, 18); c.stroke();
      c.font = `900 ${txt.length > 5 ? 50 : 60}px Arial`; c.textAlign = 'center'; c.textBaseline = 'middle'; c.shadowColor = col; c.shadowBlur = 14; c.fillStyle = '#ffffff'; c.fillText(txt, x0 + w / n / 2, hh / 2 + 3); c.shadowBlur = 0; }
  });
  tex.anisotropy = 8;
  const lm = new THREE.Mesh(new THREE.PlaneGeometry(n * P.dx, n * P.dx * H / W), lit(THREE, 'BonusLabels', tex, 1.0)); lm.position.set(0, P.labelY, 0); lm.name = 'Labels'; g.add(lm);
  return g;
}
