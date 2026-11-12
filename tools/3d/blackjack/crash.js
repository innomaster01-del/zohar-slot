// ZOHAR SKY RUSH: a launch pad that stands on your table, and a jet that takes off from it and flies through the room.
// Built in code, no third-party art. Origin of the pad: the middle of its underside, on the table; +y up, +z toward the
// player; the runway runs along x, take-off toward +x. Tabletop size: the pad is 0.96 m long.
// The jet (origin at its middle, nose toward +x) and the effects are separate small models the app moves.
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js';

// the same numbers as CrashScene.kt
export const C = { padW: 0.96, padD: 0.30, padH: 0.018, rwyL: 0.86, rwyW: 0.11, startX: -0.34, planeY: 0.046, len: 0.22 };
export const CYAN = '#4cc9ff', VIOLET = '#b48cff', GOLD = '#ffc94a', RED = '#ff3b4e', GREEN = '#3dff8a';
const glow = (THREE, name, color, k = 2.2) => new THREE.MeshStandardMaterial({ name, color: new THREE.Color(color).multiplyScalar(0.12), emissive: new THREE.Color(color), emissiveIntensity: k, roughness: 0.35 });

/** The launch pad: a glossy dark slab, the runway on it with its markings and lights, approach lights, a small tower. */
export function pad(THREE, kit) {
  const g = kit.group('CrashPad'); const top = C.padH;
  const slab = new THREE.MeshPhysicalMaterial({ name: 'PadBody', color: 0x0b0f1c, roughness: 0.28, metalness: 0.5, clearcoat: 1, clearcoatRoughness: 0.08 });
  g.add(kit.roundedBox(C.padW, C.padH, C.padD, 0.008, slab, { name: 'Slab', pos: [0, C.padH / 2, 0] }));
  // a thin glowing line all round the slab's top edge
  const edge = [[-1, -1], [1, -1], [1, 1], [-1, 1], [-1, -1]].map(([sx, sz]) => [sx * (C.padW / 2 - 0.004), top + 0.0005, sz * (C.padD / 2 - 0.004)]);
  for (let i = 0; i < 4; i++) { const [a, b] = [edge[i], edge[i + 1]]; const len = Math.hypot(b[0] - a[0], b[2] - a[2]); const m = kit.box(i % 2 ? 0.0025 : len, 0.0012, i % 2 ? len : 0.0025, glow(THREE, 'PadEdge', CYAN, 1.6), { name: 'PadEdge', pos: [(a[0] + b[0]) / 2, top + 0.0006, (a[2] + b[2]) / 2] }); g.add(m); }
  // the runway: asphalt, threshold piano keys, the designators 09 / 27, aiming-point blocks, the centre line, and the name
  const rw = kit.canvasTexture(2048, 262, (c, w, h) => {
    const gr = c.createLinearGradient(0, 0, 0, h); gr.addColorStop(0, '#1a1e28'); gr.addColorStop(0.5, '#232835'); gr.addColorStop(1, '#1a1e28'); c.fillStyle = gr; c.fillRect(0, 0, w, h);
    for (let i = 0; i < 9000; i++) { c.fillStyle = `rgba(255,255,255,${Math.random() * 0.05})`; c.fillRect(Math.random() * w, Math.random() * h, 2, 2); }
    c.fillStyle = 'rgba(255,255,255,0.88)'; const px = (x) => (x / C.rwyL + 0.5) * w;
    c.fillRect(0, 8, w, 5); c.fillRect(0, h - 13, w, 5);                                     // side stripes
    for (const end of [0, 1]) { const x0 = end ? w - 70 : 22;
      for (let k = 0; k < 6; k++) c.fillRect(x0, 26 + k * 37, 48, 22); }                    // threshold keys
    c.save(); c.font = '900 92px Arial Black, Arial'; c.textAlign = 'center'; c.textBaseline = 'middle';
    c.translate(118, h / 2); c.rotate(Math.PI / 2); c.fillText('09', 0, 0); c.restore();
    c.save(); c.font = '900 92px Arial Black, Arial'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.translate(w - 118, h / 2); c.rotate(-Math.PI / 2); c.fillText('27', 0, 0); c.restore();
    for (const x of [300, w - 400]) { c.fillRect(x, 40, 100, 30); c.fillRect(x, h - 70, 100, 30); }   // aiming points
    for (let x = 200; x < w - 200; x += 110) c.fillRect(x, h / 2 - 4, 60, 8);                         // centre line
    c.font = '700 44px Arial'; c.fillStyle = 'rgba(255,201,74,0.85)'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText('SKY  RUSH', w * 0.55, 62);
  });
  const rmesh = new THREE.Mesh(new THREE.PlaneGeometry(C.rwyL, C.rwyW), new THREE.MeshPhysicalMaterial({ name: 'Runway', map: rw, roughness: 0.6, clearcoat: 0.4 }));
  rmesh.rotation.x = -Math.PI / 2; rmesh.position.y = top + 0.0008; rmesh.name = 'Runway'; g.add(rmesh);
  // edge lights: white along the sides, green at the take-off end... like a real one: green threshold, red end
  const bulbs = { w: [], g: [], r: [] };
  for (let x = -C.rwyL / 2 + 0.02; x <= C.rwyL / 2 - 0.02 + 1e-6; x += 0.06) for (const z of [-1, 1]) bulbs.w.push([x, z * (C.rwyW / 2 + 0.008)]);
  for (let k = -4; k <= 4; k++) { bulbs.g.push([-C.rwyL / 2 - 0.006, k * 0.012]); bulbs.r.push([C.rwyL / 2 + 0.006, k * 0.012]); }
  for (const [key, col] of [['w', '#e8f6ff'], ['g', GREEN], ['r', RED]]) {
    const geos = bulbs[key].map(([x, z]) => { const s = new THREE.SphereGeometry(0.0042, 12, 8, 0, Math.PI * 2, 0, Math.PI / 2); s.translate(x, top, z); return s; });
    const m = new THREE.Mesh(mergeGeometries(geos), glow(THREE, 'RunwayLight_' + key, col, 3.0)); m.name = 'RunwayLights'; g.add(m);
  }
  // approach lights: a row of white bars out from the start, on the slab
  for (let k = 1; k <= 1; k++) g.add(kit.box(0.003, 0.002, 0.07, glow(THREE, 'Approach', '#ffffff', 2.4), { name: 'Approach', pos: [-C.rwyL / 2 - 0.02, top + 0.001, 0] }));
  // the tower: a slim stem, a glass cab with a lit floor, a red beacon on top
  const tw = kit.group('Tower', { pos: [0.18, top, -C.padD / 2 + 0.05] });
  tw.add(kit.cylinder(0.009, 0.013, 0.11, new THREE.MeshPhysicalMaterial({ name: 'TowerStem', color: 0xdfe6f2, roughness: 0.3, clearcoat: 1 }), { name: 'Stem', pos: [0, 0.055, 0], segments: 24 }));
  tw.add(kit.cylinder(0.024, 0.017, 0.028, new THREE.MeshPhysicalMaterial({ name: 'TowerGlass', color: 0x1b3550, roughness: 0.05, metalness: 0.6, emissive: new THREE.Color(CYAN), emissiveIntensity: 0.35 }), { name: 'Cab', pos: [0, 0.124, 0], segments: 8 }));
  tw.add(kit.cylinder(0.026, 0.026, 0.004, new THREE.MeshPhysicalMaterial({ name: 'TowerRoof', color: 0xdfe6f2, roughness: 0.3 }), { name: 'Roof', pos: [0, 0.14, 0], segments: 8 }));
  tw.add(kit.sphere(0.005, glow(THREE, 'Beacon', RED, 3.5), { name: 'Beacon', pos: [0, 0.147, 0], segments: 12 }));
  g.add(tw);
  // a soft cyan glow on the table round the pad
  const halo = kit.canvasTexture(512, 256, (c, w, h) => { c.translate(w / 2, h / 2); c.scale(1, h / w); c.translate(-w / 2, -w / 2); const gr = c.createRadialGradient(w / 2, w / 2, 10, w / 2, w / 2, w / 2 - 2); gr.addColorStop(0, 'rgba(76,201,255,0.5)'); gr.addColorStop(0.6, 'rgba(76,201,255,0.12)'); gr.addColorStop(1, 'rgba(76,201,255,0)'); c.fillStyle = gr; c.fillRect(0, 0, w, w); });
  const hm = new THREE.Mesh(new THREE.PlaneGeometry(C.padW + 0.5, C.padD + 0.4), new THREE.MeshBasicMaterial({ name: 'FloorShadow', map: halo, transparent: true, depthWrite: false })); hm.rotation.x = -Math.PI / 2; hm.position.y = 0.001; hm.name = 'FloorShadow'; g.add(hm);
  return g;
}

/** The jet: white and gold, swept wings, twin canted fins, a glass canopy, navigation lights (red port, green starboard). */
export function plane(THREE, kit) {
  const g = kit.group('Jet');
  const white = new THREE.MeshPhysicalMaterial({ name: 'JetWhite', color: 0xf3f6ff, roughness: 0.22, metalness: 0.15, clearcoat: 1, clearcoatRoughness: 0.05 });
  const gold = new THREE.MeshPhysicalMaterial({ name: 'JetGold', color: 0xffc94a, roughness: 0.28, metalness: 0.45, clearcoat: 0.6, emissive: new THREE.Color('#b07800'), emissiveIntensity: 0.55 });
  const dark = new THREE.MeshStandardMaterial({ name: 'JetDark', color: 0x2a2e3a, roughness: 0.35, metalness: 0.7 });
  // fuselage: a smooth body of revolution, nose to tail along x, a little flattened
  const prof = [[0, 0.112], [0.006, 0.104], [0.012, 0.09], [0.018, 0.07], [0.022, 0.045], [0.024, 0.015], [0.024, -0.03], [0.022, -0.07], [0.019, -0.095], [0.016, -0.11], [0.0001, -0.11]];
  const fg = new THREE.LatheGeometry(prof.map(([r, y]) => new THREE.Vector2(r, y)), 48); fg.rotateZ(-Math.PI / 2); fg.scale(1, 0.88, 1);
  const body = new THREE.Mesh(fg, white); body.name = 'Fuselage'; g.add(body);
  // a gold stripe along the spine, from the canopy to the fins
  { const st = new THREE.BoxGeometry(0.12, 0.0016, 0.006); st.translate(-0.035, 0.0215, 0); const m = new THREE.Mesh(st, gold); m.name = 'Spine'; g.add(m); }
  // canopy: a long low bubble of dark glass
  const cg = new THREE.SphereGeometry(0.02, 32, 20); cg.scale(1.7, 0.6, 0.62);
  const canopy = new THREE.Mesh(cg, new THREE.MeshPhysicalMaterial({ name: 'Canopy', color: 0x0e2238, roughness: 0.03, metalness: 0.6, clearcoat: 1, emissive: new THREE.Color('#1d6fa8'), emissiveIntensity: 0.5 })); canopy.position.set(0.052, 0.015, 0); canopy.name = 'Canopy'; g.add(canopy);
  for (const s of [1, -1]) {
    // wing: swept, thin, built for its own side (no mirroring, so it is lit right side out)
    const ws = new THREE.Shape(); ws.moveTo(0.04, 0); ws.lineTo(-0.045, 0.125 * s); ws.lineTo(-0.075, 0.125 * s); ws.lineTo(-0.055, 0); ws.closePath();
    const geo = new THREE.ExtrudeGeometry(ws, { depth: 0.004, bevelEnabled: true, bevelThickness: 0.0012, bevelSize: 0.0012, bevelSegments: 2 }); geo.rotateX(Math.PI / 2); geo.translate(0, -0.006, 0);
    const w = new THREE.Mesh(geo, white); w.name = 'Wing'; g.add(w);
    const le = new THREE.Vector3(0.04, 0, 0), tip = new THREE.Vector3(-0.045, 0, 0.125 * s); const len = le.distanceTo(tip);
    const strip = kit.box(len, 0.0042, 0.0032, gold, { name: 'LeadingEdge' }); strip.position.set((le.x + tip.x) / 2, -0.008, (le.z + tip.z) / 2); strip.rotation.y = -Math.atan2(tip.z - le.z, tip.x - le.x); g.add(strip);
    g.add(kit.box(0.03, 0.014, 0.003, gold, { name: 'Winglet', pos: [-0.062, -0.002, 0.125 * s] }));
    g.add(kit.sphere(0.0038, glow(THREE, s > 0 ? 'NavGreen' : 'NavRed', s > 0 ? GREEN : RED, 4), { name: 'NavLight', pos: [-0.046, -0.008, 0.127 * s], segments: 12 }));
    // tailplane
    const tp = new THREE.Shape(); tp.moveTo(0, 0); tp.lineTo(-0.025, 0.045 * s); tp.lineTo(-0.038, 0.045 * s); tp.lineTo(-0.036, 0); tp.closePath();
    const tg = new THREE.ExtrudeGeometry(tp, { depth: 0.0025, bevelEnabled: false }); tg.rotateX(Math.PI / 2); tg.translate(-0.07, -0.002, 0);
    const t = new THREE.Mesh(tg, white); t.name = 'Tailplane'; g.add(t);
    // fin, canted out, with its gold top, as one piece
    const fin = kit.group('Fin', { pos: [-0.058, 0.01, 0.011 * s] }); fin.rotation.x = -s * 0.3;
    const fs = new THREE.Shape(); fs.moveTo(0, 0); fs.lineTo(-0.03, 0.046); fs.lineTo(-0.046, 0.046); fs.lineTo(-0.044, 0); fs.closePath();
    const fgeo = new THREE.ExtrudeGeometry(fs, { depth: 0.003, bevelEnabled: true, bevelThickness: 0.0008, bevelSize: 0.0008, bevelSegments: 1 }); fgeo.translate(0, 0, -0.0015);
    const fm = new THREE.Mesh(fgeo, white); fm.name = 'FinBody'; fin.add(fm);
    fin.add(kit.box(0.018, 0.0045, 0.0042, gold, { name: 'FinTop', pos: [-0.038, 0.046, 0] }));
    g.add(fin);
  }
  // the nozzle with its hot core
  g.add(kit.cylinder(0.0165, 0.0145, 0.016, dark, { name: 'Nozzle', pos: [-0.112, 0, 0], rot: [0, 0, 90], segments: 32 }));
  g.add(kit.cylinder(0.011, 0.011, 0.002, glow(THREE, 'NozzleCore', '#bfe9ff', 4), { name: 'NozzleCore', pos: [-0.1205, 0, 0], rot: [0, 0, 90], segments: 32 }));
  return g;
}
/** The afterburner: a glowing cone pointing back from the nozzle (origin at the nozzle, flame toward −x). The app makes it flicker. */
export function flame(THREE, kit) {
  const g = kit.group('Flame');
  const outer = new THREE.ConeGeometry(0.013, 0.07, 24, 1, true); outer.rotateZ(Math.PI / 2); outer.translate(-0.035, 0, 0);
  const inner = new THREE.ConeGeometry(0.0075, 0.045, 24); inner.rotateZ(Math.PI / 2); inner.translate(-0.0225, 0, 0);
  const a = new THREE.Mesh(outer, new THREE.MeshStandardMaterial({ name: 'FlameOuter', color: 0x05101a, emissive: new THREE.Color('#2f8cff'), emissiveIntensity: 3, transparent: true, opacity: 0.55, side: THREE.DoubleSide, depthWrite: false })); a.name = 'FlameOuter';
  const b = new THREE.Mesh(inner, glow(THREE, 'FlameCore', '#d8f4ff', 5)); b.name = 'FlameCore';
  g.add(a); g.add(b); return g;
}
/** One puff of the trail. */
export function dot(THREE, kit, color, name) { const g = kit.group(name); g.add(kit.sphere(0.0055, glow(THREE, name, color, 3.2), { name: 'Dot', segments: 12 })); return g; }
/** The fireball: a hot core in an orange shell (the app grows it and hides it). */
export function fireball(THREE, kit) {
  const g = kit.group('Fireball');
  g.add(kit.sphere(0.03, glow(THREE, 'FireCore', '#ffd060', 3.2), { name: 'Core', segments: 24 }));
  const shell = new THREE.Mesh(new THREE.IcosahedronGeometry(0.05, 2), new THREE.MeshStandardMaterial({ name: 'FireShell', color: 0x1a0600, emissive: new THREE.Color('#ff4a00'), emissiveIntensity: 2.2, transparent: true, opacity: 0.75, depthWrite: false, flatShading: true })); shell.name = 'Shell'; g.add(shell);
  return g;
}
/** The shock ring that flies out flat from the blast. */
export function shock(THREE, kit) { const g = kit.group('Shock'); g.add(kit.torus(0.06, 0.004, glow(THREE, 'Shock', '#ffb04a', 3.5), { name: 'Ring', rot: [90, 0, 0], segments: 48 })); return g; }
/** A burning piece of the jet: a white shard with a glowing edge. */
export function shard(THREE, kit) {
  const g = kit.group('Shard'); const s = new THREE.TetrahedronGeometry(0.012); s.scale(1.6, 0.4, 1);
  const m = new THREE.Mesh(s, new THREE.MeshStandardMaterial({ name: 'Shard', color: 0xdfe6f2, roughness: 0.4, emissive: new THREE.Color('#ff5a1a'), emissiveIntensity: 1.2, flatShading: true })); m.name = 'Shard'; g.add(m); return g;
}
/** Smoke: a dark soft ball the app lets rise and grow. */
export function smoke(THREE, kit) { const g = kit.group('Smoke'); const m = new THREE.Mesh(new THREE.IcosahedronGeometry(0.04, 2), new THREE.MeshStandardMaterial({ name: 'Smoke', color: 0x9a9aa2, roughness: 1, transparent: true, opacity: 0.32, depthWrite: false })); m.name = 'Smoke'; g.add(m); return g; }
/** The runway chase light: two bright points, one each side, that run toward the take-off end. */
export function chase(THREE, kit) { const g = kit.group('Chase'); for (const z of [-1, 1]) g.add(kit.sphere(0.0062, glow(THREE, 'Chase', '#ffffff', 5), { name: 'ChaseLight', pos: [0, C.padH + 0.002, z * (C.rwyW / 2 + 0.008)], segments: 12 })); return g; }
