// The 3D trail pieces for Follow Poppy (all lie flat on the ground, forward = +z, 1 unit = 1 m):
//  trail_ribbon.glb   one ribbon segment, 1 x 1 (the app scales x to the width, z to the length): see-through cyan with white edges
//  trail_chevron.glb  a raised white chevron arrow with a soft cyan glow under it (~0.42 m wide)
//  poppy_paw_girl.glb / poppy_paw_boy.glb   one small paw print (~0.11 m), pink or blue
//  poppy_shadow.glb   a soft round shadow, 1 x 1 (scaled in the app)
// usage: node make_trail.js <three.min.js> <out dir>
const fs = require('fs'), path = require('path');
const { writeGLB, loadThree } = require('./gltf_writer');
const [threePath, outDir] = process.argv.slice(2);
const THREE = loadThree(threePath).THREE;
const C = hex => new THREE.Color(hex).convertSRGBToLinear();
const flat = g => { g.rotateX(-Math.PI / 2); return g; };                  // shape plane (x, y) → ground (x, -y): shape "down" (−y) = forward (+z)
const unlit = (hex, opacity) => { const m = new THREE.MeshBasicMaterial({ color: C(hex) }); if (opacity < 1) { m.transparent = true; m.opacity = opacity; } return m; };
function save(name, root) { const { glb } = writeGLB(THREE, root); fs.writeFileSync(path.join(outDir, name), glb); console.log(name, glb.length, 'bytes'); }

// ---- ribbon segment ----
{
  const g = new THREE.Group(); g.name = 'Ribbon';
  const body = new THREE.Mesh(flat(new THREE.PlaneGeometry(0.94, 1)), unlit(0x22d3ee, 0.30)); g.add(body);
  for (const s of [-1, 1]) { const e = new THREE.Mesh(flat(new THREE.PlaneGeometry(0.03, 1)), unlit(0xffffff, 0.75)); e.position.set(s * 0.485, 0.002, 0); g.add(e); }
  save('trail_ribbon.glb', g);
}
// ---- ribbon fade-in: the near end (towards you) dissolves into scattered specks instead of a hard square edge ----
{
  const W = 64, H = 256, rgba = { width: W, height: H, data: new Uint8Array(W * H * 4) };
  let seed = 12345; const rnd = () => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff);
  const cell = 4, cells = [];                                                          // specks in 4x4 px cells
  for (let cy = 0; cy < H / cell; cy++) { cells.push([]); for (let cx = 0; cx < W / cell; cx++) cells[cy].push(rnd()); }
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    const t = y / (H - 1);                                                             // 0 = near you (top row in glTF = local −z), 1 = far end
    const ramp = Math.pow(Math.min(1, Math.max(0, (t - 0.02) / 0.93)), 1.35);
    const keep = cells[(y / cell) | 0][(x / cell) | 0] < Math.pow(ramp, 0.75);        // more specks further away
    const u = (x + 0.5) / W, edge = u < 0.035 || u > 0.965;
    const soft = keep ? 1 : ramp * 0.35;                                               // a faint wash under the specks
    const a = (edge ? 0.75 : 0.30) * soft * Math.min(1, ramp * 1.6);
    const c = edge ? [255, 255, 255] : [34, 211, 238];
    rgba.data.set([c[0], c[1], c[2], Math.round(a * 255)], (y * W + x) * 4);
  }
  const m = new THREE.MeshBasicMaterial({ color: 0xffffff, transparent: true, opacity: 1 }); m.userData.rgba = rgba;
  const g = new THREE.Group(); g.name = 'RibbonFade';
  const body = new THREE.Mesh(flat(new THREE.PlaneGeometry(1.0, 1)), m); g.add(body);   // 1 x 1 m: x scaled to the width, z to the length
  save('trail_ribbon_fade.glb', g);
}
// ---- chevron ----
{
  const sh = new THREE.Shape(), w = 0.21, len = 0.15, th = 0.07;          // tip at −y (forward after flat())
  sh.moveTo(0, -len); sh.lineTo(w, len - 0.02); sh.lineTo(w - th, len + 0.03); sh.lineTo(0, -len + th * 1.25); sh.lineTo(-w + th, len + 0.03); sh.lineTo(-w, len - 0.02); sh.closePath();
  const g = new THREE.Group(); g.name = 'Chevron';
  const top = new THREE.ExtrudeGeometry(sh, { depth: 0.025, bevelEnabled: true, bevelThickness: 0.006, bevelSize: 0.006, bevelSegments: 2 });
  const arrow = new THREE.Mesh(flat(top), unlit(0xffffff, 1)); arrow.position.y = 0.006; g.add(arrow);
  const glowShape = new THREE.Shape(); const gw = w + 0.05, gl = len + 0.05;
  glowShape.moveTo(0, -gl); glowShape.lineTo(gw, gl); glowShape.lineTo(0, gl - 0.06); glowShape.lineTo(-gw, gl); glowShape.closePath();
  const glow = new THREE.Mesh(flat(new THREE.ShapeGeometry(glowShape)), unlit(0x67e8f9, 0.45)); glow.position.y = 0.003; g.add(glow);
  save('trail_chevron.glb', g);
}
// ---- paw prints ----
for (const [gender, hex] of [['girl', 0xff7fbf], ['boy', 0x5fb3ff]]) {
  const g = new THREE.Group(); g.name = 'Paw'; const m = unlit(hex, 0.88);
  const pad = new THREE.Mesh(flat(new THREE.CircleGeometry(0.032, 24)), m); pad.scale.set(1.15, 1, 0.95); pad.position.set(0, 0.004, -0.012); g.add(pad);
  [[-0.034, 0.03], [0, 0.046], [0.034, 0.03]].forEach(([x, z]) => { const t = new THREE.Mesh(flat(new THREE.CircleGeometry(0.0155, 16)), m); t.position.set(x, 0.004, z); g.add(t); });
  save(`poppy_paw_${gender}.glb`, g);
}
// ---- soft shadow ----
{
  const n = 64, rgba = { width: n, height: n, data: new Uint8Array(n * n * 4) };
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) { const r = Math.hypot(x + 0.5 - n / 2, y + 0.5 - n / 2) / (n / 2); const a = r >= 1 ? 0 : Math.pow(1 - r * r, 1.6) * 0.55; rgba.data.set([0, 0, 0, Math.round(a * 255)], (y * n + x) * 4); }
  const m = new THREE.MeshBasicMaterial({ color: 0xffffff, transparent: true, opacity: 1 }); m.userData.rgba = rgba;
  const s = new THREE.Mesh(flat(new THREE.PlaneGeometry(1, 1)), m); s.name = 'Shadow'; s.position.y = 0.003;
  const g = new THREE.Group(); g.add(s); save('poppy_shadow.glb', g);
}
