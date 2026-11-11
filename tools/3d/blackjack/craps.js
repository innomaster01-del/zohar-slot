// ZOHAR craps table (Pass Line + Field), real size, in the shared table frame (see shared/layout.js → craps).
// A tub: printed felt, padded leather rail on wooden walls, pyramid rubber on the far wall, the dealer's opening
// with the chip bank, the stick, the dice bowl, the ON/OFF puck and the dice themselves. No third-party art.
import { L } from './shared/layout.js';
import { CHIPS, chipMesh } from './chips.js';
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js';
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js';

const C = L.craps;
const NAMES = { 4: '4', 5: '5', 6: 'SIX', 8: '8', 9: 'NINE', 10: '10' };

/** One face of a casino die: deep red, lighter toward the middle like clear acetate, flush white pips. */
function drawDieFace(c, s, n) {
  const g = c.createRadialGradient(s / 2, s / 2, s * 0.05, s / 2, s / 2, s * 0.75); g.addColorStop(0, '#e2202c'); g.addColorStop(0.6, '#b90f1c'); g.addColorStop(1, '#7c0611');
  c.fillStyle = g; c.fillRect(0, 0, s, s);
  c.strokeStyle = 'rgba(255,170,170,0.35)'; c.lineWidth = s * 0.02; c.strokeRect(s * 0.01, s * 0.01, s * 0.98, s * 0.98);
  const q = s * 0.27, m = s / 2, r = s * 0.095;
  const at = { 1: [[m, m]], 2: [[m - q, m - q], [m + q, m + q]], 3: [[m - q, m - q], [m, m], [m + q, m + q]], 4: [[m - q, m - q], [m + q, m - q], [m - q, m + q], [m + q, m + q]],
    5: [[m - q, m - q], [m + q, m - q], [m, m], [m - q, m + q], [m + q, m + q]], 6: [[m - q, m - q], [m + q, m - q], [m - q, m], [m + q, m], [m - q, m + q], [m + q, m + q]] }[n];
  for (const [x, y] of at) { c.fillStyle = 'rgba(60,0,6,0.5)'; c.beginPath(); c.arc(x + 1.5, y + 2, r, 0, 7); c.fill(); c.fillStyle = '#fffdf7'; c.beginPath(); c.arc(x, y, r, 0, 7); c.fill(); }
}

/** A die: +Y shows 1, −Y 6, +Z 2, −Z 5, +X 3, −X 4 (opposite faces add up to seven). Origin at its centre. */
export function die(THREE, kit, size = C.die) {
  const face = (n) => { const t = kit.canvasTexture(128, 128, (c, w) => drawDieFace(c, w, n)); t.anisotropy = 8; return new THREE.MeshPhysicalMaterial({ name: `Die${n}`, map: t, roughness: 0.1, metalness: 0, clearcoat: 1, clearcoatRoughness: 0.05 }); };
  const m = new THREE.Mesh(new RoundedBoxGeometry(size, size, size, 3, size * 0.045), [3, 4, 1, 6, 2, 5].map(face));
  m.name = 'Die'; m.castShadow = true; const g = kit.group('CrapsDie'); g.add(m); return g;
}

/** The puck: white ON face up; turned over it shows the black OFF face. Origin at the middle of its underside. */
export function puck(THREE, kit) {
  const side = (txt, bg, fg, flip) => { const t = kit.canvasTexture(256, 256, (c, w, h) => {
    c.fillStyle = bg; c.fillRect(0, 0, w, h); c.strokeStyle = fg; c.lineWidth = 10; c.beginPath(); c.arc(w / 2, h / 2, w / 2 - 16, 0, 7); c.stroke();
    c.save(); c.translate(w / 2, h / 2); if (flip) c.scale(-1, 1); c.fillStyle = fg; c.font = `900 ${txt.length > 2 ? 92 : 112}px Arial`; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText(txt, 0, 6); c.restore(); });
    return new THREE.MeshStandardMaterial({ name: `Puck${txt}`, map: t, roughness: 0.35 }); };
  const rim = new THREE.MeshStandardMaterial({ name: 'PuckRim', color: 0xd8d4c8, roughness: 0.4 });
  const m = new THREE.Mesh(new THREE.CylinderGeometry(C.puckD / 2, C.puckD / 2, 0.012, 48), [rim, side('ON', '#f4f1e8', '#111111', false), side('OFF', '#0c0c0e', '#f4f1e8', true)]);
  m.position.y = 0.006; m.rotation.y = Math.PI / 2; m.name = 'Puck'; m.castShadow = true; const g = kit.group('CrapsPuck'); g.add(m); return g;
}

export async function build(THREE, kit) {
  const g = kit.group('CrapsTable');
  const FX = C.feltX, ZF = C.zFar, ZN = C.zNear, WT = C.wallT, WH = C.wallH, Y = L.feltY;

  // ---------------- felt ----------------
  const X0 = -FX - 0.02, Z0 = ZF - 0.02, SPAN = 2 * FX + 0.04, ZSPAN = ZN - ZF + 0.04, FW = 2400, FH = Math.round(FW * ZSPAN / SPAN);
  const px = (x) => (x - X0) / SPAN * FW, py = (z) => (z - Z0) / ZSPAN * FH, S = FW / SPAN;
  const feltTex = kit.canvasTexture(FW, FH, (c, w, h) => {
    c.fillStyle = '#075233'; c.fillRect(0, 0, w, h);
    const rg = c.createRadialGradient(px(0), py(0.5), 60, px(0), py(0.5), 1500); rg.addColorStop(0, 'rgba(50,165,105,0.30)'); rg.addColorStop(0.6, 'rgba(0,0,0,0)'); rg.addColorStop(1, 'rgba(0,10,5,0.5)');
    c.fillStyle = rg; c.fillRect(0, 0, w, h);
    let seed = 7; const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
    for (let i = 0; i < 150000; i++) { const x = rnd() * w, y = rnd() * h, a = rnd() * 0.05; c.fillStyle = rnd() > 0.5 ? `rgba(255,255,255,${a})` : `rgba(0,0,0,${a * 1.5})`; c.fillRect(x, y, 1 + rnd() * 2.5, 1); }
    const white = 'rgba(250,246,232,0.95)', gold = '#f0c95c', red = '#e2333c';
    c.lineJoin = 'round'; c.textAlign = 'center'; c.textBaseline = 'middle';
    const box = (x, z, bw, bd, r = 10, lw = 5, col = white) => { c.strokeStyle = col; c.lineWidth = lw; c.beginPath(); c.roundRect(px(x - bw / 2), py(z - bd / 2), bw * S, bd * S, r); c.stroke(); };
    const text = (x, z, t, size, col, font = 'Georgia, serif', weight = '900', rot = 0) => { c.save(); c.translate(px(x), py(z)); c.rotate(rot); c.fillStyle = col; c.font = `${weight} ${size}px ${font}`; c.shadowColor = 'rgba(0,0,0,0.45)'; c.shadowBlur = 3; c.fillText(t, 0, 0); c.restore(); };
    // the point numbers, the way they run along a real layout
    C.numbers.forEach((n, i) => {
      const x = C.pointX0 + i * C.pointDx;
      c.fillStyle = 'rgba(0,0,0,0.14)'; c.fillRect(px(x - C.pointW / 2), py(C.pointZ - C.pointD / 2), C.pointW * S, C.pointD * S);
      box(x, C.pointZ, C.pointW, C.pointD, 8, 5);
      text(x, C.pointZ + 0.012, NAMES[n], NAMES[n].length > 2 ? 62 : 96, gold);
    });
    // the dice come to rest here
    c.save(); c.globalAlpha = 0.34; c.translate(px(0), py(0.425)); c.fillStyle = gold;
    const cw = 34; c.beginPath(); c.moveTo(-cw - 190, -14); c.lineTo(-cw - 190, -40); c.lineTo(-cw / 2 - 190, -26); c.lineTo(-190, -48); c.lineTo(cw / 2 - 190, -26); c.lineTo(cw - 190, -40); c.lineTo(cw - 190, -14); c.closePath(); c.fill();
    c.font = '900 64px Georgia, serif'; c.fillText('ZOHAR', 20, 4); c.restore();
    // the field
    const F = C.field;
    c.fillStyle = 'rgba(0,0,0,0.12)'; c.beginPath(); c.roundRect(px(F[0] - C.fieldW / 2), py(F[2] - C.fieldD / 2), C.fieldW * S, C.fieldD * S, 16); c.fill();
    box(F[0], F[2], C.fieldW, C.fieldD, 16, 6);
    const nums = [2, 3, 4, 9, 10, 11, 12];
    nums.forEach((n, i) => {
      const x = -0.50 + i * 0.125, z = F[2] - 0.022;
      if (n === 2 || n === 12) { c.strokeStyle = gold; c.lineWidth = 5; c.beginPath(); c.arc(px(x), py(z), 0.043 * S, 0, 7); c.stroke(); text(x, z + 0.058, n === 2 ? 'PAYS DOUBLE' : 'PAYS TRIPLE', 15, gold, 'Arial', '800'); }
      text(x, z + 0.002, String(n), 60, n === 2 || n === 12 ? gold : white);
      if (i < nums.length - 1) text(x + 0.0625, z, '·', 44, white);
    });
    text(-0.125, F[2] + 0.052, 'F  I  E  L  D', 34, white);
    // where the field bet goes
    c.setLineDash([9, 7]); c.strokeStyle = 'rgba(250,246,232,0.55)'; c.lineWidth = 3; c.beginPath(); c.arc(px(C.fieldChips[0]), py(C.fieldChips[2]), 0.05 * S, 0, 7); c.stroke(); c.setLineDash([]);
    // the pass line: along the player's side and up both ends
    const P = C.pass, hw = C.passW / 2, d = C.passD, zs = C.passSideZ, zo = P[2] + d / 2, zi = P[2] - d / 2;
    const band = () => { c.beginPath(); c.moveTo(px(-hw), py(zs)); c.lineTo(px(-hw), py(zo - 0.05)); c.quadraticCurveTo(px(-hw), py(zo), px(-hw + 0.05), py(zo)); c.lineTo(px(hw - 0.05), py(zo)); c.quadraticCurveTo(px(hw), py(zo), px(hw), py(zo - 0.05)); c.lineTo(px(hw), py(zs));
      c.lineTo(px(hw - d), py(zs)); c.lineTo(px(hw - d), py(zi - 0.02)); c.quadraticCurveTo(px(hw - d), py(zi), px(hw - d - 0.02), py(zi)); c.lineTo(px(-hw + d + 0.02), py(zi)); c.quadraticCurveTo(px(-hw + d), py(zi), px(-hw + d), py(zi - 0.02)); c.lineTo(px(-hw + d), py(zs)); c.closePath(); };
    band(); c.fillStyle = 'rgba(0,0,0,0.14)'; c.fill(); c.strokeStyle = white; c.lineWidth = 6; band(); c.stroke();
    text(-0.36, P[2] + 0.003, 'PASS  LINE', 58, white); text(0.36, P[2] + 0.003, 'PASS  LINE', 58, white);
    text(-hw + d / 2, 0.60, 'PASS LINE', 34, white, 'Georgia, serif', '900', Math.PI / 2); text(hw - d / 2, 0.60, 'PASS LINE', 34, white, 'Georgia, serif', '900', -Math.PI / 2);
    c.setLineDash([9, 7]); c.strokeStyle = 'rgba(250,246,232,0.55)'; c.lineWidth = 3; c.beginPath(); c.arc(px(C.passChips[0]), py(C.passChips[2]), 0.044 * S, 0, 7); c.stroke(); c.setLineDash([]);
    // the puck's OFF place
    c.strokeStyle = 'rgba(250,246,232,0.4)'; c.lineWidth = 3; c.beginPath(); c.arc(px(C.puckOff[0]), py(C.puckOff[2]), (C.puckD / 2 + 0.006) * S, 0, 7); c.stroke();
    // pays, printed small between the line and the field
    text(0, 0.728, 'PASS LINE PAYS 1 TO 1   ·   FIELD: 3 · 4 · 9 · 10 · 11 PAY 1 TO 1   ·   PLAY MONEY', 17, 'rgba(250,246,232,0.7)', 'Arial', '700');
  });
  feltTex.anisotropy = 8;
  const clothN = kit.canvasTexture(256, 256, (c, w, h) => { c.fillStyle = 'rgb(128,128,255)'; c.fillRect(0, 0, w, h); for (let y = 0; y < h; y += 4) for (let x = 0; x < w; x += 4) { const o = ((x + y) / 4) % 2 ? 12 : -12; c.fillStyle = `rgb(${128 + o},${128 - o},245)`; c.fillRect(x, y, 4, 2); } });
  clothN.colorSpace = THREE.NoColorSpace; clothN.wrapS = clothN.wrapT = THREE.RepeatWrapping; clothN.repeat.set(84, 50);
  const felt = new THREE.MeshStandardMaterial({ name: 'Felt', map: feltTex, normalMap: clothN, normalScale: new THREE.Vector2(0.35, 0.35), roughness: 0.96, metalness: 0 });
  {
    const geo = new THREE.PlaneGeometry(SPAN, ZSPAN); geo.rotateX(-Math.PI / 2);
    const m = new THREE.Mesh(geo, felt); m.name = 'Felt'; m.position.set(0, Y, Z0 + ZSPAN / 2); g.add(m);
  }

  const leatherTex = kit.canvasTexture(1024, 256, (c, w, h) => {
    c.fillStyle = '#141113'; c.fillRect(0, 0, w, h);
    let seed = 3; const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
    for (let i = 0; i < 40000; i++) { const x = rnd() * w, y = rnd() * h, r = 0.5 + rnd() * 1.4; c.fillStyle = rnd() > 0.5 ? 'rgba(255,240,220,0.03)' : 'rgba(0,0,0,0.18)'; c.beginPath(); c.arc(x, y, r, 0, 7); c.fill(); }
    // two rows of stitching along the rail (v runs across the cushion profile)
    c.strokeStyle = 'rgba(205,170,110,0.75)'; c.lineWidth = 2.2; c.setLineDash([9, 6]);
    for (const y of [h * 0.2, h * 0.8]) { c.beginPath(); c.moveTo(0, y); c.lineTo(w, y); c.stroke(); }
  });
  leatherTex.wrapS = leatherTex.wrapT = THREE.RepeatWrapping; leatherTex.repeat.set(14, 1);
  const leather = new THREE.MeshPhysicalMaterial({ name: 'Leather', map: leatherTex, roughness: 0.48, clearcoat: 0.3, clearcoatRoughness: 0.5, sheen: 0.4, sheenColor: new THREE.Color(0x3a302a) });
  const woodTex = kit.canvasTexture(1024, 256, (c, w, h) => {
    const lg = c.createLinearGradient(0, 0, 0, h); lg.addColorStop(0, '#2c160c'); lg.addColorStop(0.5, '#4a2614'); lg.addColorStop(1, '#22100a'); c.fillStyle = lg; c.fillRect(0, 0, w, h);
    for (let i = 0; i < 120; i++) { const y = (i * 37.7) % h; c.strokeStyle = `rgba(30,12,4,${0.08 + (i % 7) / 50})`; c.lineWidth = 1 + (i % 3); c.beginPath(); c.moveTo(0, y); for (let x = 0; x <= w; x += 24) c.lineTo(x, y + Math.sin(x / 90 + i * 1.7) * 5); c.stroke(); }
  });
  woodTex.wrapS = woodTex.wrapT = THREE.RepeatWrapping; woodTex.repeat.set(6, 1);
  const wood = new THREE.MeshPhysicalMaterial({ name: 'Wood', map: woodTex, roughness: 0.32, clearcoat: 1, clearcoatRoughness: 0.12 });

  const liner = new THREE.MeshStandardMaterial({ name: 'WallCloth', color: 0x06452b, roughness: 0.95 });
  const rubberMat = new THREE.MeshStandardMaterial({ name: 'PyramidRubber', color: 0x0a2c1e, roughness: 0.62 });
  const goldTrim = kit.mat.metal('#d4a94a', 0.22);
  const apronMat = new THREE.MeshPhysicalMaterial({ name: 'Apron', color: 0x0b0a0c, roughness: 0.3, clearcoat: 0.8, clearcoatRoughness: 0.2 });

  // ---------------- walls: wooden body, cloth inside, padded leather rail on top ----------------
  // a wall runs along x (len) or along z; `inner` is the coordinate of its inside face
  const wall = (name, cx, cz, lenX, lenZ) => {
    const w = kit.group(name, { pos: [cx, 0, cz] });
    w.add(kit.roundedBox(lenX, WH + 0.085, lenZ, 0.012, wood, { name: name + 'Wood', pos: [0, Y - 0.085 + (WH + 0.085) / 2 - 0.022, 0] }));
    w.add(kit.roundedBox(lenX + 0.012, 0.05, lenZ + 0.012, 0.024, leather, { name: name + 'Rail', pos: [0, Y + WH - 0.018, 0] }));
    g.add(w); return w;
  };
  const xo = FX + WT / 2, zLen = ZN - ZF + 2 * WT;
  wall('WallLeft', -xo, (ZN + ZF) / 2, WT, zLen); wall('WallRight', xo, (ZN + ZF) / 2, WT, zLen);
  wall('WallNear', 0, ZN + WT / 2, 2 * FX + 0.02, WT);
  const segLen = FX - C.gap + WT, segX = C.gap + segLen / 2;
  wall('WallFarL', -segX, ZF - WT / 2, segLen, WT); wall('WallFarR', segX, ZF - WT / 2, segLen, WT);
  // cloth lining on the inside of the side and near walls
  const lin = WH - 0.045;
  g.add(kit.box(0.004, lin, ZN - ZF, liner, { name: 'LinerL', pos: [-FX + 0.002, Y + lin / 2, (ZN + ZF) / 2] }));
  g.add(kit.box(0.004, lin, ZN - ZF, liner, { name: 'LinerR', pos: [FX - 0.002, Y + lin / 2, (ZN + ZF) / 2] }));
  g.add(kit.box(2 * FX, lin, 0.004, liner, { name: 'LinerNear', pos: [0, Y + lin / 2, ZN - 0.002] }));
  // gold line under the rail, all the way round the inside
  for (const [n, x, z, lx, lz] of [['TrimL', -FX + 0.003, (ZN + ZF) / 2, 0.004, ZN - ZF], ['TrimR', FX - 0.003, (ZN + ZF) / 2, 0.004, ZN - ZF], ['TrimNear', 0, ZN - 0.003, 2 * FX, 0.004]]) g.add(kit.box(lx, 0.005, lz, goldTrim, { name: n, pos: [x, Y + lin + 0.003, z] }));
  // pyramid rubber on the two far sections: rows of small pyramids, the surface the dice must strike
  {
    const p = 0.0125, rows = Math.floor((lin - 0.004) / p), parts = [];
    for (const sx of [-1, 1]) {
      const x0 = sx > 0 ? C.gap + 0.006 : -FX + 0.004, len = FX - C.gap - 0.01, cols = Math.floor(len / p), pad = (len - cols * p) / 2;
      const back = new THREE.BoxGeometry(len, lin, 0.004); back.translate(x0 + len / 2, Y + lin / 2, ZF + 0.002); parts.push(back);
      for (let r = 0; r < rows; r++) for (let k = 0; k < cols; k++) {
        const cone = new THREE.ConeGeometry(p * 0.70, 0.0075, 4, 1, true); cone.rotateY(Math.PI / 4); cone.rotateX(Math.PI / 2);
        cone.translate(x0 + pad + (k + 0.5) * p, Y + 0.004 + (r + 0.5) * p, ZF + 0.004 + 0.00375); parts.push(cone);
      }
      g.add(kit.box(len, 0.005, 0.004, goldTrim, { name: 'TrimFar', pos: [x0 + len / 2, Y + lin + 0.003, ZF + 0.003] }));
    }
    const geo = mergeGeometries(parts.map((q) => q.toNonIndexed())); geo.computeVertexNormals();
    const m = new THREE.Mesh(geo, rubberMat); m.name = 'PyramidRubber'; g.add(m);
  }
  // the dealer's opening: a low wooden edge like the card tables, cheeks closing the wall ends
  g.add(kit.roundedBox(2 * C.gap + 0.01, 0.05, 0.07, 0.008, wood, { name: 'DealerEdge', pos: [0, Y - 0.024, ZF - 0.075] }));
  g.add(kit.box(2 * C.gap, 0.006, 0.004, kit.mat.metal('#c9a03c', 0.25), { name: 'BrassStrip', pos: [0, Y + 0.0005, ZF - 0.0405] }));
  g.add(kit.box(2 * C.gap, 0.012, 0.044, new THREE.MeshStandardMaterial({ name: 'FeltLip', color: 0x075233, roughness: 0.96 }), { name: 'FeltLip', pos: [0, Y - 0.0062, ZF - 0.02] }));
  // body under the felt and the base
  g.add(kit.roundedBox(2 * (FX + WT) - 0.02, 0.15, zLen - 0.02, 0.02, apronMat, { name: 'Apron', pos: [0, Y - 0.16, (ZN + ZF) / 2] }));
  g.add(kit.box(2 * (FX + WT) + 0.004, 0.012, zLen + 0.004, goldTrim, { name: 'GoldBand', pos: [0, Y - 0.092, (ZN + ZF) / 2] }));
  const black = kit.mat.metal('#1b1b1e', 0.45);
  for (const x of [-0.55, 0.55]) {
    g.add(kit.cylinder(0.07, 0.085, 0.56, black, { name: 'Leg', pos: [x, 0.30, 0.5], segments: 32 }));
    g.add(kit.cylinder(0.26, 0.28, 0.025, black, { name: 'Foot', pos: [x, 0.0125, 0.5], segments: 48 }));
  }

  // ---------------- chip rack: black tray with ten rounded channels, real chips standing on edge in rows ----------------
  const rackW = 0.66, rackD = 0.112, tubes = 10, tubeW = (rackW - 0.024) / tubes;
  const trayMat = new THREE.MeshPhysicalMaterial({ name: 'RackTray', color: 0x0c0b0d, roughness: 0.22, clearcoat: 1, clearcoatRoughness: 0.1 });
  const rack = kit.group('ChipRack', { pos: [L.rack[0], L.feltY, L.rack[2]] });
  // tray: a block whose top is carved into half-round channels (profile extruded along z)
  {
    const sh = new THREE.Shape(); const top = 0.006, bottom = -0.03, rr = tubeW / 2 - 0.0012;
    sh.moveTo(-rackW / 2, bottom); sh.lineTo(-rackW / 2, top);
    let x = -rackW / 2 + 0.012; sh.lineTo(x, top);
    for (let i = 0; i < tubes; i++) { const cx = x + tubeW / 2; sh.lineTo(cx - rr, top); sh.absarc(cx, top, rr, Math.PI, 0, false); sh.lineTo(x + tubeW, top); x += tubeW; }
    sh.lineTo(rackW / 2, top); sh.lineTo(rackW / 2, bottom); sh.closePath();
    const geo = new THREE.ExtrudeGeometry(sh, { depth: rackD, bevelEnabled: true, bevelThickness: 0.002, bevelSize: 0.002, bevelSegments: 2, curveSegments: 10 });
    geo.translate(0, 0, -rackD / 2);
    const m = new THREE.Mesh(geo, trayMat); m.name = 'RackTray'; rack.add(m);
    rack.add(kit.box(rackW + 0.006, 0.004, 0.006, kit.mat.metal('#d4a94a', 0.22), { name: 'RackTrim', pos: [0, top + 0.001, rackD / 2 + 0.002] }));
    rack.add(kit.box(rackW + 0.006, 0.004, 0.006, kit.mat.metal('#d4a94a', 0.22), { name: 'RackTrim', pos: [0, top + 0.001, -rackD / 2 - 0.002] }));
  }
  // chips: one merged mesh per value; each chip turned a little so the edge inserts don't line up (that is what makes it read as chips)
  {
    const order = [0, 0, 1, 1, 2, 2, 3, 3, 1, 0];          // value per channel, like a real float: 5 5 25 25 100 100 500 500 25 5
    let seed = 5; const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
    const per = CHIPS.map(() => ({ rim: [], face: [] }));
    const proto = CHIPS.map((ch) => chipMesh(THREE, kit, ch, true));
    const rimGeo = (k) => proto[k].children.find((o) => o.name.endsWith('Rim')).geometry;
    const faceGeo = (k) => proto[k].children.find((o) => o.name.endsWith('Top')).geometry;
    const faceGeoB = (k) => proto[k].children.find((o) => o.name.endsWith('Bottom')).geometry;
    let x = -rackW / 2 + 0.012;
    for (let i = 0; i < tubes; i++) {
      const k = order[i]; const n = 24 + Math.floor(rnd() * 5); const cx = x + tubeW / 2; x += tubeW;
      const len = n * L.chipH; const z0 = -len / 2;
      for (let j = 0; j < n; j++) {
        // chip axis along z, centre a little above the channel bottom, a slight lean and a random spin
        const m = new THREE.Matrix4().compose(
          new THREE.Vector3(cx, 0.006 + L.chipD / 2 - (tubeW / 2 - 0.0012) + 0.0035, z0 + j * L.chipH),
          new THREE.Quaternion().setFromEuler(new THREE.Euler(Math.PI / 2, rnd() * Math.PI * 2, 0, 'XZY')),
          new THREE.Vector3(1, 1, 1));
        per[k].rim.push(rimGeo(k).clone().applyMatrix4(m));
        if (j === 0) per[k].face.push(faceGeoB(k).clone().applyMatrix4(m));
        if (j === n - 1) per[k].face.push(faceGeo(k).clone().applyMatrix4(m));
      }
    }
    per.forEach((p, k) => {
      if (!p.rim.length) return;
      const rimMat = proto[k].children.find((o) => o.name.endsWith('Rim')).material;
      const faceMat = proto[k].children.find((o) => o.name.endsWith('Top')).material;
      const r = new THREE.Mesh(mergeGeometries(p.rim), rimMat); r.name = `RackChips${CHIPS[k].v}`; rack.add(r);
      if (p.face.length) { const f = new THREE.Mesh(mergeGeometries(p.face), faceMat); f.name = `RackChipFaces${CHIPS[k].v}`; rack.add(f); }
    });
  }
  g.add(rack);

  // ---------------- the stick, the dice bowl ----------------
  {
    const cane = new THREE.MeshStandardMaterial({ name: 'Stick', color: 0xc9a56a, roughness: 0.55 });
    const pts = []; for (let i = 0; i <= 20; i++) { const u = i / 20; pts.push(new THREE.Vector3(-0.42 + 0.78 * u, Y + 0.007 + 0.004 * Math.sin(u * Math.PI), 0.142 + 0.006 * Math.sin(u * 3))); }
    for (let i = 1; i <= 8; i++) { const a = i / 8 * 2.4; pts.push(new THREE.Vector3(0.36 + 0.028 * Math.sin(a), Y + 0.007, 0.142 + 0.028 - 0.028 * Math.cos(a) + 0.0)); }
    const m = new THREE.Mesh(new THREE.TubeGeometry(new THREE.CatmullRomCurve3(pts), 64, 0.0055, 10, false), cane); m.name = 'Stick'; g.add(m);
    const bowlMat = new THREE.MeshPhysicalMaterial({ name: 'Bowl', color: 0x3a1c0e, roughness: 0.3, clearcoat: 1, clearcoatRoughness: 0.15, side: THREE.DoubleSide });
    const bowl = kit.group('DiceBowl', { pos: [0.455, Y, 0.078] });
    const prof = [[0.001, 0.004], [0.034, 0.004], [0.05, 0.018], [0.056, 0.034], [0.052, 0.034], [0.046, 0.02], [0.032, 0.009], [0.001, 0.009]];
    const bm = new THREE.Mesh(new THREE.LatheGeometry(prof.map(([r, y]) => new THREE.Vector2(r, y)), 48), bowlMat); bm.name = 'Bowl'; bowl.add(bm);
    [[-0.014, 0.0, 20, 1], [0.012, 0.01, 65, 3], [0.004, -0.016, 130, 5]].forEach(([x, z, yaw, k], i) => { const d = die(THREE, kit); d.name = 'SpareDie' + i; d.position.set(x, 0.009 + C.die / 2, z); d.rotation.set(k === 3 ? Math.PI / 2 : 0, THREE.MathUtils.degToRad(yaw), k === 5 ? Math.PI / 2 : 0); bowl.add(d); });
    g.add(bowl);
  }

  // ---------------- limit sign on the rail ----------------
  const signTex = kit.canvasTexture(512, 256, (c, w, h) => {
    const lg = c.createLinearGradient(0, 0, 0, h); lg.addColorStop(0, '#16161c'); lg.addColorStop(1, '#050507'); c.fillStyle = lg; c.fillRect(0, 0, w, h);
    c.strokeStyle = '#c9a03c'; c.lineWidth = 6; c.strokeRect(8, 8, w - 16, h - 16);
    c.fillStyle = '#ffd36b'; c.font = '800 66px Arial'; c.textAlign = 'center'; c.fillText('CRAPS', w / 2, 84);
    c.fillStyle = '#ffffff'; c.font = '700 40px Arial'; c.fillText('MIN 5  ·  MAX 500', w / 2, 148);
    c.fillStyle = '#7fe0a8'; c.font = '600 30px Arial'; c.fillText('PLAY MONEY', w / 2, 206);
  });
  const sign = kit.group('LimitSign', { pos: [-0.66, Y + WH + 0.007, ZF - WT / 2], rot: [0, 12, 0] });
  sign.add(kit.box(0.16, 0.08, 0.008, new THREE.MeshStandardMaterial({ name: 'SignFace', map: signTex, emissive: 0xffffff, emissiveMap: signTex, emissiveIntensity: 0.6, roughness: 0.3 }), { name: 'SignFace', pos: [0, 0.07, 0] }));
  sign.add(kit.box(0.17, 0.09, 0.006, black, { name: 'SignFrame', pos: [0, 0.07, -0.006] }));
  sign.add(kit.cylinder(0.006, 0.006, 0.03, black, { name: 'SignPost', pos: [0, 0.015, -0.004], segments: 12 }));
  g.add(sign);

  // ---------------- soft floor shadow ----------------
  const shadowTex = kit.canvasTexture(256, 256, (c, w, h) => { const rg = c.createRadialGradient(w / 2, h / 2, 10, w / 2, h / 2, w / 2); rg.addColorStop(0, 'rgba(0,0,0,0.55)'); rg.addColorStop(0.6, 'rgba(0,0,0,0.3)'); rg.addColorStop(1, 'rgba(0,0,0,0)'); c.fillStyle = rg; c.fillRect(0, 0, w, h); });
  const shadow = new THREE.Mesh(new THREE.PlaneGeometry(2.7, 2.0), new THREE.MeshBasicMaterial({ name: 'FloorShadow', map: shadowTex, transparent: true, depthWrite: false }));
  shadow.rotation.x = -Math.PI / 2; shadow.position.set(0, 0.002, 0.5); shadow.name = 'FloorShadow'; g.add(shadow);

  g.traverse((o) => { if (o.isMesh && o.name !== 'FloorShadow') { o.castShadow = true; o.receiveShadow = true; } });
  return g;
}
