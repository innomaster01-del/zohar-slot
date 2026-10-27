// ZOHAR modern slot cabinets for the AR app: PHARAOH'S BOOK (J-curve portrait) and DRAGON FORTUNE (C-curve + jackpot topper).
// Front = +Z, metres, floor centre at the origin. Named parts the app drives (same contract as real-cabinet.js):
//   Screen (material ScreenImage, UV v = 0 at the bottom of the curve), ScreenGlass, TopperScreen (material TopperImage),
//   SpinButton (material Cap_Spin), BetDownButton, BetUpButton, MaxBetButton, AutoButton (+ Label_* children),
//   light rails split in chase segments with materials LED_0 / LED_1 / LED_2 (merged → Merged_LED_0..2).
import { pharaohPortrait, dragonMain, dragonTopper } from './machine_art.js';

const V2 = (THREE, a, b) => new THREE.Vector2(a, b);

export function common(THREE, kit) {
  const tex = (w, h, draw) => { const t = kit.canvasTexture(w, h, draw); t.colorSpace = THREE.SRGBColorSpace; t.anisotropy = 8; return t; };
  const emis = (t, k = 1, name) => new THREE.MeshStandardMaterial({ name, map: t, emissive: 0xffffff, emissiveMap: t, emissiveIntensity: k, roughness: 0.3 });
  const glowM = (col, k = 2.5, name) => new THREE.MeshStandardMaterial({ name, color: 0x000000, emissive: col, emissiveIntensity: k, roughness: 0.3 });
  const spline = (pts) => new THREE.SplineCurve(pts.map(([z, y]) => V2(THREE, z, y)));
  // a sculpted body from a side profile (z,y), extruded across x
  function body(profile, width, mat, bev = 0.035) {
    const geo = new THREE.ExtrudeGeometry(profile, { depth: width - 2 * bev, bevelEnabled: true, bevelThickness: bev, bevelSize: bev * 0.8, bevelSegments: 6, curveSegments: 3 });
    geo.rotateY(-Math.PI / 2); geo.translate((width - 2 * bev) / 2, 0, 0); const m = new THREE.Mesh(geo, mat); return m;
  }
  // a curved screen along a (z,y) path, offset along the normal toward the player; uv v = t (0 at the start = bottom)
  function ribbon(path, width, mat, off = 0, n = 90, name) {
    const pos = [], uv = [], idx = [];
    for (let i = 0; i <= n; i++) {
      const t = i / n, p = path.getPointAt(t), d = path.getTangentAt(t), nz = d.y, ny = -d.x;
      for (const sx of [-1, 1]) { pos.push(sx * width / 2, p.y + ny * off, p.x + nz * off); uv.push(sx < 0 ? 0 : 1, t); }
    }
    for (let i = 0; i < n; i++) { const a = i * 2; idx.push(a, a + 1, a + 2, a + 1, a + 3, a + 2); }
    const geo = new THREE.BufferGeometry(); geo.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3)); geo.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2)); geo.setIndex(idx); geo.computeVertexNormals();
    const m = new THREE.Mesh(geo, mat); if (name) m.name = name; return m;
  }
  // a tube along a (z,y) path at a fixed x, over t ∈ [t0, t1]
  function rail(path, x, r, mat, off = 0, t0 = 0, t1 = 1) {
    const pts = []; const n = Math.max(6, Math.round(60 * (t1 - t0)));
    for (let i = 0; i <= n; i++) { const t = t0 + (t1 - t0) * i / n, p = path.getPointAt(t), d = path.getTangentAt(t); pts.push(new THREE.Vector3(x, p.y - d.x * off, p.x + d.y * off)); }
    return new THREE.Mesh(new THREE.TubeGeometry(new THREE.CatmullRomCurve3(pts), n * 2, r, 10), mat);
  }
  // LED chase materials: the app animates their emissive in three phases
  const LED = [0, 1, 2].map((i) => glowM(0xffffff, 3, `LED_${i}`));
  function ledRail(root, path, x, r, off, segs, colors) {
    LED.forEach((m, i) => m.emissive.set(colors[i]));
    for (let k = 0; k < segs; k++) { const m = rail(path, x, r, LED[k % 3], off, k / segs + 0.002, (k + 1) / segs - 0.002); root.add(m); }
  }
  function label(text, w, h, px, color = '#ffffff') {
    const t = kit.canvasTexture(Math.round(w * 4000), Math.round(h * 4000), (g, W, H) => { g.clearRect(0, 0, W, H); g.fillStyle = color; g.font = `900 ${px}px Arial, sans-serif`; g.textAlign = 'center'; g.textBaseline = 'middle'; g.lineWidth = Math.max(4, px * 0.14); g.strokeStyle = 'rgba(0,0,0,0.85)'; g.lineJoin = 'round'; g.strokeText(text, W / 2, H / 2 + 2); g.fillText(text, W / 2, H / 2 + 2); });
    t.colorSpace = THREE.SRGBColorSpace;
    const m = new THREE.Mesh(new THREE.PlaneGeometry(w, h), new THREE.MeshBasicMaterial({ name: `Label_${text}`, map: t, transparent: true, depthWrite: false, toneMapped: false }));
    m.rotation.x = -Math.PI / 2; m.name = `Label_${text}`; return m;
  }
  /** Physical button deck (group local: +y up out of the deck, +z toward the player). */
  function buttonDeck(o) {
    const deck = kit.group('Deck', { pos: o.pos, rot: [o.angle, 0, 0] });
    deck.add(kit.roundedBox(o.w, 0.014, o.d, 0.006, o.plate, { name: 'DeckPlate', pos: [0, -0.004, 0] }));
    deck.add(kit.cylinder(0.006, 0.006, o.w, o.trim, { name: 'DeckLip', pos: [0, 0.0, o.d / 2], rot: [0, 0, 90], segments: 12 }));
    const xs = [-0.25, -0.16, -0.07, 0.02];
    const buttons = [['BetDownButton', 'BET −'], ['BetUpButton', 'BET +'], ['MaxBetButton', 'MAX BET'], ['AutoButton', 'AUTO']];
    buttons.forEach(([name, text], i) => {
      const color = o.caps[i];
      const cap = new THREE.MeshPhysicalMaterial({ name: `Cap_${name}`, color, emissive: color, emissiveIntensity: 0.55, roughness: 0.12, clearcoat: 1, clearcoatRoughness: 0.02 });
      const btn = kit.roundedBox(0.074, 0.016, 0.05, 0.007, cap, { name, pos: [xs[i], 0.011, 0.01] });
      const lb = label(text, 0.068, 0.042, 54); lb.position.y = 0.0085; btn.add(lb); deck.add(btn);
      deck.add(kit.roundedBox(0.084, 0.008, 0.06, 0.004, o.trim, { name: `${name}Bezel`, pos: [xs[i], 0.004, 0.01] }));
    });
    const spinCap = new THREE.MeshPhysicalMaterial({ name: 'Cap_Spin', color: o.spin, emissive: o.spin, emissiveIntensity: 0.8, roughness: 0.1, clearcoat: 1, clearcoatRoughness: 0.02 });
    const spin = kit.cylinder(0.05, 0.052, 0.026, spinCap, { name: 'SpinButton', pos: [0.2, 0.015, 0.0], segments: 56 });
    const sl = label('SPIN', 0.088, 0.046, 90); sl.position.y = 0.0135; spin.add(sl); deck.add(spin);
    deck.add(kit.torus(0.057, 0.007, o.trim, { name: 'SpinRing', pos: [0.2, 0.006, 0.0], rot: [90, 0, 0], segments: 56 }));
    return deck;
  }
  function floorGlow(color, w, d, z) {
    const t = kit.canvasTexture(256, 256, (g, W, H) => { const r = g.createRadialGradient(W / 2, H / 2, 0, W / 2, H / 2, W / 2); r.addColorStop(0, color + '8c'); r.addColorStop(0.5, color + '33'); r.addColorStop(1, color + '00'); g.fillStyle = r; g.fillRect(0, 0, W, H); });
    const m = new THREE.Mesh(new THREE.PlaneGeometry(w, d), new THREE.MeshBasicMaterial({ name: 'FloorGlow', map: t, transparent: true, depthWrite: false, toneMapped: false }));
    m.rotation.x = -Math.PI / 2; m.position.set(0, 0.003, z); m.name = 'FloorGlow'; return m;
  }
  function screenParts(root, path, width, w, h, draw) {
    const t = tex(w, h, draw);
    root.add(ribbon(path, width, new THREE.MeshBasicMaterial({ name: 'ScreenImage', map: t, toneMapped: false, side: THREE.DoubleSide }), 0.034, 90, 'Screen'));
    root.add(ribbon(path, width + 0.01, new THREE.MeshPhysicalMaterial({ name: 'ScreenGlass', color: 0xffffff, roughness: 0.02, metalness: 0, transparent: true, opacity: 0.06, clearcoat: 1, depthWrite: false }), 0.040, 90, 'ScreenGlass'));
  }
  const KEEP = ['Screen', 'ScreenGlass', 'TopperScreen', 'SpinButton', 'BetDownButton', 'BetUpButton', 'MaxBetButton', 'AutoButton', 'FloorGlow'];
  function finish(root) {
    kit.mergeByMaterial(root, KEEP);
    root.traverse((o) => { if (o.isMesh) { o.receiveShadow = false; o.castShadow = !['FloorGlow', 'Screen', 'ScreenGlass', 'TopperScreen'].includes(o.name); } });
    return root;
  }
  return { tex, emis, glowM, spline, body, ribbon, rail, ledRail, label, buttonDeck, floorGlow, screenParts, finish, LED };
}

// ======================= PHARAOH'S BOOK =======================
export function pharaohCabinet(THREE, kit) {
  const C = common(THREE, kit); const root = new THREE.Group(); root.name = 'PharaohCabinet';
  const V = (a, b) => V2(THREE, a, b);
  const piano = new THREE.MeshPhysicalMaterial({ name: 'PianoBlack', color: 0x08070a, roughness: 0.12, clearcoat: 1, clearcoatRoughness: 0.04 });
  const satin = new THREE.MeshPhysicalMaterial({ name: 'SatinBronze', color: 0x2a1d0c, roughness: 0.35, metalness: 0.6, clearcoat: 0.8 });
  const gold = new THREE.MeshStandardMaterial({ name: 'Gold', color: 0xe2b85a, roughness: 0.16, metalness: 1 });
  const lapis = new THREE.MeshPhysicalMaterial({ name: 'Lapis', color: 0x123a8a, roughness: 0.25, metalness: 0.2, clearcoat: 1 });
  const scr = C.spline([[0.43, 0.93], [0.30, 0.975], [0.13, 1.07], [0.035, 1.25], [0.005, 1.55], [-0.01, 1.85], [-0.03, 2.08]]);
  const prof = new THREE.Shape(); prof.moveTo(0.26, 0.0); prof.lineTo(0.29, 0.62); prof.quadraticCurveTo(0.31, 0.84, 0.44, 0.905);
  const sp = []; for (let i = 0; i <= 60; i++) { const p = scr.getPointAt(i / 60); sp.push(V(p.x - 0.014, p.y)); } prof.splineThru(sp);
  prof.quadraticCurveTo(-0.04, 2.17, -0.15, 2.16); prof.lineTo(-0.30, 2.12); prof.quadraticCurveTo(-0.39, 2.06, -0.38, 1.86); prof.lineTo(-0.33, 0.0); prof.closePath();
  root.add(C.body(prof, 0.72, piano));
  // side inlays: lapis panels with gold hieroglyph bands
  const side = C.tex(256, 1024, (c, w, h) => {
    c.fillStyle = '#0d2560'; c.fillRect(0, 0, w, h); c.strokeStyle = '#f2c14e'; c.lineWidth = 10; c.strokeRect(12, 12, w - 24, h - 24);
    c.fillStyle = '#f2c14e'; c.globalAlpha = 0.9;
    for (let y = 60; y < h - 60; y += 74) { const k = (y / 74) % 4 | 0; c.save(); c.translate(w / 2, y);
      if (k === 0) { c.beginPath(); c.ellipse(0, 0, 34, 20, 0, 0, 7); c.lineWidth = 7; c.strokeStyle = '#f2c14e'; c.stroke(); c.beginPath(); c.arc(0, 0, 9, 0, 7); c.fill(); }
      else if (k === 1) { c.lineWidth = 9; c.strokeStyle = '#f2c14e'; c.beginPath(); c.ellipse(0, -16, 12, 16, 0, 0, 7); c.stroke(); c.beginPath(); c.moveTo(-26, 2); c.lineTo(26, 2); c.moveTo(0, 0); c.lineTo(0, 34); c.stroke(); }
      else if (k === 2) { c.beginPath(); c.moveTo(0, -30); c.lineTo(32, 26); c.lineTo(-32, 26); c.closePath(); c.fill(); }
      else { c.beginPath(); c.ellipse(0, 4, 18, 24, 0, 0, 7); c.fill(); c.fillRect(-40, -4, 80, 6); }
      c.restore(); }
  });
  const sideMat = C.emis(side, 0.35, 'PharaohSide'); sideMat.roughness = 0.2;
  for (const sx of [-1, 1]) { const m = new THREE.Mesh(new THREE.PlaneGeometry(0.29, 1.78), sideMat); m.position.set(sx * 0.3605, 1.05, -0.185); m.rotation.y = sx * Math.PI / 2; root.add(m); }
  C.screenParts(root, scr, 0.64, 900, 1750, pharaohPortrait);
  for (const sx of [-1, 1]) root.add(C.rail(scr, sx * 0.333, 0.007, gold, 0.036));
  // chasing gold light rails along the front edges, floor to top
  const edge = C.spline([[0.27, 0.02], [0.29, 0.6], [0.36, 0.86], [0.44, 0.92], [0.30, 0.985], [0.13, 1.08], [0.035, 1.26], [0.0, 1.6], [-0.03, 2.08], [-0.15, 2.155]]);
  for (const sx of [-1, 1]) C.ledRail(root, edge, sx * 0.372, 0.011, 0, 30, [0xffb43a, 0x40e0f0, 0xff8a1a]);
  // button deck in front of the J's bend, on gold brackets
  const deckAng = 9;
  root.add(C.buttonDeck({ pos: [0, 0.885, 0.545], angle: deckAng, w: 0.66, d: 0.16, plate: satin, trim: gold, caps: ['#2a6aff', '#2a6aff', '#ff8a1a', '#18b0c8'], spin: '#ffb020' }));
  for (const sx of [-1, 1]) root.add(kit.roundedBox(0.03, 0.08, 0.16, 0.01, gold, { name: 'DeckBracket', pos: [sx * 0.31, 0.845, 0.52] }));
  root.add(kit.cylinder(0.024, 0.024, 0.68, satin, { name: 'Armrest', pos: [0, 0.865, 0.635], rot: [0, 0, 90], segments: 32 }));
  // plinth with gold underglow, base emblem (eye of Horus)
  root.add(kit.roundedBox(0.66, 0.07, 0.58, 0.02, satin, { name: 'Plinth', pos: [0, 0.035, -0.03] }));
  root.add(kit.box(0.62, 0.006, 0.54, C.glowM(0xff9a20, 2.5, 'Underglow'), { pos: [0, 0.004, -0.03] }));
  const em = new THREE.Mesh(new THREE.CircleGeometry(0.13, 64), C.emis(C.tex(512, 512, (c, w, h) => { c.fillStyle = '#06050a'; c.fillRect(0, 0, w, h); c.strokeStyle = '#f2c14e'; c.lineWidth = 16; c.beginPath(); c.arc(256, 256, 220, 0, 7); c.stroke(); c.lineWidth = 22; c.beginPath(); c.moveTo(90, 256); c.quadraticCurveTo(256, 130, 422, 256); c.quadraticCurveTo(256, 350, 90, 256); c.stroke(); c.fillStyle = '#5ad0ff'; c.beginPath(); c.arc(256, 248, 46, 0, 7); c.fill(); c.strokeStyle = '#f2c14e'; c.beginPath(); c.moveTo(230, 300); c.lineTo(200, 400); c.stroke(); }), 0.9, 'Emblem'));
  em.position.set(0, 0.45, 0.283); em.rotation.x = -0.04; root.add(em);
  root.add(new THREE.Mesh(new THREE.TorusGeometry(0.135, 0.008, 12, 96), gold).translateY(0.45).translateZ(0.283));
  // speaker grilles under the deck
  for (const sx of [-1, 1]) { const g = kit.cylinder(0.05, 0.05, 0.01, kit.mat.metal('#1a1612', 0.5), { name: 'Speaker', pos: [sx * 0.19, 0.72, 0.302], rot: [90 - 6, 0, 0], segments: 40 }); root.add(g); root.add(kit.torus(0.052, 0.005, gold, { pos: [sx * 0.19, 0.72, 0.307], rot: [-6, 0, 0], segments: 40 })); }
  // halo sign: glowing ring with the logo on a slim neck
  root.add(kit.cylinder(0.03, 0.04, 0.14, piano, { name: 'SignNeck', pos: [0, 2.22, -0.14], segments: 24 }));
  const ring = new THREE.Mesh(new THREE.TorusGeometry(0.31, 0.028, 24, 160), C.LED[1]); ring.position.set(0, 2.6, -0.12); root.add(ring);
  const ring2 = new THREE.Mesh(new THREE.TorusGeometry(0.338, 0.01, 12, 160), gold); ring2.position.set(0, 2.6, -0.12); root.add(ring2);
  const ring3 = new THREE.Mesh(new THREE.TorusGeometry(0.285, 0.008, 12, 160), gold); ring3.position.set(0, 2.6, -0.115); root.add(ring3);
  const disc = new THREE.Mesh(new THREE.CircleGeometry(0.285, 96), C.emis(C.tex(768, 768, (c, w, h) => {
    const rg = c.createRadialGradient(w / 2, h / 2, 40, w / 2, h / 2, w / 2); rg.addColorStop(0, '#3a2208'); rg.addColorStop(1, '#0a0603'); c.fillStyle = rg; c.fillRect(0, 0, w, h);
    const gg = c.createLinearGradient(0, 150, 0, 520); gg.addColorStop(0, '#fff6c8'); gg.addColorStop(0.5, '#f2c14e'); gg.addColorStop(1, '#a8721a'); c.fillStyle = gg;
    c.beginPath(); c.moveTo(w / 2, 170); c.lineTo(w / 2 + 170, 420); c.lineTo(w / 2 - 170, 420); c.closePath(); c.fill();
    c.fillStyle = '#5ad0ff'; c.beginPath(); c.arc(w / 2, 330, 26, 0, 7); c.fill();
    c.font = '900 92px Georgia,serif'; c.textAlign = 'center'; c.fillStyle = gg; c.fillText('PHARAOH', w / 2, 540); c.font = '700 44px Georgia,serif'; c.fillText("'S BOOK", w / 2, 600);
  }), 1.15, 'HaloLogo'));
  disc.position.set(0, 2.6, -0.112); root.add(disc);
  // lapis back cap
  root.add(kit.roundedBox(0.5, 0.05, 0.22, 0.02, lapis, { name: 'TopCap', pos: [0, 2.15, -0.22] }));
  root.add(C.floorGlow('#ffb040', 1.5, 1.2, 0.3));
  return C.finish(root);
}

// ======================= DRAGON FORTUNE =======================
export function dragonCabinet(THREE, kit) {
  const C = common(THREE, kit); const root = new THREE.Group(); root.name = 'DragonCabinet';
  const V = (a, b) => V2(THREE, a, b);
  const silver = new THREE.MeshPhysicalMaterial({ name: 'Silver', color: 0x8a8a94, roughness: 0.25, metalness: 0.9, clearcoat: 0.7 });
  const black = new THREE.MeshPhysicalMaterial({ name: 'PianoBlack', color: 0x08070a, roughness: 0.15, clearcoat: 1 });
  const red = new THREE.MeshPhysicalMaterial({ name: 'Lacquer', color: 0x9a0a14, roughness: 0.18, clearcoat: 1, clearcoatRoughness: 0.03 });
  const gold = new THREE.MeshStandardMaterial({ name: 'Gold', color: 0xe2b85a, roughness: 0.16, metalness: 1 });
  const scr = C.spline([[0.20, 1.02], [0.08, 1.24], [0.035, 1.55], [0.07, 1.86], [0.16, 2.07]]);
  const prof = new THREE.Shape(); prof.moveTo(0.27, 0.0); prof.lineTo(0.30, 0.74); prof.quadraticCurveTo(0.33, 0.86, 0.50, 0.90); prof.lineTo(0.52, 0.93); prof.lineTo(0.22, 1.0);
  const sp = []; for (let i = 0; i <= 60; i++) { const p = scr.getPointAt(i / 60); sp.push(V(p.x - 0.016, p.y)); } prof.splineThru(sp);
  prof.lineTo(0.12, 2.16); prof.lineTo(0.05, 2.22); prof.lineTo(0.0, 2.62); prof.quadraticCurveTo(-0.02, 2.68, -0.1, 2.68); prof.lineTo(-0.28, 2.64); prof.quadraticCurveTo(-0.38, 2.5, -0.38, 2.2); prof.lineTo(-0.34, 0.0); prof.closePath();
  root.add(C.body(prof, 0.80, black));
  // red lacquer side wings with a gold cloud pattern
  const wingTex = C.tex(512, 1024, (c, w, h) => {
    c.fillStyle = '#9a0a14'; c.fillRect(0, 0, w, h);
    c.strokeStyle = 'rgba(255,200,90,0.55)'; c.lineWidth = 6;
    for (let y = 80; y < h; y += 150) for (let x = (y / 150 % 2) * 120 + 60; x < w; x += 240) { c.beginPath(); c.arc(x, y, 34, Math.PI, 0); c.arc(x + 50, y, 24, Math.PI, 0); c.arc(x - 44, y + 4, 20, Math.PI, 0); c.stroke(); c.beginPath(); c.arc(x, y + 6, 14, 0, 6); c.stroke(); }
  });
  const wingMat = new THREE.MeshPhysicalMaterial({ name: 'Wing', map: wingTex, roughness: 0.18, clearcoat: 1 });
  for (const sx of [-1, 1]) {
    const w = new THREE.Shape(); w.moveTo(0.29, 0.1); w.lineTo(0.32, 0.78); w.quadraticCurveTo(0.36, 0.88, 0.48, 0.9); w.lineTo(0.22, 1.0);
    const sp2 = []; for (let i = 0; i <= 40; i++) { const p = scr.getPointAt(i / 40); sp2.push(V(p.x - 0.03, p.y)); } w.splineThru(sp2); w.lineTo(0.0, 2.2); w.lineTo(-0.2, 2.2); w.lineTo(-0.2, 0.1); w.closePath();
    const geo = new THREE.ExtrudeGeometry(w, { depth: 0.006, bevelEnabled: true, bevelThickness: 0.012, bevelSize: 0.01, bevelSegments: 4, curveSegments: 3 });
    // planar UVs from the profile so the pattern maps across the wing
    const pos = geo.attributes.position, uv = geo.attributes.uv; for (let i = 0; i < pos.count; i++) uv.setXY(i, (pos.getX(i) + 0.25) / 0.8, pos.getY(i) / 2.3);
    geo.rotateY(-Math.PI / 2); const m = new THREE.Mesh(geo, wingMat); m.position.x = sx * 0.405 + (sx < 0 ? 0 : 0.006); root.add(m);
  }
  C.screenParts(root, scr, 0.70, 900, 1700, dragonMain);
  // micro-LED surround round the main screen (chasing red/gold)
  for (const sx of [-1, 1]) C.ledRail(root, scr, sx * 0.365, 0.012, 0.036, 24, [0xff2a1a, 0xffb43a, 0xff5a1a]);
  // topper: 27" screen with its own LED bars and silver frame
  const tp = new THREE.Mesh(new THREE.PlaneGeometry(0.66, 0.37), new THREE.MeshBasicMaterial({ name: 'TopperImage', map: C.tex(1280, 720, dragonTopper), toneMapped: false }));
  tp.name = 'TopperScreen'; tp.position.set(0, 2.42, 0.078); tp.rotation.x = -0.12; root.add(tp);
  const fr = kit.roundedBox(0.70, 0.41, 0.03, 0.012, silver, { name: 'TopperFrame', pos: [0, 2.42, 0.058], rot: [-0.12 * 180 / Math.PI, 0, 0] }); root.add(fr);
  for (const [y, z] of [[2.225, 0.075], [2.615, 0.03]]) for (let k = 0; k < 9; k++) root.add(kit.box(0.072, 0.012, 0.012, C.LED[k % 3], { pos: [-0.33 + 0.0825 * k, y, z] }));
  // slant deck with the physical buttons (the deck surface rises 13° toward the screen)
  const ang = Math.atan2(0.07, 0.30) * 180 / Math.PI;
  root.add(C.buttonDeck({ pos: [0, 0.998, 0.37], angle: ang, w: 0.70, d: 0.2, plate: kit.mat.metal('#1c1416', 0.45), trim: silver, caps: ['#c8961a', '#c8961a', '#ff6a1a', '#c01a2a'], spin: '#ff2a1a' }));
  root.add(kit.box(0.82, 0.014, 0.014, silver, { name: 'DeckEdge', pos: [0, 0.925, 0.522] }));
  // light rails down the front edges to the floor
  const edge = C.spline([[0.28, 0.02], [0.30, 0.7], [0.37, 0.87], [0.50, 0.915]]);
  for (const sx of [-1, 1]) C.ledRail(root, edge, sx * 0.412, 0.01, 0, 12, [0xff2a1a, 0xffb43a, 0xff5a1a]);
  root.add(kit.roundedBox(0.74, 0.06, 0.6, 0.02, silver, { name: 'Plinth', pos: [0, 0.03, -0.03] }));
  root.add(kit.box(0.70, 0.006, 0.56, C.glowM(0xff2a1a, 2.2, 'Underglow'), { pos: [0, 0.004, -0.03] }));
  const em = new THREE.Mesh(new THREE.CircleGeometry(0.14, 64), C.emis(C.tex(512, 512, (c, w, h) => { const rg = c.createRadialGradient(w / 2, h / 2, 30, w / 2, h / 2, w / 2); rg.addColorStop(0, '#c0101a'); rg.addColorStop(1, '#2a0003'); c.fillStyle = rg; c.fillRect(0, 0, w, h); c.strokeStyle = '#f2c14e'; c.lineWidth = 14; c.beginPath(); c.arc(w / 2, h / 2, 230, 0, 7); c.stroke(); c.font = '900 300px "Noto Serif CJK SC",serif'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillStyle = '#ffd24a'; c.fillText('發', w / 2, h / 2 + 16); }), 1, 'Emblem'));
  em.position.set(0, 0.46, 0.29); root.add(em);
  root.add(new THREE.Mesh(new THREE.TorusGeometry(0.145, 0.008, 12, 96), gold).translateY(0.46).translateZ(0.29));
  for (const sx of [-1, 1]) { root.add(kit.cylinder(0.05, 0.05, 0.01, kit.mat.metal('#1a1214', 0.5), { name: 'Speaker', pos: [sx * 0.2, 0.74, 0.302], rot: [86, 0, 0], segments: 40 })); root.add(kit.torus(0.052, 0.005, silver, { pos: [sx * 0.2, 0.74, 0.307], rot: [-4, 0, 0], segments: 40 })); }
  root.add(C.floorGlow('#ff3020', 1.6, 1.2, 0.3));
  return C.finish(root);
}
