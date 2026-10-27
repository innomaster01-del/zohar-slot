// ZOHAR money wheel (big wheel game show) in the shared frame (see shared/layout.js → mw).
// Parts the app animates are separate: the wheel (turns about z), the flapper (tilts), the bulbs (two chase groups).
import { L } from './shared/layout.js';
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js';

export const SEGMENTS = ['40', '1', '5', '2', '10', '1', '2', '1', '1', '20', '5', '2', '1', '2', '1', '1', '2', '1', '7x', '10', '5', '2', '1', '1', '2', '5', '1', '2', '1', '1', '2', '1', '10', '5', '1', '2', '2x', '20', '1', '2', '1', '5', '1', '2', '1', '10', '2', '1', '1', '5', '2', '1', '2', '1'];
export const STYLE = {
  '1': { a: '#ffd84a', b: '#d99a0a', ink: '#ffffff' }, '2': { a: '#4aa3ff', b: '#1b58c9', ink: '#ffffff' },
  '5': { a: '#c169f0', b: '#6e1fa8', ink: '#ffffff' }, '10': { a: '#3fe08a', b: '#0f8f4a', ink: '#ffffff' },
  '20': { a: '#ff9f3a', b: '#c4580a', ink: '#ffffff' }, '40': { a: '#ff4b55', b: '#a8101a', ink: '#ffffff' },
  '2x': { a: '#f2f4f8', b: '#8e96a3', ink: '#1a1a22' }, '7x': { a: '#ffe9a8', b: '#c7962c', ink: '#3a2400' },
};
const M = L.mw, N = SEGMENTS.length, STEP = Math.PI * 2 / N;

function mats(THREE, kit) {
  return {
    chrome: new THREE.MeshStandardMaterial({ name: 'Chrome', color: 0xeef0f4, roughness: 0.07, metalness: 1 }),
    gold: new THREE.MeshStandardMaterial({ name: 'Gold', color: 0xe2b64e, roughness: 0.16, metalness: 1 }),
    lacquer: new THREE.MeshPhysicalMaterial({ name: 'Lacquer', color: 0x0b0a10, roughness: 0.18, clearcoat: 1, clearcoatRoughness: 0.05 }),
    plum: new THREE.MeshPhysicalMaterial({ name: 'Plum', color: 0x2a0b33, roughness: 0.25, clearcoat: 1, clearcoatRoughness: 0.08 }),
  };
}

// ---------------- the wheel (origin = axis, faces +z; segment i centred i·step clockwise from the top) ----------------
export function wheel(THREE, kit) {
  const K = mats(THREE, kit);
  const g = kit.group('MoneyWheel');
  const R = M.R, rIn = 0.19, S = 2048, C = S / 2, px = S / 2 / (R + 0.004);
  const faceTex = kit.canvasTexture(S, S, (c) => {
    c.fillStyle = '#0b0a10'; c.fillRect(0, 0, S, S);
    for (let i = 0; i < N; i++) {
      const v = SEGMENTS[i], st = STYLE[v];
      // canvas angle: 0 = +x, clockwise on screen (y down) = clockwise seen from the front
      const a0 = -Math.PI / 2 + (i - 0.5) * STEP, a1 = a0 + STEP;
      const gr = c.createRadialGradient(C, C, rIn * px, C, C, R * px); gr.addColorStop(0, st.b); gr.addColorStop(0.55, st.a); gr.addColorStop(0.9, st.a); gr.addColorStop(1, st.b);
      c.beginPath(); c.arc(C, C, R * px, a0, a1); c.arc(C, C, rIn * px, a1, a0, true); c.closePath(); c.fillStyle = gr; c.fill();
      // gloss stripe and inner shadow
      c.save(); c.clip(); const gl = c.createLinearGradient(C + Math.cos(a0) * R * px, C + Math.sin(a0) * R * px, C + Math.cos(a1) * R * px, C + Math.sin(a1) * R * px);
      gl.addColorStop(0, 'rgba(255,255,255,0.0)'); gl.addColorStop(0.35, 'rgba(255,255,255,0.22)'); gl.addColorStop(0.5, 'rgba(255,255,255,0.0)'); gl.addColorStop(1, 'rgba(0,0,0,0.25)');
      c.fillStyle = gl; c.fillRect(0, 0, S, S); c.restore();
      // the value, reading outward, near the rim
      const am = (a0 + a1) / 2;
      c.save(); c.translate(C + Math.cos(am) * R * px * 0.8, C + Math.sin(am) * R * px * 0.8); c.rotate(am + Math.PI / 2);
      const big = v.endsWith('x'); c.font = `900 ${v.length > 1 ? (big ? 78 : 76) : 92}px Arial Black, Arial, sans-serif`; c.textAlign = 'center'; c.textBaseline = 'middle';
      c.lineJoin = 'round'; c.lineWidth = 10; c.strokeStyle = 'rgba(0,0,0,0.55)'; c.strokeText(v, 0, 0);
      c.fillStyle = st.ink; c.fillText(v, 0, 0);
      if (big) { c.font = '800 26px Arial'; c.fillStyle = st.ink; c.fillText('MULTIPLIER', 0, 56); }
      c.restore();
      // a small value repeated lower down the segment
      c.save(); c.translate(C + Math.cos(am) * R * px * 0.52, C + Math.sin(am) * R * px * 0.52); c.rotate(am + Math.PI / 2);
      c.font = `800 ${v.length > 1 ? 36 : 42}px Arial`; c.fillStyle = 'rgba(255,255,255,0.55)'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText(v, 0, 0); c.restore();
    }
    // separators
    c.strokeStyle = '#f4d27a'; c.lineWidth = 7;
    for (let i = 0; i < N; i++) { const a = -Math.PI / 2 + (i + 0.5) * STEP; c.beginPath(); c.moveTo(C + Math.cos(a) * rIn * px, C + Math.sin(a) * rIn * px); c.lineTo(C + Math.cos(a) * R * px, C + Math.sin(a) * R * px); c.stroke(); }
    c.lineWidth = 12; c.beginPath(); c.arc(C, C, R * px - 6, 0, 7); c.stroke(); c.beginPath(); c.arc(C, C, rIn * px + 6, 0, 7); c.stroke();
  });
  faceTex.anisotropy = 8;
  const face = new THREE.Mesh(new THREE.CircleGeometry(R + 0.004, 216), new THREE.MeshPhysicalMaterial({ name: 'WheelFace', map: faceTex, roughness: 0.22, clearcoat: 1, clearcoatRoughness: 0.06 }));
  face.position.z = M.face; face.name = 'WheelFace'; g.add(face);
  // body: thick lacquered disc with a gold edge band
  const body = kit.cylinder(R + 0.07, R + 0.07, 0.11, K.plum, { name: 'WheelBody', segments: 160 }); body.rotation.x = Math.PI / 2; body.position.z = M.face - 0.056; g.add(body);
  const edge = kit.cylinder(R + 0.072, R + 0.072, 0.03, K.gold, { name: 'WheelEdge', segments: 160 }); edge.rotation.x = Math.PI / 2; edge.position.z = M.face - 0.02; g.add(edge);
  // raised chrome rim round the segments, and the inner gold ring
  const rim = new THREE.Mesh(new THREE.TorusGeometry(R + 0.012, 0.012, 16, 216), K.chrome); rim.position.z = M.face + 0.004; rim.name = 'WheelRim'; g.add(rim);
  const outer = new THREE.Mesh(new THREE.TorusGeometry(R + 0.062, 0.01, 16, 216), K.gold); outer.position.z = M.face + 0.002; outer.name = 'WheelOuterRing'; g.add(outer);
  // raised gold separators
  const sep = []; for (let i = 0; i < N; i++) { const a = (i + 0.5) * STEP; const geo = new THREE.BoxGeometry(0.006, R - rIn - 0.01, 0.008); geo.translate(0, (R + rIn) / 2, M.face + 0.004); geo.rotateZ(-a); sep.push(geo.toNonIndexed()); }
  const seps = new THREE.Mesh(mergeGeometries(sep), K.gold); seps.name = 'Separators'; g.add(seps);
  // pegs on the segment lines, just inside the rim
  const pg = [];
  for (let i = 0; i < N; i++) {
    const a = (i + 0.5) * STEP; const x = Math.sin(a) * M.pegR, y = Math.cos(a) * M.pegR;
    const shaft = new THREE.CylinderGeometry(0.0055, 0.0065, 0.05, 14); shaft.rotateX(Math.PI / 2); shaft.translate(x, y, M.face + 0.025); pg.push(shaft.toNonIndexed());
    const head = new THREE.SphereGeometry(0.0085, 14, 10); head.translate(x, y, M.face + 0.052); pg.push(head.toNonIndexed());
  }
  const pegs = new THREE.Mesh(mergeGeometries(pg), K.chrome); pegs.name = 'Pegs'; g.add(pegs);
  // bulbs round the edge: two groups that the app lights in turn (chase)
  const bulbGeo = (odd) => { const bs = []; for (let i = 0; i < 108; i++) { if (i % 2 !== odd) continue; const a = (i + 0.5) / 108 * Math.PI * 2; const b = new THREE.SphereGeometry(0.0115, 14, 10); b.translate(Math.sin(a) * (R + 0.042), Math.cos(a) * (R + 0.042), M.face + 0.006); bs.push(b.toNonIndexed()); } return mergeGeometries(bs); };
  const bulbA = new THREE.Mesh(bulbGeo(0), new THREE.MeshStandardMaterial({ name: 'BulbsA', color: 0xfff1cc, emissive: 0xffd27a, emissiveIntensity: 3, roughness: 0.3 })); bulbA.name = 'BulbsA'; g.add(bulbA);
  const bulbB = new THREE.Mesh(bulbGeo(1), new THREE.MeshStandardMaterial({ name: 'BulbsB', color: 0xfff1cc, emissive: 0xffd27a, emissiveIntensity: 3, roughness: 0.3 })); bulbB.name = 'BulbsB'; g.add(bulbB);
  // hub: gold medallion with the crown, glass dome
  const hubTex = kit.canvasTexture(512, 512, (c, w, h) => {
    const rg = c.createRadialGradient(w / 2, h / 2 - 40, 20, w / 2, h / 2, w / 2); rg.addColorStop(0, '#3a1347'); rg.addColorStop(1, '#0d0412'); c.fillStyle = rg; c.fillRect(0, 0, w, h);
    const gg = c.createLinearGradient(0, 120, 0, 380); gg.addColorStop(0, '#fff3c0'); gg.addColorStop(0.5, '#e9c46a'); gg.addColorStop(1, '#a8761c');
    c.fillStyle = gg; c.beginPath(); const k = 70, cx = w / 2, cy = 200; c.moveTo(cx - k, cy + 30); c.lineTo(cx - k, cy - 40); c.lineTo(cx - k / 2, cy - 5); c.lineTo(cx, cy - 70); c.lineTo(cx + k / 2, cy - 5); c.lineTo(cx + k, cy - 40); c.lineTo(cx + k, cy + 30); c.closePath(); c.fill();
    c.font = '900 92px Georgia, serif'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText('ZOHAR', cx, 310);
    c.font = '700 30px Arial'; c.fillStyle = '#e9c46a'; c.fillText('MONEY WHEEL', cx, 385);
  });
  const hub = new THREE.Mesh(new THREE.CircleGeometry(rIn - 0.005, 96), new THREE.MeshPhysicalMaterial({ name: 'Hub', map: hubTex, roughness: 0.3, clearcoat: 1, clearcoatRoughness: 0.05 }));
  hub.position.z = M.face + 0.002; hub.name = 'Hub'; g.add(hub);
  const hubRing = new THREE.Mesh(new THREE.TorusGeometry(rIn, 0.014, 16, 128), K.gold); hubRing.position.z = M.face + 0.008; hubRing.name = 'HubRing'; g.add(hubRing);
  const dome = new THREE.Mesh(new THREE.SphereGeometry(rIn - 0.01, 64, 24, 0, Math.PI * 2, 0, Math.PI / 2), new THREE.MeshPhysicalMaterial({ name: 'Dome', color: 0xffffff, roughness: 0.02, transparent: true, opacity: 0.18, clearcoat: 1 }));
  dome.rotation.x = Math.PI / 2; dome.scale.set(1, 0.22, 1); dome.position.z = M.face + 0.004; dome.name = 'Dome'; g.add(dome);
  g.traverse((o) => { if (o.isMesh) { o.castShadow = true; o.receiveShadow = true; } });
  return g;
}

// ---------------- the flapper: pivot at the origin, the leather tongue hangs down ----------------
export function flapper(THREE, kit) {
  const K = mats(THREE, kit);
  const g = kit.group('Flapper');
  const sh = new THREE.Shape(); sh.moveTo(-0.02, 0); sh.lineTo(0.02, 0); sh.lineTo(0.014, -0.1); sh.lineTo(0, -0.125); sh.lineTo(-0.014, -0.1); sh.closePath();
  const geo = new THREE.ExtrudeGeometry(sh, { depth: 0.006, bevelEnabled: true, bevelThickness: 0.002, bevelSize: 0.002, bevelSegments: 2 }); geo.translate(0, 0, -0.003);
  const tongue = new THREE.Mesh(geo, new THREE.MeshPhysicalMaterial({ name: 'FlapperLeather', color: 0xc8101e, roughness: 0.35, clearcoat: 0.6 })); tongue.name = 'FlapperTongue'; g.add(tongue);
  g.add(kit.cylinder(0.012, 0.012, 0.03, K.gold, { name: 'FlapperPin', rot: [90, 0, 0], segments: 24 }));
  g.add(kit.box(0.05, 0.03, 0.02, K.gold, { name: 'FlapperClamp', pos: [0, 0.012, 0] }));
  return g;
}

// ---------------- the stage: stand, frame, platform, light pillars, betting console ----------------
export async function stage(THREE, kit) {
  const K = mats(THREE, kit);
  const g = kit.group('MoneyWheelStage');
  const W = M.wheel, R = M.R;
  // platform: a low round stage with a glowing edge
  g.add(kit.cylinder(1.55, 1.58, 0.06, K.lacquer, { name: 'Platform', pos: [0, 0.03, -0.15], segments: 128 }));
  const glow = new THREE.Mesh(new THREE.TorusGeometry(1.565, 0.008, 10, 200), new THREE.MeshStandardMaterial({ name: 'StageGlow', color: 0xd9a3ff, emissive: 0xb050ff, emissiveIntensity: 2.4 })); glow.rotation.x = Math.PI / 2; glow.position.set(0, 0.05, -0.15); glow.name = 'StageGlow'; g.add(glow);
  const gl2 = new THREE.Mesh(new THREE.TorusGeometry(1.2, 0.006, 10, 180), K.gold); gl2.rotation.x = Math.PI / 2; gl2.position.set(0, 0.061, -0.15); gl2.name = 'StageRing'; g.add(gl2);
  // the stand: a tapered column behind the wheel up to the axle, a foot, the axle cap
  g.add(kit.roundedBox(0.34, 0.08, 0.5, 0.02, K.lacquer, { name: 'StandFoot', pos: [0, 0.1, W[2] - 0.2] }));
  const col = kit.lathe([[0.16, 0], [0.13, 0.4], [0.1, 0.9], [0.09, W[1] - 0.06]], K.plum, { name: 'StandColumn', segments: 48 }); col.position.set(0, 0.06, W[2] - 0.22); g.add(col);
  g.add(kit.cylinder(0.09, 0.09, 0.22, K.gold, { name: 'Axle', pos: [W[0], W[1], W[2] - 0.12], rot: [90, 0, 0], segments: 40 }));
  // the frame ring behind the wheel with its own light strip, and the flapper bracket on top
  const frame = new THREE.Mesh(new THREE.TorusGeometry(R + 0.13, 0.03, 18, 216), K.lacquer); frame.position.set(W[0], W[1], W[2] - 0.08); frame.name = 'Frame'; g.add(frame);
  const strip = new THREE.Mesh(new THREE.TorusGeometry(R + 0.13, 0.008, 10, 216), new THREE.MeshStandardMaterial({ name: 'FrameGlow', color: 0xffe2a0, emissive: 0xffc35a, emissiveIntensity: 2.2 })); strip.position.set(W[0], W[1], W[2] - 0.05); strip.name = 'FrameGlow'; g.add(strip);
  g.add(kit.roundedBox(0.09, 0.11, 0.16, 0.012, K.gold, { name: 'FlapperBracket', pos: [M.flapper[0], M.flapper[1] + 0.02, M.flapper[2] - 0.08] }));
  // two light pillars at the back
  const pillarTex = kit.canvasTexture(64, 512, (c, w, h) => { const lg = c.createLinearGradient(0, 0, 0, h); lg.addColorStop(0, '#ffd27a'); lg.addColorStop(0.5, '#c24dff'); lg.addColorStop(1, '#3a0d6e'); c.fillStyle = lg; c.fillRect(0, 0, w, h); for (let y = 0; y < h; y += 16) { c.fillStyle = 'rgba(0,0,0,0.35)'; c.fillRect(0, y, w, 3); } });
  for (const sx of [-1, 1]) {
    const p = kit.group('Pillar', { pos: [sx * 1.32, 0.06, -0.55] });
    p.add(kit.roundedBox(0.16, 2.2, 0.16, 0.02, K.lacquer, { name: 'PillarBody', pos: [0, 1.1, 0] }));
    p.add(kit.box(0.1, 2.0, 0.004, new THREE.MeshStandardMaterial({ name: 'PillarLight', map: pillarTex, emissive: 0xffffff, emissiveMap: pillarTex, emissiveIntensity: 1.6 }), { name: 'PillarLight', pos: [0, 1.1, 0.082] }));
    p.add(kit.cylinder(0.11, 0.11, 0.03, K.gold, { name: 'PillarCap', pos: [0, 2.215, 0], segments: 32 }));
    g.add(p);
  }
  // ---- the betting console in front ----
  const D = M.desk;
  const deskTex = kit.canvasTexture(2048, 512, (c, w, h) => {
    c.fillStyle = '#07060b'; c.fillRect(0, 0, w, h);
    const sx = w / D.w, sz = h / D.d, X = (x) => (x + D.w / 2) * sx, Z = (z) => (z - (D.z - D.d / 2)) * sz;
    const vals = ['1', '2', '5', '10', '20', '40'], pays = ['1 TO 1', '2 TO 1', '5 TO 1', '10 TO 1', '20 TO 1', '40 TO 1'];
    M.spots.forEach((x, i) => {
      const st = STYLE[vals[i]], cx = X(x), cz = Z(M.spotZ), r = M.spotR * sx;
      const gl = c.createRadialGradient(cx, cz, r * 0.2, cx, cz, r * 1.35); gl.addColorStop(0, st.a + 'aa'); gl.addColorStop(0.7, st.b + '55'); gl.addColorStop(1, 'rgba(0,0,0,0)'); c.fillStyle = gl; c.fillRect(cx - r * 1.4, cz - r * 1.4, r * 2.8, r * 2.8);
      c.lineWidth = 9; c.strokeStyle = st.a; c.beginPath(); c.arc(cx, cz, r, 0, 7); c.stroke(); c.lineWidth = 3; c.strokeStyle = '#f4d27a'; c.beginPath(); c.arc(cx, cz, r - 13, 0, 7); c.stroke();
      c.font = `900 ${vals[i].length > 1 ? 84 : 100}px Arial Black, Arial`; c.textAlign = 'center'; c.textBaseline = 'middle'; c.lineWidth = 8; c.strokeStyle = 'rgba(0,0,0,0.6)'; c.strokeText(vals[i], cx, cz - 10); c.fillStyle = '#ffffff'; c.fillText(vals[i], cx, cz - 10);
      c.font = '700 26px Arial'; c.fillStyle = st.a; c.fillText(pays[i], cx, cz + 58);
    });
    c.font = '700 22px Arial'; c.fillStyle = 'rgba(244,210,122,0.85)'; c.textAlign = 'center';
    c.fillText('2x · 7x MULTIPLIERS: THE WHEEL SPINS AGAIN AND EVERY WIN IS MULTIPLIED  ·  PLAY MONEY', w / 2, h - 22);
  });
  deskTex.anisotropy = 8;
  const desk = kit.group('Console', { pos: [0, 0, D.z] });
  desk.add(kit.roundedBox(D.w + 0.06, 0.05, D.d + 0.06, 0.02, K.gold, { name: 'DeskRim', pos: [0, D.y - 0.03, 0] }));
  const top = new THREE.Mesh(new THREE.PlaneGeometry(D.w, D.d), new THREE.MeshPhysicalMaterial({ name: 'DeskTop', map: deskTex, emissive: 0xffffff, emissiveMap: deskTex, emissiveIntensity: 0.55, roughness: 0.12, clearcoat: 1, clearcoatRoughness: 0.03 }));
  top.rotation.x = -Math.PI / 2; top.position.y = D.y + 0.0005; top.name = 'DeskTop'; desk.add(top);
  desk.add(kit.roundedBox(D.w, D.y - 0.06, D.d - 0.06, 0.03, K.lacquer, { name: 'DeskBody', pos: [0, (D.y - 0.06) / 2, 0] }));
  // the front panel: a light strip and the logo
  const frontTex = kit.canvasTexture(1024, 256, (c, w, h) => { const lg = c.createLinearGradient(0, 0, w, 0); lg.addColorStop(0, '#3a0d6e'); lg.addColorStop(0.5, '#c24dff'); lg.addColorStop(1, '#3a0d6e'); c.fillStyle = '#0a0710'; c.fillRect(0, 0, w, h); c.fillStyle = lg; c.fillRect(0, 18, w, 10); c.fillRect(0, h - 28, w, 10);
    const gg = c.createLinearGradient(0, 60, 0, 200); gg.addColorStop(0, '#fff3c0'); gg.addColorStop(0.5, '#e9c46a'); gg.addColorStop(1, '#a8761c'); c.fillStyle = gg; c.font = '900 92px Georgia, serif'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText('ZOHAR  MONEY  WHEEL', w / 2, h / 2 + 4); });
  desk.add(kit.box(D.w - 0.04, D.y - 0.14, 0.004, new THREE.MeshStandardMaterial({ name: 'DeskFront', map: frontTex, emissive: 0xffffff, emissiveMap: frontTex, emissiveIntensity: 0.9, roughness: 0.3 }), { name: 'DeskFront', pos: [0, (D.y - 0.06) / 2, D.d / 2 - 0.028] }));
  g.add(desk);
  // soft floor shadow
  const shadowTex = kit.canvasTexture(256, 256, (c, w, h) => { const rg = c.createRadialGradient(w / 2, h / 2, 10, w / 2, h / 2, w / 2); rg.addColorStop(0, 'rgba(0,0,0,0.6)'); rg.addColorStop(0.6, 'rgba(0,0,0,0.3)'); rg.addColorStop(1, 'rgba(0,0,0,0)'); c.fillStyle = rg; c.fillRect(0, 0, w, h); });
  const shadow = new THREE.Mesh(new THREE.PlaneGeometry(3.8, 3.4), new THREE.MeshBasicMaterial({ name: 'FloorShadow', map: shadowTex, transparent: true, depthWrite: false }));
  shadow.rotation.x = -Math.PI / 2; shadow.position.set(0, 0.002, 0.3); shadow.name = 'FloorShadow'; g.add(shadow);
  g.traverse((o) => { if (o.isMesh && o.name !== 'FloorShadow') { o.castShadow = true; o.receiveShadow = true; } });
  return g;
}
