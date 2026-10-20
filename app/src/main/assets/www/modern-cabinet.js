// modern-cabinet.js — a modern casino-style upright cabinet (original design) built in code, real size ≈ 2.0 m.
// Curved portrait video screen (shows reels-screen.js), illuminated topper, chasing LED strips with soft glow,
// tilted button deck with lit buttons and a big round SPIN, glossy body with lit logo panel.
// Front = +Z, metres, pivot at floor centre. Named parts for taps: SpinButton, BetDownButton, BetUpButton,
// MaxBetButton, AutoButton, Screen.
//
//   const cab = buildCabinet(THREE, kit, { screenCanvas });   // kit = await createKit(THREE) (procedural-3d-models)
//   scene.add(cab.root);  cab.update(dt, 'idle'|'spin'|'win'|'free'|'big');  cab.press('SpinButton');
//   cab.screenTexture.needsUpdate = true  (whenever the reel screen redrew)

export function buildCabinet(THREE, kit, { screenCanvas, title = 'ZOHAR', subtitle = 'כסף משחק בלבד', merge = true } = {}) {
  const { mat } = kit, deg = THREE.MathUtils.degToRad;
  const root = kit.group('ZoharCabinet');
  // ---- materials (cached by the kit; shared looks = fewer draw calls)
  const gloss = mat.plastic('#0b0b11', 0.22), satin = mat.plastic('#17121f', 0.5), chrome = mat.metal('#e3e7ec', 0.12);
  const dark = mat.metal('#1c1d23', 0.45), gold = mat.metal('#d6a72c', 0.22), rubber = mat.rubber('#111');
  // LED: unlit, bright, one shared animated texture (a strip of soft dots that "chases" by scrolling the offset)
  const ledTex = (() => {
    const cv = document.createElement('canvas'); cv.width = 64; cv.height = 8; const g = cv.getContext('2d');
    for (let i = 0; i < 8; i++) { const x = i * 8 + 4, rg = g.createRadialGradient(x, 4, 0.5, x, 4, 4.5); rg.addColorStop(0, '#ffffff'); rg.addColorStop(0.5, i % 2 ? '#ffd34d' : '#c46bff'); rg.addColorStop(1, 'rgba(20,0,40,1)'); g.fillStyle = rg; g.fillRect(i * 8, 0, 8, 8); }
    const t = new THREE.CanvasTexture(cv); t.colorSpace = THREE.SRGBColorSpace; t.wrapS = t.wrapT = THREE.RepeatWrapping; t.repeat.set(6, 1); return t;
  })();
  const led = new THREE.MeshBasicMaterial({ map: ledTex, name: 'LED', toneMapped: false });
  const glowTex = (() => {
    const cv = document.createElement('canvas'); cv.width = 16; cv.height = 64; const g = cv.getContext('2d');
    const lg = g.createLinearGradient(0, 0, 16, 0); lg.addColorStop(0, 'rgba(255,255,255,0)'); lg.addColorStop(0.5, 'rgba(255,255,255,1)'); lg.addColorStop(1, 'rgba(255,255,255,0)');
    g.fillStyle = lg; g.fillRect(0, 0, 16, 64); const t = new THREE.CanvasTexture(cv); return t;
  })();
  const glow = new THREE.MeshBasicMaterial({ map: glowTex, color: 0xb066ff, transparent: true, opacity: 0.55, blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide, name: 'Glow', toneMapped: false });
  const lit = (key, w, h, draw, intensity = 1) => {          // a painted panel that glows (emissive = its own artwork)
    const tex = kit.canvasTexture(w, h, draw);
    return new THREE.MeshStandardMaterial({ map: tex, emissiveMap: tex, emissive: 0xffffff, emissiveIntensity: intensity, roughness: 0.35, name: `Lit_${key}` });
  };
  const gradient = (g, x0, y0, x1, y1, stops) => { const lg = g.createLinearGradient(x0, y0, x1, y1); stops.forEach(([o, c]) => lg.addColorStop(o, c)); return lg; };
  const GOLD = [[0, '#fff6c2'], [0.4, '#ffd34d'], [0.7, '#d99a14'], [1, '#8a5a06']];

  // ---- base plinth + belly
  root.add(kit.roundedBox(0.68, 0.08, 0.66, 0.015, dark, { name: 'Plinth', pos: [0, 0.04, 0] }));
  root.add(kit.box(0.64, 0.012, 0.004, led, { name: 'PlinthLED', pos: [0, 0.062, 0.331] }));
  root.add(kit.roundedBox(0.64, 0.74, 0.6, 0.03, gloss, { name: 'Belly', pos: [0, 0.45, -0.01] }));
  const logo = lit('belly', 512, 320, (g, w, h) => {
    g.fillStyle = gradient(g, 0, 0, 0, h, [[0, '#2a0a4a'], [1, '#0a0216']]); g.fillRect(0, 0, w, h);
    for (let i = 0; i < 40; i++) { g.fillStyle = `rgba(255,${180 + (i * 37) % 70},${80 + (i * 53) % 150},${0.15 + (i % 5) * 0.08})`; g.beginPath(); g.arc((i * 97) % w, (i * 61) % h, 2 + (i % 4), 0, 7); g.fill(); }
    g.textAlign = 'center'; g.textBaseline = 'middle'; g.font = '900 150px Georgia, serif'; g.shadowColor = '#c46bff'; g.shadowBlur = 30;
    g.fillStyle = gradient(g, 0, 90, 0, 220, GOLD); g.fillText(title, w / 2, h * 0.46); g.shadowBlur = 0;
    g.font = '700 34px Heebo, Arial, sans-serif'; g.direction = 'rtl'; g.fillStyle = '#e8c7ff'; g.fillText(subtitle, w / 2, h * 0.83);
  }, 0.9);
  root.add(kit.roundedBox(0.5, 0.31, 0.012, 0.012, logo, { name: 'LogoPanel', pos: [0, 0.47, 0.292] }));
  root.add(kit.roundedBox(0.52, 0.33, 0.006, 0.014, chrome, { name: 'LogoFrame', pos: [0, 0.47, 0.287] }));
  // ticket printer / bill validator (realism details)
  root.add(kit.roundedBox(0.13, 0.055, 0.03, 0.008, dark, { name: 'TicketPrinter', pos: [0.16, 0.71, 0.295] }));
  root.add(kit.box(0.09, 0.006, 0.004, mat.emissive('#3dff8a', 2.5), { name: 'TicketSlot', pos: [0.16, 0.715, 0.311] }));
  root.add(kit.roundedBox(0.09, 0.055, 0.03, 0.008, dark, { name: 'CardReader', pos: [-0.17, 0.71, 0.295] }));
  root.add(kit.box(0.05, 0.022, 0.004, mat.emissive('#4cc9ff', 1.8), { name: 'CardLCD', pos: [-0.17, 0.716, 0.311] }));
  // chrome edge trims on the belly
  for (const x of [-0.318, 0.318]) {
    root.add(kit.box(0.012, 0.7, 0.006, led, { name: 'BellyLED', pos: [x, 0.45, 0.288] }));
    root.add(kit.cylinder(0.005, 0.005, 0.7, chrome, { name: 'Trim', pos: [x * 0.955, 0.45, 0.292], segments: 12 }));
  }

  // ---- button deck (tilted 12° toward the player), buttons in deck space
  const deck = kit.group('Deck', { pos: [0, 0.875, 0.36], rot: [12, 0, 0] });
  deck.add(kit.roundedBox(0.66, 0.05, 0.25, 0.018, dark, { name: 'DeckBody' }));
  deck.add(kit.box(0.62, 0.008, 0.004, led, { name: 'DeckLED', pos: [0, -0.005, 0.127] }));
  deck.add(kit.roundedBox(0.64, 0.004, 0.22, 0.002, satin, { name: 'DeckTop', pos: [0, 0.026, 0] }));
  const buttons = {};
  const labelBtn = (name, text, color, x) => {
    const m = lit(`btn-${name}`, 128, 64, (g, w, h) => {
      g.fillStyle = gradient(g, 0, 0, 0, h, [[0, color], [1, '#111']]); g.fillRect(0, 0, w, h);
      g.fillStyle = 'rgba(255,255,255,0.25)'; g.fillRect(0, 0, w, h * 0.3);
      g.fillStyle = '#fff'; g.font = '800 24px Heebo, Arial, sans-serif'; g.textAlign = 'center'; g.textBaseline = 'middle'; g.direction = 'rtl'; g.fillText(text, w / 2, h / 2 + 2);
    }, 0.8);
    const b = kit.roundedBox(0.075, 0.016, 0.042, 0.006, m, { name, pos: [x, 0.036, 0.05] });
    b.userData.rest = b.position.clone(); deck.add(b); buttons[name] = b;
    deck.add(kit.roundedBox(0.083, 0.006, 0.05, 0.004, chrome, { name: `${name}Bezel`, pos: [x, 0.029, 0.05] }));
  };
  labelBtn('BetDownButton', 'הימור −', '#3a5bd9', -0.265);
  labelBtn('BetUpButton', 'הימור +', '#3a5bd9', -0.175);
  labelBtn('MaxBetButton', 'מקסימום', '#d97a1a', -0.085);
  labelBtn('AutoButton', 'אוטומטי', '#7a2bd9', 0.005);
  const spinMat = new THREE.MeshStandardMaterial({ color: 0x18c95a, emissive: 0x18ff6a, emissiveIntensity: 1.2, roughness: 0.25, name: 'SpinLight' });
  const spin = kit.cylinder(0.046, 0.05, 0.026, spinMat, { name: 'SpinButton', pos: [0.2, 0.045, 0.03], segments: 40 });
  spin.userData.rest = spin.position.clone(); deck.add(spin); buttons.SpinButton = spin;
  deck.add(kit.torus(0.053, 0.007, chrome, { name: 'SpinRing', pos: [0.2, 0.035, 0.03], rot: [90, 0, 0], segments: 48 }));
  const spinLabel = lit('spin-label', 128, 128, (g, w, h) => { g.clearRect(0, 0, w, h); g.fillStyle = '#eafff1'; g.font = '900 34px Heebo, Arial, sans-serif'; g.textAlign = 'center'; g.textBaseline = 'middle'; g.direction = 'rtl'; g.fillText('סובב', w / 2, h / 2); }, 1);
  spinLabel.transparent = true; spinLabel.alphaTest = 0.4;
  spin.add(kit.mesh(new THREE.PlaneGeometry(0.07, 0.07), spinLabel, { name: 'SpinButton', pos: [0, 0.0135, 0], rot: [-90, 0, 0] }));
  root.add(deck);

  // ---- curved portrait screen (cylindrical, axis X, concave toward the player)
  const SW = 0.56, SH = 0.84, RAD = 1.4, ARC = SH / RAD, SY = 1.38, SZ = 0.15, TILT = 6;
  function curved(w, arcLen, segY = 28, zOff = 0) {
    const a = arcLen / RAD, pos = [], uv = [], idx = [];
    for (let j = 0; j <= segY; j++) {
      const v = j / segY, th = (v - 0.5) * a;
      for (let i = 0; i <= 1; i++) { pos.push((i - 0.5) * w, RAD * Math.sin(th), RAD * (1 - Math.cos(th)) + zOff); uv.push(i, v); }
    }
    for (let j = 0; j < segY; j++) { const k = j * 2; idx.push(k, k + 1, k + 2, k + 1, k + 3, k + 2); }
    const geo = new THREE.BufferGeometry(); geo.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3)); geo.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2)); geo.setIndex(idx); geo.computeVertexNormals(); return geo;
  }
  const head = kit.group('ScreenHead', { pos: [0, SY, SZ], rot: [-TILT, 0, 0] });
  const screenTexture = new THREE.CanvasTexture(screenCanvas);
  Object.assign(screenTexture, { colorSpace: THREE.SRGBColorSpace, generateMipmaps: false, minFilter: THREE.LinearFilter, anisotropy: 4 });
  const screenMat = new THREE.MeshBasicMaterial({ map: screenTexture, toneMapped: false, name: 'ScreenImage' });
  head.add(kit.mesh(curved(SW, SH), screenMat, { name: 'Screen' }));
  // glass: faint reflections of the room over the image (one transparent layer)
  const glass = new THREE.MeshStandardMaterial({ color: 0xffffff, roughness: 0.04, metalness: 0.6, transparent: true, opacity: 0.1, name: 'ScreenGlass', depthWrite: false });
  head.add(kit.mesh(curved(SW, SH, 28, 0.003), glass, { name: 'ScreenGlass' }));
  head.add(kit.mesh(curved(SW + 0.05, SH + 0.05, 28, -0.004), gloss, { name: 'Bezel' }));
  // side wings following the curve (hide the housing), with LED + glow on their front edge
  const a = (SH + 0.05) / RAD, edge = []; for (let j = 0; j <= 16; j++) { const th = (j / 16 - 0.5) * a; edge.push([RAD * (1 - Math.cos(th)), RAD * Math.sin(th)]); }
  const wingOutline = [[-0.38, edge[0][1] - 0.02], ...edge.map(([z, y]) => [z + 0.004, y]), [-0.38, edge[16][1] + 0.02]];
  for (const side of [-1, 1]) {
    const wing = kit.extrude(wingOutline, 0.026, gloss, { name: 'Wing', rot: [0, -90, 0], pos: [side * (SW / 2 + 0.038), 0, 0], bevel: 0.004 });
    head.add(wing);
    const pts = edge.map(([z, y]) => [side * (SW / 2 + 0.038), y, z + 0.012]);
    head.add(kit.tube(pts, 0.007, led, { name: 'SideLED', segments: 48, radialSegments: 8 }));
    const gl = kit.mesh(new THREE.PlaneGeometry(0.06, SH + 0.08), glow, { name: 'Glow', pos: [side * (SW / 2 + 0.04), 0, 0.04] });
    head.add(gl);
  }
  // housing behind the screen + speaker bar under it
  head.add(kit.roundedBox(SW + 0.11, SH + 0.1, 0.4, 0.03, gloss, { name: 'Housing', pos: [0, 0, -0.205] }));
  const grille = lit('grille', 256, 32, (g, w, h) => { g.fillStyle = '#08060c'; g.fillRect(0, 0, w, h); for (let x = 6; x < w; x += 9) for (let y = 6; y < h; y += 9) { g.fillStyle = '#2a2433'; g.beginPath(); g.arc(x, y, 2.3, 0, 7); g.fill(); } g.fillStyle = '#c46bff'; g.fillRect(0, h - 3, w, 3); }, 0.6);
  head.add(kit.roundedBox(SW, 0.035, 0.03, 0.01, grille, { name: 'SpeakerBar', pos: [0, -SH / 2 - 0.045, 0.02] }));
  root.add(head);

  // ---- topper sign
  const topper = kit.group('Topper', { pos: [0, SY + SH / 2 + 0.15, 0.0] });
  topper.add(kit.roundedBox(0.68, 0.22, 0.22, 0.05, gloss, { name: 'TopperBody' }));
  const sign = lit('topper', 1024, 256, (g, w, h) => {
    g.fillStyle = gradient(g, 0, 0, w, 0, [[0, '#1a0533'], [0.5, '#3a0a66'], [1, '#1a0533']]); g.fillRect(0, 0, w, h);
    g.strokeStyle = 'rgba(255,211,77,0.5)'; g.lineWidth = 3; for (let i = 0; i < 18; i++) { g.beginPath(); g.moveTo(w / 2, h * 1.2); g.lineTo(i * w / 17, -10); g.stroke(); }
    g.textAlign = 'center'; g.textBaseline = 'middle'; g.font = '900 170px Georgia, serif'; g.shadowColor = '#ffb400'; g.shadowBlur = 40;
    g.lineWidth = 10; g.strokeStyle = '#3a1500'; g.strokeText(title, w / 2, h * 0.54); g.fillStyle = gradient(g, 0, 40, 0, 210, GOLD); g.fillText(title, w / 2, h * 0.54);
  }, 0.7);
  topper.add(kit.roundedBox(0.62, 0.17, 0.01, 0.01, sign, { name: 'TopperSign', pos: [0, 0, 0.11] }));
  topper.add(kit.box(0.64, 0.008, 0.006, led, { name: 'TopperLED', pos: [0, 0.097, 0.108] }));
  topper.add(kit.box(0.64, 0.008, 0.006, led, { name: 'TopperLED', pos: [0, -0.097, 0.108] }));
  root.add(topper);
  // neck between deck and screen (closes the gap)
  root.add(kit.roundedBox(0.64, 0.12, 0.42, 0.02, gloss, { name: 'Neck', pos: [0, 0.9, -0.08] }));

  const keep = ['SpinButton', 'BetDownButton', 'BetUpButton', 'MaxBetButton', 'AutoButton', 'Screen', 'ScreenGlass', 'Glow'];
  if (merge) kit.mergeByMaterial(root, keep);
  root.traverse((o) => {
    if (o.name === 'Glow') o.raycast = () => {};          // glow halos must not swallow taps
    if (!o.isMesh) return;
    // phones: only the big body pieces cast the floor shadow; nothing receives (no self-shadow pass per material)
    o.receiveShadow = false;
    o.castShadow = /^Merged_(Plastic|Metal)/.test(o.name) && o.geometry.attributes.position.count > 2000;
  });
  const model = kit.finalize(root, {});        // real size as built (≈ 2.0 m); scale the whole thing for tabletop

  // ---- animation: LED chase speed/colour by mood, SPIN pulse, button press travel
  const moods = { idle: [0.6, 0xffffff, 0x9a4dff, 0.45], spin: [3.5, 0xffffff, 0x6aa8ff, 0.7], win: [2.2, 0xfff0a0, 0xffc233, 0.9], free: [2.8, 0xffb3e6, 0xff4dc4, 0.9], big: [5.0, 0xfff7cc, 0xffd34d, 1.0] };
  const pressT = {}; let t = 0, ledCol = new THREE.Color(1, 1, 1), glowCol = new THREE.Color(0x9a4dff);
  function update(dt, mood = 'idle') {
    t += dt; const [speed, lc, gc, gop] = moods[mood] || moods.idle;
    ledTex.offset.x = (ledTex.offset.x - speed * dt) % 1;
    ledCol.lerp(new THREE.Color(lc), Math.min(1, dt * 4)); led.color.copy(ledCol);
    glowCol.lerp(new THREE.Color(gc), Math.min(1, dt * 4)); glow.color.copy(glowCol);
    glow.opacity = gop * (mood === 'big' ? 0.6 + 0.4 * Math.sin(t * 18) : 0.85 + 0.15 * Math.sin(t * 2));
    spinMat.emissiveIntensity = mood === 'idle' ? 0.8 + 0.6 * (0.5 + 0.5 * Math.sin(t * 3)) : 0.5;
    for (const [name, b] of Object.entries(buttons)) {
      if (pressT[name] === undefined) continue; pressT[name] += dt; const k = pressT[name];
      const down = k < 0.06 ? k / 0.06 : Math.max(0, 1 - (k - 0.06) / 0.12);
      b.position.y = b.userData.rest.y - 0.006 * down; if (k > 0.2) { delete pressT[name]; b.position.y = b.userData.rest.y; }
    }
  }
  const press = (name) => { if (buttons[name]) pressT[name] = 0; };
  return { root: model, screenTexture, update, press, buttons, materials: { led, glow, spinMat, screenMat } };
}
