// Casino chips 39 × 3.3 mm, one model per value: printed face, striped edge.
import { L } from './shared/layout.js';
export const CHIPS = [
  { v: 5, base: '#c8262e', spot: '#f5f5f2', ink: '#ffffff' },
  { v: 25, base: '#1e8a3e', spot: '#f5f5f2', ink: '#ffffff' },
  { v: 100, base: '#151515', spot: '#f5f5f2', ink: '#f2d16b' },
  { v: 500, base: '#5b2a86', spot: '#f2d16b', ink: '#ffffff' },
];
export function chipMesh(THREE, kit, ch) {
  const r = L.chipD / 2, h = L.chipH;
  const face = kit.canvasTexture(256, 256, (c, w, hh) => {
    const cx = w / 2, cy = hh / 2;
    c.fillStyle = ch.base; c.fillRect(0, 0, w, hh);
    // edge spots seen on the face rim
    c.fillStyle = ch.spot; for (let i = 0; i < 6; i++) { c.save(); c.translate(cx, cy); c.rotate(i * Math.PI / 3); c.fillRect(-14, -128, 28, 26); c.restore(); }
    c.strokeStyle = ch.spot; c.lineWidth = 3; c.beginPath(); c.arc(cx, cy, 92, 0, 7); c.stroke();
    // inlay
    const g = c.createRadialGradient(cx, cy - 20, 10, cx, cy, 80); g.addColorStop(0, '#ffffff'); g.addColorStop(1, '#e9e3d4'); c.fillStyle = g; c.beginPath(); c.arc(cx, cy, 78, 0, 7); c.fill();
    c.strokeStyle = ch.base; c.lineWidth = 4; c.beginPath(); c.arc(cx, cy, 70, 0, 7); c.stroke();
    c.fillStyle = ch.base === '#151515' ? '#151515' : ch.base; c.font = `900 ${ch.v >= 100 ? 64 : 76}px Arial`; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText(String(ch.v), cx, cy + 4);
    c.font = '700 18px Arial'; c.fillText('ZOHAR', cx, cy - 46); c.fillText('PLAY', cx, cy + 50);
  });
  const edge = kit.canvasTexture(512, 32, (c, w, hh) => { c.fillStyle = ch.base; c.fillRect(0, 0, w, hh); c.fillStyle = ch.spot; for (let i = 0; i < 6; i++) c.fillRect(i * w / 6 + 10, 0, w / 14, hh); });
  const mFace = new THREE.MeshPhysicalMaterial({ name: `Chip${ch.v}Face`, map: face, roughness: 0.42, clearcoat: 0.4, clearcoatRoughness: 0.3 });
  const mEdge = new THREE.MeshStandardMaterial({ name: `Chip${ch.v}Edge`, map: edge, roughness: 0.5 });
  const geo = new THREE.CylinderGeometry(r, r, h, 48, 1, false);
  geo.translate(0, h / 2, 0);                         // pivot at the bottom: stacks are y = i × h
  const m = new THREE.Mesh(geo, [mEdge, mFace, mFace]); m.name = `Chip${ch.v}`; m.castShadow = true; m.receiveShadow = true;
  return m;
}
