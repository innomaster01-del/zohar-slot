// ZOHAR Plinko: a standing neon board, real size (about 2 m tall), built in code. No third-party art.
// A smoked-glass triangle you can see the room through, glowing pegs, thirteen glowing pockets, neon tube frame,
// a dark podium. Separate small models for the ball, the peg flash, the lit pocket and the three multiplier strips.
import { L } from './shared/layout.js';
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js';

const P = L.plinko;
export const POCKET_COLORS = ['#2f7bff', '#22b8ff', '#19e0c0', '#5dff7a', '#ffe14a', '#ff9a2e', '#ff3d9a'];   // centre … edge
// the three boards, multipliers ×5, edge … centre (the same numbers as PlinkoEngine.kt; the unit test there checks the returns)
export const BOARDS = { low: [60, 16, 8, 8, 6, 5, 1], medium: [130, 56, 22, 10, 6, 2, 2], high: [755, 122, 42, 10, 3, 1, 1] };
const label = (f) => (f % 5 === 0 ? `${f / 5}` : `${Math.floor(f / 5)}.${(f % 5) * 2}`);
const pocketX = (k) => (k - P.rows / 2) * P.dx;
const colorOf = (k) => POCKET_COLORS[Math.abs(k - P.rows / 2)];

// saturated colour that also lights itself; kept near 1 so it stays the colour it is instead of burning out to white (the app's bloom adds the halo)
const glow = (THREE, name, color, strength = 1.0) => new THREE.MeshStandardMaterial({ name, color: new THREE.Color(color).multiplyScalar(0.15), emissive: new THREE.Color(color), emissiveIntensity: strength, roughness: 0.4, metalness: 0 });

export function ball(THREE, kit) {
  const g = kit.group('PlinkoBall');
  const m = new THREE.Mesh(new THREE.SphereGeometry(P.ballR, 32, 20), new THREE.MeshPhysicalMaterial({ name: 'Ball', color: 0xff2f8e, emissive: 0xff1f7a, emissiveIntensity: 0.9, roughness: 0.15, clearcoat: 1, clearcoatRoughness: 0.05 }));
  m.name = 'Ball'; g.add(m); return g;
}
/** A peg lighting up as the ball touches it. */
export function flash(THREE, kit) {
  const g = kit.group('PlinkoFlash');
  const m = new THREE.Mesh(new THREE.SphereGeometry(P.pegR * 2.1, 20, 14), glow(THREE, 'Flash', '#ffffff', 4)); m.name = 'Flash'; g.add(m); return g;
}
/** The pocket the ball fell in, lit: a bright frame the size of one pocket. Origin: pocket centre on the pocket floor. */
export function hit(THREE, kit) {
  const g = kit.group('PlinkoHit'); const mat = glow(THREE, 'Hit', '#fff6c8', 3.5); const w = P.dx - 0.006, h = P.cupTop - P.cupFloor + 0.012, t = 0.006, d = 0.064;
  for (const sx of [-1, 1]) g.add(kit.box(t, h, d, mat, { name: 'HitSide', pos: [sx * w / 2, h / 2 - 0.006, P.ballZ] }));
  g.add(kit.box(w + t, t, d, mat, { name: 'HitFloor', pos: [0, -0.006, P.ballZ] }));
  return g;
}
/** The strip of multipliers under the pockets for one board. Origin: its centre. */
export function labels(THREE, kit, board) {
  const half = BOARDS[board]; const W = 2048, H = 128, n = P.rows + 1;
  const tex = kit.canvasTexture(W, H, (c, w, h) => {
    c.fillStyle = '#05060c'; c.fillRect(0, 0, w, h);
    for (let k = 0; k < n; k++) {
      const f = half[Math.min(k, P.rows - k)], x = (k + 0.5) * w / n, col = colorOf(k), txt = '×' + label(f);
      c.fillStyle = col; c.globalAlpha = 0.16; c.beginPath(); c.roundRect(k * w / n + 6, 10, w / n - 12, h - 20, 16); c.fill(); c.globalAlpha = 1;
      c.strokeStyle = col; c.lineWidth = 4; c.beginPath(); c.roundRect(k * w / n + 6, 10, w / n - 12, h - 20, 16); c.stroke();
      c.font = `900 ${txt.length > 4 ? 44 : txt.length > 3 ? 52 : 62}px Arial`; c.textAlign = 'center'; c.textBaseline = 'middle'; c.shadowColor = col; c.shadowBlur = 14; c.fillStyle = '#ffffff'; c.fillText(txt, x, h / 2 + 3);
    }
  });
  tex.anisotropy = 8;
  const g = kit.group('PlinkoLabels_' + board);
  const m = new THREE.Mesh(new THREE.PlaneGeometry(n * P.dx, n * P.dx * H / W), new THREE.MeshStandardMaterial({ name: 'Labels_' + board, map: tex, emissive: 0xffffff, emissiveMap: tex, emissiveIntensity: 1.0, roughness: 0.5 }));
  m.name = 'Labels'; g.add(m); return g;
}

export async function build(THREE, kit) {
  const g = kit.group('PlinkoBoard');
  const lastY = P.topY - (P.rows - 1) * P.dy, halfW = (P.rows + 1) / 2 * P.dx;            // last row of pegs; half width of the pocket row
  const yBot = P.cupFloor - 0.085, yApex = P.dropY + 0.07, xApex = 0.115, xSide = halfW + 0.035, yShoulder = P.cupTop + 0.02;
  // outline of the board, counter-clockwise from the bottom left (seen from the player)
  const outline = [[-xSide, yBot], [xSide, yBot], [xSide, yShoulder], [xApex, yApex], [-xApex, yApex], [-xSide, yShoulder]];

  // ---------------- smoked glass: dark, but the room shows through ----------------
  {
    const sh = new THREE.Shape(); outline.forEach(([x, y], i) => (i ? sh.lineTo(x, y) : sh.moveTo(x, y))); sh.closePath();
    const geo = new THREE.ExtrudeGeometry(sh, { depth: 0.008, bevelEnabled: false }); geo.translate(0, 0, -0.02);
    const glass = new THREE.MeshPhysicalMaterial({ name: 'SmokedGlass', color: 0x05070f, roughness: 0.12, metalness: 0, transparent: true, opacity: 0.78 });
    const m = new THREE.Mesh(geo, glass); m.name = 'Glass'; g.add(m);
  }
  // ---------------- neon tube frame ----------------
  {
    const tube = (pts, r, mat, name) => { const path = new THREE.CurvePath(); for (let i = 0; i < pts.length - 1; i++) path.add(new THREE.LineCurve3(new THREE.Vector3(...pts[i]), new THREE.Vector3(...pts[i + 1]))); const m = new THREE.Mesh(new THREE.TubeGeometry(path, pts.length * 24, r, 10, false), mat); m.name = name; g.add(m); return m; };
    const ring = [...outline, outline[0]].map(([x, y]) => [x, y, 0.0]);
    tube(ring, 0.009, glow(THREE, 'NeonCyan', '#12d8ff', 1.3), 'NeonFrame');
    for (const [x, y] of outline) { const s = new THREE.Mesh(new THREE.SphereGeometry(0.009, 14, 10), glow(THREE, 'NeonCyan', '#12d8ff', 1.3)); s.position.set(x, y, 0); s.name = 'NeonJoint'; g.add(s); }
    // a second, thinner line inside it
    const inset = (k) => outline.map(([x, y]) => [x * (1 - k / Math.max(Math.abs(x), 0.2) * 0.2), y + (y < 1 ? k : -k * 1.4), 0.0]);
    const inner = inset(0.03); tube([...inner, inner[0]], 0.004, glow(THREE, 'NeonPink', '#ff2f92', 1.3), 'NeonInner');
    // the gate the ball drops from
    const gate = new THREE.Mesh(new THREE.TorusGeometry(P.ballR * 1.9, 0.005, 12, 40), glow(THREE, 'NeonPink', '#ff2f92', 1.3)); gate.position.set(0, P.dropY + 0.012, P.ballZ); gate.rotation.x = Math.PI / 2; gate.name = 'DropGate'; g.add(gate);
  }
  // ---------------- pegs: one merged mesh; each a short stem with a glowing head ----------------
  {
    const heads = [], stems = [];
    for (let r = 0; r < P.rows; r++) for (let i = 0; i < r + 3; i++) {
      const x = (i - (r + 2) / 2) * P.dx, y = P.topY - r * P.dy;
      const h = new THREE.SphereGeometry(P.pegR, 14, 10); h.translate(x, y, P.ballZ); heads.push(h);
      const st = new THREE.CylinderGeometry(0.0028, 0.0028, P.ballZ + 0.012, 8); st.rotateX(Math.PI / 2); st.translate(x, y, (P.ballZ - 0.012) / 2); stems.push(st);
    }
    const hm = new THREE.Mesh(mergeGeometries(heads), glow(THREE, 'PegHead', '#f2fbff', 1.6)); hm.name = 'Pegs'; g.add(hm);
    const sm = new THREE.Mesh(mergeGeometries(stems), kit.mat.metal('#8a93a8', 0.3)); sm.name = 'PegStems'; g.add(sm);
  }
  // ---------------- pockets: glowing dividers and floors, coloured from the centre out ----------------
  {
    const n = P.rows + 1, h = P.cupTop - P.cupFloor, d = 0.06, by = new Map();
    const add = (col, geo) => { if (!by.has(col)) by.set(col, []); by.get(col).push(geo); };
    for (let k = 0; k <= n; k++) {                         // n + 1 dividers; each takes the colour of the pocket nearer the edge
      const x = (k - n / 2) * P.dx, col = colorOf(k <= n / 2 ? Math.min(k, n - 1) : k - 1);
      const b = new THREE.BoxGeometry(0.005, h, d); b.translate(x, P.cupFloor + h / 2, P.ballZ); add(col, b);
      const cap = new THREE.SphereGeometry(0.0055, 10, 8); cap.translate(x, P.cupTop, P.ballZ + d / 2); add(col, cap);
    }
    for (let k = 0; k < n; k++) { const f = new THREE.BoxGeometry(P.dx - 0.005, 0.005, d); f.translate(pocketX(k), P.cupFloor - 0.0025, P.ballZ); add(colorOf(k), f); }
    for (const [col, geos] of by) { const m = new THREE.Mesh(mergeGeometries(geos.map((q) => q.toNonIndexed())), glow(THREE, 'Pocket' + col, col, 1.1)); m.name = 'Pockets' + col; g.add(m); }
    // a tinted clear front, so the ball is seen resting in its pocket
    const front = new THREE.Mesh(new THREE.BoxGeometry(n * P.dx, h, 0.003), new THREE.MeshPhysicalMaterial({ name: 'PocketFront', color: 0x9fd8ff, roughness: 0.05, transparent: true, opacity: 0.12 })); front.position.set(0, P.cupFloor + h / 2, P.ballZ + d / 2); front.name = 'PocketFront'; g.add(front);
  }
  // ---------------- sign ----------------
  {
    const tex = kit.canvasTexture(1024, 256, (c, w, h) => {
      c.fillStyle = '#05060c'; c.fillRect(0, 0, w, h);
      c.textAlign = 'center'; c.textBaseline = 'middle';
      c.font = '900 132px Arial'; c.shadowColor = '#27e7ff'; c.shadowBlur = 36; c.fillStyle = '#bff7ff'; c.fillText('PLINKO', w / 2, 104);
      c.font = '800 44px Georgia, serif'; c.shadowColor = '#ff3d9a'; c.shadowBlur = 22; c.fillStyle = '#ffd0e6'; c.fillText('Z O H A R', w / 2, 206);
      c.shadowBlur = 0; c.strokeStyle = '#ff3d9a'; c.lineWidth = 8; c.beginPath(); c.roundRect(10, 10, w - 20, h - 20, 40); c.stroke();
    });
    tex.anisotropy = 8;
    const sign = kit.group('Sign', { pos: [0, yApex + 0.125, 0] });
    sign.add(kit.box(0.6, 0.15, 0.012, new THREE.MeshStandardMaterial({ name: 'SignFace', map: tex, emissive: 0xffffff, emissiveMap: tex, emissiveIntensity: 1.1, roughness: 0.4 }), { name: 'SignFace', pos: [0, 0, 0.004] }));
    sign.add(kit.roundedBox(0.62, 0.17, 0.03, 0.012, kit.mat.metal('#15161c', 0.4), { name: 'SignBody', pos: [0, 0, -0.014] }));
    g.add(sign);
    g.add(kit.cylinder(0.012, 0.012, 0.06, kit.mat.metal('#15161c', 0.4), { name: 'SignNeck', pos: [0, yApex + 0.025, -0.012], segments: 16 }));
  }
  // ---------------- stand: podium, post, shelf for the multipliers ----------------
  {
    const dark = new THREE.MeshPhysicalMaterial({ name: 'Body', color: 0x0a0b12, roughness: 0.25, clearcoat: 1, clearcoatRoughness: 0.15 });
    g.add(kit.roundedBox(1.16, 0.09, 0.5, 0.03, dark, { name: 'Podium', pos: [0, 0.045, 0] }));
    g.add(kit.roundedBox(1.18, 0.012, 0.52, 0.005, glow(THREE, 'NeonCyan', '#12d8ff', 1.3), { name: 'PodiumGlow', pos: [0, 0.092, 0] }));
    g.add(kit.roundedBox(1.12, 0.014, 0.46, 0.006, dark, { name: 'PodiumTop', pos: [0, 0.102, 0] }));
    for (const x of [-0.36, 0.36]) {
      g.add(kit.roundedBox(0.07, yBot - 0.10, 0.07, 0.015, dark, { name: 'Post', pos: [x, 0.10 + (yBot - 0.10) / 2, -0.03] }));
      g.add(kit.box(0.008, yBot - 0.16, 0.008, glow(THREE, 'NeonPink', '#ff2f92', 1.3), { name: 'PostGlow', pos: [x, 0.13 + (yBot - 0.16) / 2, 0.008] }));
    }
    // the strip that carries the multipliers (the three label models go on its front)
    g.add(kit.roundedBox(2 * xSide - 0.02, 0.075, 0.05, 0.01, dark, { name: 'LabelShelf', pos: [0, P.labelY, 0.035] }));
  }
  // ---------------- soft floor shadow ----------------
  const shadowTex = kit.canvasTexture(256, 256, (c, w, h) => { const rg = c.createRadialGradient(w / 2, h / 2, 10, w / 2, h / 2, w / 2); rg.addColorStop(0, 'rgba(0,0,0,0.55)'); rg.addColorStop(0.6, 'rgba(0,0,0,0.3)'); rg.addColorStop(1, 'rgba(0,0,0,0)'); c.fillStyle = rg; c.fillRect(0, 0, w, h); });
  const shadow = new THREE.Mesh(new THREE.PlaneGeometry(1.7, 0.95), new THREE.MeshBasicMaterial({ name: 'FloorShadow', map: shadowTex, transparent: true, depthWrite: false }));
  shadow.rotation.x = -Math.PI / 2; shadow.position.set(0, 0.002, 0); shadow.name = 'FloorShadow'; g.add(shadow);
  return g;
}
