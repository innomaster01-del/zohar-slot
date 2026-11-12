// The placement marker: a ZOHAR casino chip of light lying on the floor where the game will stand, about 0.5 m across.
// Our own design: a ring of red and white edge inserts, a gold ring with ZOHAR lettering, and in the middle a gold "Z"
// on a radiant star (zohar = radiance). Around it a dashed halo and four aiming corners.
// Animated (clip "Play", 4 s, loops): the edge ring turns slowly, the halo turns the other way, the glow breathes and the
// corners pulse in and out. Unlit, so it glows over the camera picture.
const RED = '#ff3b47', GOLD = '#ffcf5a', WHITE = '#ffffff';
function tex(THREE, size, draw) { const c = document.createElement('canvas'); c.width = c.height = size; draw(c.getContext('2d'), size); const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; t.userData.mimeType = 'image/png'; return t; }
const unlit = (THREE, name, map, opacity = 1) => new THREE.MeshBasicMaterial({ name, map, transparent: true, opacity, depthWrite: false, toneMapped: false, side: THREE.DoubleSide });
function flat(THREE, g, name, r, mat, y) { const geo = new THREE.PlaneGeometry(2 * r, 2 * r); geo.rotateX(-Math.PI / 2); const m = new THREE.Mesh(geo, mat); m.name = name; m.position.y = y; g.add(m); return m; }

export function reticle(THREE) {
  const g = new THREE.Group(); g.name = 'Reticle'; const R = 0.25, tracks = [], dur = 4;
  // the glow under it all
  const glow = flat(THREE, g, 'Glow', R * 1.25, unlit(THREE, 'ReticleGlow', tex(THREE, 256, (c, s) => { const gr = c.createRadialGradient(s / 2, s / 2, s * 0.1, s / 2, s / 2, s / 2); gr.addColorStop(0, 'rgba(255,59,71,0.35)'); gr.addColorStop(0.55, 'rgba(255,59,71,0.14)'); gr.addColorStop(1, 'rgba(255,59,71,0)'); c.fillStyle = gr; c.fillRect(0, 0, s, s); })), 0.002);
  // the edge ring: eight red inserts with white between, like a real chip's edge spots
  const ring = flat(THREE, g, 'EdgeRing', R, unlit(THREE, 'ReticleEdge', tex(THREE, 1024, (c, s) => {
    const m = s / 2; c.translate(m, m);
    c.lineWidth = s * 0.006; c.strokeStyle = RED; c.beginPath(); c.arc(0, 0, m * 0.985, 0, 7); c.stroke();
    for (let i = 0; i < 16; i++) { const a0 = i * Math.PI / 8 + 0.02, a1 = (i + 1) * Math.PI / 8 - 0.02;
      c.fillStyle = i % 2 ? 'rgba(255,255,255,0.92)' : RED; c.beginPath(); c.arc(0, 0, m * 0.95, a0, a1); c.arc(0, 0, m * 0.76, a1, a0, true); c.closePath(); c.fill(); }
    // a fine notch line on each white insert
    c.strokeStyle = RED; c.lineWidth = s * 0.004; for (let i = 1; i < 16; i += 2) { const a = (i + 0.5) * Math.PI / 8; c.beginPath(); c.moveTo(Math.cos(a) * m * 0.79, Math.sin(a) * m * 0.79); c.lineTo(Math.cos(a) * m * 0.92, Math.sin(a) * m * 0.92); c.stroke(); }
  })), 0.004);
  // the face: a gold ring with lettering, and the emblem
  flat(THREE, g, 'Face', R * 0.74, unlit(THREE, 'ReticleFace', tex(THREE, 1024, (c, s) => {
    const m = s / 2; c.translate(m, m);
    c.fillStyle = 'rgba(20,4,8,0.45)'; c.beginPath(); c.arc(0, 0, m * 0.99, 0, 7); c.fill();
    c.strokeStyle = GOLD; c.lineWidth = s * 0.012; c.beginPath(); c.arc(0, 0, m * 0.97, 0, 7); c.stroke();
    c.lineWidth = s * 0.005; c.beginPath(); c.arc(0, 0, m * 0.74, 0, 7); c.stroke();
    // lettering round the ring
    c.fillStyle = GOLD; c.font = `900 ${s * 0.07}px Arial Black, Arial`; c.textAlign = 'center'; c.textBaseline = 'middle';
    const words = '★ ZOHAR ★ PLAY ★ ZOHAR ★ PLAY '; const n = words.length;
    for (let i = 0; i < n; i++) { const a = -Math.PI / 2 + i / n * Math.PI * 2; c.save(); c.rotate(a + Math.PI / 2); c.fillText(words[i], 0, -m * 0.855); c.restore(); }
    // the radiant star
    c.save(); const rays = 16; c.fillStyle = 'rgba(255,207,90,0.9)'; c.beginPath();
    for (let i = 0; i < rays * 2; i++) { const a = i * Math.PI / rays - Math.PI / 2, r = i % 2 ? m * 0.3 : m * 0.62; c.lineTo(Math.cos(a) * r, Math.sin(a) * r); } c.closePath(); c.fill(); c.restore();
    c.fillStyle = 'rgba(30,6,10,0.92)'; c.beginPath(); c.arc(0, 0, m * 0.4, 0, 7); c.fill();
    c.strokeStyle = GOLD; c.lineWidth = s * 0.008; c.beginPath(); c.arc(0, 0, m * 0.4, 0, 7); c.stroke();
    // the Z
    const z = m * 0.42; c.lineJoin = 'miter'; c.font = `900 ${z}px Arial Black, Arial`; c.textAlign = 'center'; c.textBaseline = 'middle';
    const gr = c.createLinearGradient(0, -z / 2, 0, z / 2); gr.addColorStop(0, '#fff3c4'); gr.addColorStop(0.5, GOLD); gr.addColorStop(1, '#c98a1a'); c.fillStyle = gr; c.fillText('Z', 0, z * 0.04);
  })), 0.005);
  // the halo: a thin dashed ring outside the chip
  const halo = flat(THREE, g, 'Halo', R * 1.2, unlit(THREE, 'ReticleHalo', tex(THREE, 1024, (c, s) => { const m = s / 2; c.translate(m, m); c.strokeStyle = 'rgba(255,255,255,0.85)'; c.lineWidth = s * 0.006; c.setLineDash([s * 0.03, s * 0.025]); c.beginPath(); c.arc(0, 0, m * 0.96, 0, 7); c.stroke(); c.setLineDash([]); c.fillStyle = RED; for (let i = 0; i < 4; i++) { const a = i * Math.PI / 2; c.beginPath(); c.arc(Math.cos(a) * m * 0.96, Math.sin(a) * m * 0.96, s * 0.012, 0, 7); c.fill(); } })), 0.003);
  // four aiming corners
  const cornerMat = unlit(THREE, 'ReticleCorner', tex(THREE, 256, (c, s) => { c.strokeStyle = RED; c.lineWidth = s * 0.07; c.lineCap = 'round'; c.beginPath(); c.moveTo(s * 0.1, s * 0.9); c.lineTo(s * 0.1, s * 0.1); c.lineTo(s * 0.9, s * 0.1); c.stroke(); }));
  const corners = [];
  for (let i = 0; i < 4; i++) { const k = new THREE.Group(); k.name = 'Corner' + i; const geo = new THREE.PlaneGeometry(0.09, 0.09); geo.rotateX(-Math.PI / 2); const m = new THREE.Mesh(geo, cornerMat); m.name = 'CornerMark' + i; m.rotation.y = (i + 1) * Math.PI / 2; k.add(m); g.add(k); corners.push(k); }

  // ---------- the animation ----------
  const steps = 16, times = [...Array(steps + 1).keys()].map((k) => k / steps * dur);
  const spin = (node, turns) => { const q = new THREE.Quaternion(), vals = []; for (const t of times) { q.setFromAxisAngle(new THREE.Vector3(0, 1, 0), turns * 2 * Math.PI * t / dur); vals.push(...q.toArray()); } tracks.push(new THREE.QuaternionKeyframeTrack(`${node.name}.quaternion`, times, vals)); };
  spin(ring, 0.25); spin(halo, -0.25);
  { const vals = []; for (const t of times) { const k = 1 + 0.12 * Math.sin(t / dur * Math.PI * 4); vals.push(k, 1, k); } tracks.push(new THREE.VectorKeyframeTrack(`${glow.name}.scale`, times, vals)); }
  corners.forEach((k, i) => { const a = Math.PI / 4 + i * Math.PI / 2, vals = [];
    // corner i sits on the diagonal; its mark's own corner points outward
    for (const t of times) { const d = R * 1.32 + 0.025 * (0.5 + 0.5 * Math.sin(t / dur * Math.PI * 4)); vals.push(Math.cos(a) * d * -1, 0.006, Math.sin(a) * d); }
    k.position.set(vals[0], vals[1], vals[2]); tracks.push(new THREE.VectorKeyframeTrack(`${k.name}.position`, times, vals)); });
  return { scene: g, animations: [new THREE.AnimationClip('Play', dur, tracks)] };
}
export async function build(THREE) { return reticle(THREE).scene; }
