// Playing cards: face images (drawn once here, shipped as JPGs) + one card mesh (CardFace / CardBack / CardEdge).
import { L } from './shared/layout.js';

export const CW = 400, CH = 560;            // face image size (poker 63.5 × 88.9 mm ratio ≈ 0.714)
const SUITS = { S: { ch: '♠', red: false }, H: { ch: '♥', red: true }, D: { ch: '♦', red: true }, C: { ch: '♣', red: false } };
export const RANKS = ['A', '2', '3', '4', '5', '6', '7', '8', '9', '10', 'J', 'Q', 'K'];

// pip layouts (x, y in 0..1 inside the pip area; flipped = drawn upside down)
const PIPS = {
  2: [[0.5, 0], [0.5, 1]], 3: [[0.5, 0], [0.5, 0.5], [0.5, 1]], 4: [[0, 0], [1, 0], [0, 1], [1, 1]],
  5: [[0, 0], [1, 0], [0.5, 0.5], [0, 1], [1, 1]], 6: [[0, 0], [1, 0], [0, 0.5], [1, 0.5], [0, 1], [1, 1]],
  7: [[0, 0], [1, 0], [0.5, 0.25], [0, 0.5], [1, 0.5], [0, 1], [1, 1]], 8: [[0, 0], [1, 0], [0.5, 0.25], [0, 0.5], [1, 0.5], [0.5, 0.75], [0, 1], [1, 1]],
  9: [[0, 0], [1, 0], [0, 1 / 3], [1, 1 / 3], [0.5, 0.5], [0, 2 / 3], [1, 2 / 3], [0, 1], [1, 1]],
  10: [[0, 0], [1, 0], [0.5, 1 / 6], [0, 1 / 3], [1, 1 / 3], [0, 2 / 3], [1, 2 / 3], [0.5, 5 / 6], [0, 1], [1, 1]],
};

function roundRect(c, x, y, w, h, r) { c.beginPath(); c.roundRect(x, y, w, h, r); }

export function drawFace(c, rank, suit) {
  const s = SUITS[suit], col = s.red ? '#c8102e' : '#141414', W = CW, H = CH;
  // card stock
  const lg = c.createLinearGradient(0, 0, W, H); lg.addColorStop(0, '#fffdf8'); lg.addColorStop(1, '#f3efe6'); c.fillStyle = lg; c.fillRect(0, 0, W, H);
  c.textAlign = 'center'; c.textBaseline = 'middle';
  const corner = () => {
    c.fillStyle = col; c.font = `700 ${rank === '10' ? 54 : 62}px "Times New Roman", Georgia, serif`; c.fillText(rank, 44, 52);
    c.font = '48px "DejaVu Sans", "Segoe UI Symbol", serif'; c.fillText(s.ch, 44, 106);
  };
  corner(); c.save(); c.translate(W, H); c.rotate(Math.PI); corner(); c.restore();
  const pip = (x, y, size, flip) => { c.save(); c.translate(x, y); if (flip) c.rotate(Math.PI); c.fillStyle = col; c.font = `${size}px "DejaVu Sans", "Segoe UI Symbol", serif`; c.fillText(s.ch, 0, 0); c.restore(); };
  if (rank === 'A') { pip(W / 2, H / 2, suit === 'S' ? 230 : 170, false); return; }
  const n = +rank;
  if (n) {
    const x0 = 120, x1 = W - 120, y0 = 112, y1 = H - 112;
    for (const [u, v] of PIPS[n]) pip(x0 + (x1 - x0) * u, y0 + (y1 - y0) * v, 92, v > 0.5);
    return;
  }
  // court cards: framed panel, mirrored halves, crown + letter + suit
  const fx = 82, fy = 74, fw = W - 164, fh = H - 148;
  const pg = c.createLinearGradient(0, fy, 0, fy + fh); pg.addColorStop(0, s.red ? '#fbe3d6' : '#e3e8f5'); pg.addColorStop(0.5, '#fff8e8'); pg.addColorStop(1, s.red ? '#fbe3d6' : '#e3e8f5');
  roundRect(c, fx, fy, fw, fh, 14); c.fillStyle = pg; c.fill(); c.lineWidth = 4; c.strokeStyle = '#b8902f'; c.stroke();
  roundRect(c, fx + 8, fy + 8, fw - 16, fh - 16, 10); c.lineWidth = 2; c.strokeStyle = col; c.stroke();
  const half = (flip) => {
    c.save(); if (flip) { c.translate(W, H); c.rotate(Math.PI); }
    const cx = W / 2, top = fy + 16;
    // crown (K, Q) or feathered cap (J)
    c.fillStyle = '#d4a52c'; c.strokeStyle = '#6b4b0c'; c.lineWidth = 3;
    c.beginPath();
    if (rank === 'J') { c.moveTo(cx - 46, top + 50); c.quadraticCurveTo(cx - 36, top + 6, cx + 8, top + 10); c.quadraticCurveTo(cx + 54, top + 16, cx + 46, top + 50); c.closePath(); }
    else { const w = rank === 'K' ? 54 : 46; c.moveTo(cx - w, top + 50); c.lineTo(cx - w, top + 14); c.lineTo(cx - w / 2, top + 34); c.lineTo(cx, top + 2); c.lineTo(cx + w / 2, top + 34); c.lineTo(cx + w, top + 14); c.lineTo(cx + w, top + 50); c.closePath(); }
    c.fill(); c.stroke();
    for (const dx of [-24, 0, 24]) { c.beginPath(); c.arc(cx + dx, top + 42, 4.5, 0, 7); c.fillStyle = s.red ? '#1b4fb5' : '#c8102e'; c.fill(); }
    // letter + suit
    c.fillStyle = col; c.font = '800 92px "Times New Roman", Georgia, serif'; c.fillText(rank, cx, top + 106);
    c.font = '50px "DejaVu Sans", "Segoe UI Symbol", serif'; c.fillText(s.ch, cx, top + 170);
    c.restore();
  };
  half(false); half(true);
  c.strokeStyle = 'rgba(184,144,47,0.7)'; c.lineWidth = 2; c.beginPath(); c.moveTo(fx + 20, H / 2); c.lineTo(fx + fw - 20, H / 2); c.stroke();
}

export function drawBack(c) {
  const W = CW, H = CH;
  c.fillStyle = '#fffdf8'; c.fillRect(0, 0, W, H);
  roundRect(c, 18, 18, W - 36, H - 36, 18); const lg = c.createLinearGradient(0, 0, W, H); lg.addColorStop(0, '#8e1022'); lg.addColorStop(1, '#5c0814'); c.fillStyle = lg; c.fill();
  c.save(); roundRect(c, 18, 18, W - 36, H - 36, 18); c.clip();
  c.strokeStyle = 'rgba(255,215,140,0.28)'; c.lineWidth = 2;
  for (let i = -H; i < W + H; i += 18) { c.beginPath(); c.moveTo(i, 0); c.lineTo(i + H, H); c.stroke(); c.beginPath(); c.moveTo(i, H); c.lineTo(i + H, 0); c.stroke(); }
  c.restore();
  roundRect(c, 30, 30, W - 60, H - 60, 12); c.lineWidth = 4; c.strokeStyle = '#e2bf62'; c.stroke();
  c.beginPath(); c.ellipse(W / 2, H / 2, 112, 70, 0, 0, 7); c.fillStyle = '#5c0814'; c.fill(); c.lineWidth = 4; c.stroke();
  c.fillStyle = '#f2d27a'; c.font = '900 54px Georgia, serif'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText('ZOHAR', W / 2, H / 2 + 2);
}

// Card mesh lying flat, face up (+Y), long side along z, top of the image toward -z (dealer side) so it reads right for the player.
export function cardMesh(THREE, faceTex, backTex) {
  const w = L.cardW, h = L.cardH, r = 0.0032, t = 0.0004;
  const sh = new THREE.Shape(); sh.moveTo(-w / 2 + r, -h / 2); sh.lineTo(w / 2 - r, -h / 2); sh.quadraticCurveTo(w / 2, -h / 2, w / 2, -h / 2 + r); sh.lineTo(w / 2, h / 2 - r); sh.quadraticCurveTo(w / 2, h / 2, w / 2 - r, h / 2); sh.lineTo(-w / 2 + r, h / 2); sh.quadraticCurveTo(-w / 2, h / 2, -w / 2, h / 2 - r); sh.lineTo(-w / 2, -h / 2 + r); sh.quadraticCurveTo(-w / 2, -h / 2, -w / 2 + r, -h / 2);
  const g = new THREE.Group(); g.name = 'Card';
  const mk = (top) => {
    const geo = new THREE.ShapeGeometry(sh, 6); const p = geo.attributes.position; const uv = geo.attributes.uv;
    for (let i = 0; i < p.count; i++) { const x = p.getX(i), y = p.getY(i); uv.setXY(i, x / w + 0.5, y / h + 0.5); }
    // shape XY → card XZ: y (image up) → -z
    geo.rotateX(-Math.PI / 2); if (!top) geo.rotateZ(Math.PI);
    geo.translate(0, top ? t / 2 : -t / 2, 0); geo.computeVertexNormals(); return geo;
  };
  const face = new THREE.Mesh(mk(true), new THREE.MeshStandardMaterial({ name: 'CardFace', map: faceTex, roughness: 0.62 })); face.name = 'CardFace';
  const back = new THREE.Mesh(mk(false), new THREE.MeshStandardMaterial({ name: 'CardBack', map: backTex, roughness: 0.62 })); back.name = 'CardBack';
  const eg = new THREE.ExtrudeGeometry(sh, { depth: t * 0.96, bevelEnabled: false, curveSegments: 6 }); eg.rotateX(-Math.PI / 2); eg.translate(0, -t * 0.48, 0);
  eg.clearGroups(); eg.addGroup(0, eg.index ? eg.index.count : eg.attributes.position.count, 0);
  const edge = new THREE.Mesh(eg, new THREE.MeshStandardMaterial({ name: 'CardEdge', color: 0xf4f1e8, roughness: 0.7 })); edge.name = 'CardEdge';
  g.add(edge, face, back); g.traverse((o) => { if (o.isMesh) { o.castShadow = true; o.receiveShadow = true; } });
  return g;
}
