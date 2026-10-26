// Casino chips 39 × 3.3 mm, one model per value. Clay chip look: rounded edge with striped edge inserts,
// recessed printed inlay on both faces (value, ZOHAR, ring of dashes). Face images are also shipped for the app's chip buttons.
import { L } from './shared/layout.js';
export const CHIPS = [
  { v: 5, base: '#b81d2a', spot: '#f4f1ea', accent: '#f4f1ea', ink: '#b81d2a' },
  { v: 25, base: '#14783a', spot: '#f4f1ea', accent: '#f2d27a', ink: '#14783a' },
  { v: 100, base: '#121214', spot: '#f4f1ea', accent: '#e0b84f', ink: '#121214' },
  { v: 500, base: '#55247e', spot: '#f2d27a', accent: '#f2d27a', ink: '#55247e' },
];

function noise(c, w, h, n, a) { let seed = 11; const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647); for (let i = 0; i < n; i++) { c.fillStyle = rnd() > 0.5 ? `rgba(255,255,255,${a * rnd()})` : `rgba(0,0,0,${a * 1.3 * rnd()})`; c.fillRect(rnd() * w, rnd() * h, 1.5, 1.5); } }

export function drawChipFace(c, ch, S = 512) {
  const cx = S / 2, cy = S / 2, R = S / 2;
  c.clearRect(0, 0, S, S);
  c.save(); c.beginPath(); c.arc(cx, cy, R, 0, 7); c.clip();
  c.fillStyle = ch.base; c.fillRect(0, 0, S, S);
  // six edge inserts, each three stripes, reaching in from the rim
  for (let i = 0; i < 6; i++) {
    c.save(); c.translate(cx, cy); c.rotate(i * Math.PI / 3 + Math.PI / 6);
    c.fillStyle = ch.spot; c.fillRect(-R * 0.13, -R, R * 0.26, R * 0.2);
    c.fillStyle = ch.base; c.fillRect(-R * 0.045, -R, R * 0.025, R * 0.2); c.fillRect(R * 0.02, -R, R * 0.025, R * 0.2);
    c.restore();
  }
  // ring of small dashes
  c.save(); c.translate(cx, cy); c.fillStyle = ch.accent;
  for (let i = 0; i < 48; i++) { c.save(); c.rotate(i * Math.PI / 24); c.fillRect(-R * 0.012, -R * 0.775, R * 0.024, R * 0.05); c.restore(); }
  c.restore();
  // inlay: cream disc with a gold rim and a thin coloured ring
  const g = c.createRadialGradient(cx, cy - R * 0.15, R * 0.05, cx, cy, R * 0.66); g.addColorStop(0, '#fffdf6'); g.addColorStop(1, '#ece4cf');
  c.fillStyle = g; c.beginPath(); c.arc(cx, cy, R * 0.66, 0, 7); c.fill();
  c.lineWidth = R * 0.03; c.strokeStyle = '#c9a03c'; c.beginPath(); c.arc(cx, cy, R * 0.665, 0, 7); c.stroke();
  c.lineWidth = R * 0.012; c.strokeStyle = ch.base; c.beginPath(); c.arc(cx, cy, R * 0.6, 0, 7); c.stroke();
  // value
  c.fillStyle = ch.ink; c.textAlign = 'center'; c.textBaseline = 'middle';
  c.font = `900 ${ch.v >= 100 ? R * 0.42 : R * 0.5}px Georgia, "Times New Roman", serif`; c.fillText(String(ch.v), cx, cy + R * 0.03);
  // ZOHAR on top, PLAY MONEY at the bottom, along the ring
  const arc = (text, rr, top, size) => {
    c.save(); c.font = `700 ${size}px Arial`; c.fillStyle = ch.ink; const total = c.measureText(text).width * 1.15;
    let a = -total / rr / 2;
    for (const chr of text) { const w = c.measureText(chr).width * 1.15; const t = a + w / rr / 2;
      c.save(); c.translate(cx, cy); c.rotate(top ? t : -t); c.translate(0, top ? -rr : rr); if (!top) c.rotate(0); c.fillText(chr, 0, 0); c.restore(); a += w / rr; }
    c.restore();
  };
  arc('ZOHAR', R * 0.47, true, R * 0.1); arc('PLAY MONEY', R * 0.47, false, R * 0.075);
  noise(c, S, S, 9000, 0.06);
  // soft shading to the rim
  const v = c.createRadialGradient(cx, cy, R * 0.7, cx, cy, R); v.addColorStop(0, 'rgba(0,0,0,0)'); v.addColorStop(1, 'rgba(0,0,0,0.18)'); c.fillStyle = v; c.fillRect(0, 0, S, S);
  c.restore();
}

export function chipMesh(THREE, kit, ch, lo = false) {
  const r = L.chipD / 2, h = L.chipH, b = 0.0006;         // edge bevel
  const face = kit.canvasTexture(512, 512, (c) => drawChipFace(c, ch, 512)); face.anisotropy = 8;
  const edge = kit.canvasTexture(1024, 64, (c, w, hh) => {
    c.fillStyle = ch.base; c.fillRect(0, 0, w, hh);
    for (let i = 0; i < 6; i++) { const x0 = (i + 0.5) * w / 6 - w * 0.022; c.fillStyle = ch.spot; c.fillRect(x0, 0, w * 0.044, hh); c.fillStyle = ch.base; c.fillRect(x0 + w * 0.012, 0, w * 0.004, hh); c.fillRect(x0 + w * 0.028, 0, w * 0.004, hh); }
    noise(c, w, hh, 4000, 0.08);
  });
  const mFace = new THREE.MeshPhysicalMaterial({ name: `Chip${ch.v}Face`, map: face, roughness: 0.5, clearcoat: 0.25, clearcoatRoughness: 0.35 });
  const mEdge = new THREE.MeshPhysicalMaterial({ name: `Chip${ch.v}Edge`, map: edge, roughness: 0.55, clearcoat: 0.15, clearcoatRoughness: 0.4 });
  const g = new THREE.Group(); g.name = `Chip${ch.v}`;
  // edge ring with rounded corners (lathe), pivot at the bottom
  const prof = [];
  const q = lo ? 1 : 4;
  for (let i = 0; i <= q; i++) { const a = -Math.PI / 2 + (i / q) * Math.PI / 2; prof.push(new THREE.Vector2(r - b + Math.cos(a) * b, b + Math.sin(a) * b)); }
  for (let i = 0; i <= q; i++) { const a = (i / q) * Math.PI / 2; prof.push(new THREE.Vector2(r - b + Math.cos(a) * b, h - b + Math.sin(a) * b)); }
  prof.unshift(new THREE.Vector2(r - b - 0.0008, 0)); prof.push(new THREE.Vector2(r - b - 0.0008, h));
  const lathe = new THREE.LatheGeometry(lo ? prof.slice(1, -1) : prof, lo ? 28 : 72);
  const em = new THREE.Mesh(lathe, mEdge); em.name = `Chip${ch.v}Rim`; g.add(em);
  // faces: planar UVs, a hair inside the rim
  const mk = (top) => { const geo = new THREE.CircleGeometry(r - b - 0.0004, lo ? 28 : 72); geo.rotateX(top ? -Math.PI / 2 : Math.PI / 2); geo.translate(0, top ? h - 0.0001 : 0.0001, 0); const m = new THREE.Mesh(geo, mFace); m.name = `Chip${ch.v}${top ? 'Top' : 'Bottom'}`; return m; };
  g.add(mk(true), mk(false));
  g.traverse((o) => { if (o.isMesh) { o.castShadow = true; o.receiveShadow = true; } });
  return g;
}
