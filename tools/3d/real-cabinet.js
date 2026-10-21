// real-cabinet.js — realistic upright video-slot cabinet (original design "ZOHAR"), built for AR at true size.
// One continuous side profile (like real cabinets), candy-paint side panels, clear-coated lacquer body,
// concave curved portrait screen (0.60 × 1.00 m), chasing light pipes (LED_0/1/2 materials, animated at runtime),
// lit button deck with padded armrest, bill validator, ticket printer, player-tracking module, speakers,
// arched topper, chrome pull lever on the right side, floor light-wash.
// Front = +Z, metres, floor at y = 0. Named parts for the app: Screen, SpinButton, BetDownButton, BetUpButton,
// MaxBetButton, AutoButton, LeverPivot, LeverKnob, FloorGlow, LED_0..2 (materials), TopperSign.
export function buildRealCabinet(THREE, kit, { screenCanvas, title = 'ZOHAR', merge = true } = {}) {
  const { mat } = kit, D2R = Math.PI / 180;
  const root = kit.group('ZoharCabinet');

  // ---------- materials ----------
  const lacquer = new THREE.MeshPhysicalMaterial({ name: 'Lacquer', color: 0x07070a, roughness: 0.32, metalness: 0, clearcoat: 1, clearcoatRoughness: 0.04 });
  const candy = new THREE.MeshPhysicalMaterial({ name: 'CandyPaint', color: 0x3a0f6b, roughness: 0.38, metalness: 0.75, clearcoat: 1, clearcoatRoughness: 0.03 });
  const chrome = new THREE.MeshStandardMaterial({ name: 'Chrome', color: 0xf2f4f7, roughness: 0.06, metalness: 1 });
  const darkMetal = new THREE.MeshStandardMaterial({ name: 'DarkMetal', color: 0x1b1c21, roughness: 0.38, metalness: 0.9, normalMap: brushedNormal(256), normalScale: new THREE.Vector2(0.5, 0.5) });
  const steel = new THREE.MeshStandardMaterial({ name: 'Steel', color: 0xb9bec6, roughness: 0.28, metalness: 1, normalMap: brushedNormal(256), normalScale: new THREE.Vector2(0.4, 0.4) });
  const glassMat = new THREE.MeshPhysicalMaterial({ name: 'BellyGlass', color: 0xffffff, roughness: 0.03, metalness: 0, transparent: true, opacity: 0.1, clearcoat: 1, depthWrite: false });
  const blackPlastic = new THREE.MeshStandardMaterial({ name: 'BlackPlastic', color: 0x101014, roughness: 0.55, metalness: 0 });
  const leather = new THREE.MeshStandardMaterial({ name: 'Leather', color: 0x141216, roughness: 0.62, metalness: 0,
    normalMap: noiseNormal(256, 2.2), normalScale: new THREE.Vector2(0.6, 0.6) });
  const red = new THREE.MeshPhysicalMaterial({ name: 'KnobRed', color: 0xc8101e, roughness: 0.18, metalness: 0, clearcoat: 1, clearcoatRoughness: 0.02 });
  const LED = [0, 1, 2].map((i) => new THREE.MeshStandardMaterial({ name: `LED_${i}`, color: 0x000000, emissive: [0xff4fd8, 0xffd34d, 0x8a5cff][i], emissiveIntensity: 4, roughness: 0.3 }));
  const grilleMat = new THREE.MeshStandardMaterial({ name: 'Grille', color: 0x1a1a1f, roughness: 0.4, metalness: 0.85,
    normalMap: perforationNormal(256), normalScale: new THREE.Vector2(1.2, 1.2), map: perforationColor(256) });
  const lit = (key, w, h, draw, intensity = 1) => {
    const tex = kit.canvasTexture(w, h, draw);
    return new THREE.MeshPhysicalMaterial({ name: `Lit_${key}`, map: tex, emissiveMap: tex, emissive: 0xffffff, emissiveIntensity: intensity, roughness: 0.18, metalness: 0, clearcoat: 1, clearcoatRoughness: 0.03 });
  };
  const grad = (g, x0, y0, x1, y1, stops) => { const l = g.createLinearGradient(x0, y0, x1, y1); stops.forEach(([o, c]) => l.addColorStop(o, c)); return l; };
  const GOLD = [[0, '#fff8d0'], [0.35, '#ffd34d'], [0.7, '#d18f0c'], [1, '#7a4d04']];

  // ---------- side profile (z, y): plinth top → belly → deck → curved screen → top → back ----------
  const B = [0.155, 0.93], L = 1.0, TILT = 7 * D2R, R = 1.4;
  const d = [-Math.sin(TILT), Math.cos(TILT)], n = [Math.cos(TILT), Math.sin(TILT)];            // chord dir, front normal
  const sag = (t) => Math.sqrt(R * R - ((t - 0.5) * L) ** 2) - Math.sqrt(R * R - (L / 2) ** 2);
  const arc = (t, off = 0) => [B[0] + t * L * d[0] - n[0] * (sag(t) - off), B[1] + t * L * d[1] - n[1] * (sag(t) - off)];
  const T = arc(1);
  const arcPts = (off, k = 24) => Array.from({ length: k + 1 }, (_, i) => arc(i / k, off));
  const profile = (front) => [
    [-0.32, 0.09], [0.30 + front, 0.09], [0.30 + front, 0.775], [0.445 + front, 0.80], [0.452 + front, 0.835], [0.17 + front, 0.878],
    ...arcPts(-0.012 + front), [T[0] + front - 0.01, 1.955], [-0.27, 1.955], [-0.31, 1.93], [-0.32, 1.88],
  ];
  // body (lacquer) + candy side panels that stand 2 cm proud at the front
  root.add(kit.extrude(profile(0), 0.64, lacquer, { name: 'Body', rot: [0, -90, 0], bevel: 0.006 }));
  for (const s of [-1, 1]) root.add(kit.extrude(profile(0.02), 0.026, candy, { name: 'SidePanel', rot: [0, -90, 0], pos: [s * 0.333, 0, 0], bevel: 0.008 }));
  // plinth (recessed toe kick)
  root.add(kit.roundedBox(0.68, 0.09, 0.6, 0.012, darkMetal, { name: 'Plinth', pos: [0, 0.045, -0.02] }));

  // ---------- light pipes along the side panels' front edges (3-phase chase) ----------
  const edgePath = [[0.322, 0.1], [0.322, 0.775], [0.467, 0.8], [0.474, 0.835], [0.192, 0.878], ...arcPts(0.008 + 0.02, 30), [T[0] + 0.012, 1.95]];
  let seg = 0;
  const pipe = (pts, x) => {
    for (let i = 0; i < pts.length - 1; i++) {
      const [z0, y0] = pts[i], [z1, y1] = pts[i + 1], len = Math.hypot(z1 - z0, y1 - y0);
      const k = Math.max(1, Math.round(len / 0.032));
      for (let j = 0; j < k; j++) {
        const a = (j + 0.5) / k, z = z0 + (z1 - z0) * a, y = y0 + (y1 - y0) * a;
        const ang = Math.atan2(z1 - z0, y1 - y0);                      // rotate +Y onto the path tangent
        const ox = Math.cos(ang) * 0.004, oy = -Math.sin(ang) * 0.004;  // push out along the outward normal
        const m = new THREE.Mesh(new THREE.BoxGeometry(0.012, len / k * 0.82, 0.006), LED[seg++ % 3]);
        m.position.set(x, y + oy, z + ox); m.rotation.x = ang; m.name = 'LightPipe'; root.add(m);
      }
    }
  };
  for (const s of [-1, 1]) { seg = 0; pipe(edgePath, s * 0.333); }
  // chrome edge trims either side of each light pipe (what makes real cabinets read as metal-framed)
  for (const s of [-1, 1]) for (const x of [0.3205, 0.3455]) {
    const pts = edgePath.map(([z, y]) => [s * x, y, z + 0.002]);
    root.add(kit.tube(pts, 0.0032, chrome, { name: 'EdgeTrim', segments: 160, radialSegments: 8 }));
  }

  // ---------- belly: art glass, speakers, bill validator, ticket printer, player tracking ----------
  const belly = lit('belly', 768, 560, (g, w, h) => {
    g.fillStyle = grad(g, 0, 0, 0, h, [[0, '#2b0750'], [0.6, '#12021f'], [1, '#05010a']]); g.fillRect(0, 0, w, h);
    for (let i = 0; i < 26; i++) { g.strokeStyle = `rgba(255,${190 + (i * 13) % 60},90,${0.08 + (i % 4) * 0.03})`; g.lineWidth = 2 + (i % 3); g.beginPath(); g.moveTo(w / 2, h * 1.15); g.lineTo((i / 25) * w * 1.4 - w * 0.2, -20); g.stroke(); }
    for (let i = 0; i < 70; i++) { g.fillStyle = `rgba(255,${200 + (i * 7) % 55},${120 + (i * 31) % 120},${0.25 + (i % 5) * 0.12})`; g.beginPath(); g.arc((i * 137) % w, (i * 71) % h, 1 + (i % 4), 0, 7); g.fill(); }
    g.textAlign = 'center'; g.textBaseline = 'middle'; g.font = '900 190px Georgia, serif'; g.shadowColor = '#c46bff'; g.shadowBlur = 40;
    g.lineWidth = 10; g.strokeStyle = '#2a1000'; g.strokeText(title, w / 2, h * 0.47); g.fillStyle = grad(g, 0, 150, 0, 330, GOLD); g.fillText(title, w / 2, h * 0.47); g.shadowBlur = 0;
    g.font = '700 38px Heebo, Arial, sans-serif'; g.fillStyle = '#ead6ff'; g.fillText('VIDEO SLOTS · PLAY MONEY ONLY', w / 2, h * 0.84);
  }, 0.85);
  root.add(kit.roundedBox(0.54, 0.38, 0.008, 0.01, belly, { name: 'BellyArt', pos: [0, 0.4, 0.305] }));
  root.add(kit.roundedBox(0.565, 0.405, 0.006, 0.014, chrome, { name: 'BellyFrame', pos: [0, 0.4, 0.302] }));
  root.add(kit.roundedBox(0.66, 0.06, 0.006, 0.004, steel, { name: 'KickPlate', pos: [0, 0.05, 0.283] }));
  // floor-level speaker grille
  root.add(kit.roundedBox(0.5, 0.085, 0.006, 0.02, grilleMat, { name: 'LowSpeaker', pos: [0, 0.155, 0.304] }));
  // control row above the art glass
  const ptLcd = lit('pt', 256, 112, (g, w, h) => {
    g.fillStyle = grad(g, 0, 0, 0, h, [[0, '#0b2a4a'], [1, '#04101e']]); g.fillRect(0, 0, w, h);
    g.fillStyle = '#7fd4ff'; g.font = '800 30px Heebo, Arial, sans-serif'; g.textAlign = 'center'; g.fillText('ZOHAR CLUB', w / 2, 44);
    g.font = '500 22px Heebo, Arial, sans-serif'; g.fillStyle = '#b8e6ff'; g.fillText('INSERT CARD · FOR FUN', w / 2, 84);
  }, 1.1);
  root.add(kit.roundedBox(0.15, 0.085, 0.03, 0.008, blackPlastic, { name: 'PTModule', pos: [-0.17, 0.68, 0.3] }));
  root.add(kit.box(0.12, 0.052, 0.002, ptLcd, { name: 'PTScreen', pos: [-0.17, 0.688, 0.3155] }));
  root.add(kit.box(0.07, 0.004, 0.003, mat.emissive('#4cc9ff', 2), { name: 'CardSlot', pos: [-0.17, 0.648, 0.3155] }));
  root.add(kit.roundedBox(0.105, 0.105, 0.035, 0.01, blackPlastic, { name: 'BillValidator', pos: [0.17, 0.675, 0.3] }));
  root.add(kit.roundedBox(0.085, 0.085, 0.004, 0.012, mat.emissive('#3dff8a', 1.6), { name: 'BillBezel', pos: [0.17, 0.675, 0.318] }));
  root.add(kit.box(0.07, 0.006, 0.006, mat.matte('#050505'), { name: 'BillSlot', pos: [0.17, 0.675, 0.321] }));
  root.add(kit.roundedBox(0.11, 0.04, 0.03, 0.008, darkMetal, { name: 'TicketPrinter', pos: [0, 0.665, 0.3] }));
  root.add(kit.box(0.08, 0.004, 0.003, mat.emissive('#ffd34d', 1.6), { name: 'TicketSlot', pos: [0, 0.667, 0.3155] }));

  // ---------- button deck (tilted with the profile), padded armrest ----------
  const DECK_ANG = Math.atan2(0.878 - 0.835, 0.452 - 0.17) / D2R;   // ≈ 8.7°
  const deck = kit.group('Deck', { pos: [0, 0.857, 0.311], rot: [DECK_ANG, 0, 0] });
  deck.add(kit.roundedBox(0.6, 0.006, 0.25, 0.003, kit.mat.metal('#2a2b31', 0.5), { name: 'DeckPlate', pos: [0, 0.0, 0] }));
  const buttons = [['BetDownButton', 'BET −', '#2f6bff', -0.235], ['BetUpButton', 'BET +', '#2f6bff', -0.145], ['MaxBetButton', 'MAX BET', '#ff8a1a', -0.055], ['AutoButton', 'AUTO', '#a33bff', 0.035]];
  // label decals lie flat on each cap (unlit white lettering), children of the cap so they sink with it when pressed
  const label = (text, w, h, px) => {
    const t = kit.canvasTexture(Math.round(w * 4000), Math.round(h * 4000), (g, W, H) => { g.clearRect(0, 0, W, H); g.fillStyle = '#ffffff'; g.font = `900 ${px}px Arial, sans-serif`; g.textAlign = 'center'; g.textBaseline = 'middle'; g.lineWidth = Math.max(4, px * 0.14); g.strokeStyle = 'rgba(0,0,0,0.85)'; g.lineJoin = 'round'; g.strokeText(text, W / 2, H / 2 + 2); g.fillText(text, W / 2, H / 2 + 2); });
    const m = new THREE.Mesh(new THREE.PlaneGeometry(w, h), new THREE.MeshBasicMaterial({ name: `Label_${text}`, map: t, transparent: true, depthWrite: false, toneMapped: false }));
    m.rotation.x = -Math.PI / 2; m.name = `Label_${text}`; return m;
  };
  for (const [name, text, color, x] of buttons) {
    const cap = new THREE.MeshPhysicalMaterial({ name: `Cap_${name}`, color, emissive: color, emissiveIntensity: 0.55, roughness: 0.12, clearcoat: 1, clearcoatRoughness: 0.02 });
    const btn = kit.roundedBox(0.074, 0.016, 0.046, 0.007, cap, { name, pos: [x, 0.011, 0.055] });
    const lb = label(text, 0.068, 0.04, 54); lb.position.y = 0.0085; btn.add(lb);
    deck.add(btn);
    deck.add(kit.roundedBox(0.084, 0.008, 0.056, 0.004, chrome, { name: `${name}Bezel`, pos: [x, 0.004, 0.055] }));
  }
  const spinCap = new THREE.MeshPhysicalMaterial({ name: 'Cap_Spin', color: 0x19d65f, emissive: 0x19ff6c, emissiveIntensity: 0.8, roughness: 0.1, clearcoat: 1, clearcoatRoughness: 0.02 });
  const spin = kit.cylinder(0.048, 0.05, 0.024, spinCap, { name: 'SpinButton', pos: [0.2, 0.014, 0.03], segments: 48 });
  const sl = label('SPIN', 0.085, 0.045, 90); sl.position.y = 0.0125; spin.add(sl);
  deck.add(spin);
  deck.add(kit.torus(0.054, 0.007, chrome, { name: 'SpinRing', pos: [0.2, 0.006, 0.03], rot: [90, 0, 0], segments: 56 }));
  const deckLcd = lit('decklcd', 1024, 160, (g, w, h) => {
    g.fillStyle = '#060a14'; g.fillRect(0, 0, w, h); g.strokeStyle = '#1f3a66'; g.lineWidth = 4; g.strokeRect(3, 3, w - 6, h - 6);
    g.fillStyle = '#9fd0ff'; g.font = '700 58px Arial, sans-serif'; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText('20 LINES · WILD · FREE SPINS ×3', w / 2, h / 2);
  }, 1.0);
  const lcd = new THREE.Mesh(new THREE.PlaneGeometry(0.34, 0.053), deckLcd); lcd.rotation.x = -Math.PI / 2; lcd.position.set(-0.1, 0.0045, -0.06); lcd.name = 'DeckDisplay'; deck.add(lcd);
  deck.add(kit.cylinder(0.005, 0.005, 0.6, chrome, { name: 'DeckLip', pos: [0, 0.003, 0.127], rot: [0, 0, 90], segments: 12 }));
  root.add(deck);
  root.add(kit.cylinder(0.026, 0.026, 0.66, leather, { name: 'Armrest', pos: [0, 0.818, 0.452], rot: [0, 0, 90], segments: 32 }));

  // ---------- curved screen, bezel, glass ----------
  const curved = (w, off, segY = 40) => {
    const pos = [], uv = [], idx = [];
    for (let j = 0; j <= segY; j++) {
      const t = j / segY, [z, y] = arc(t, off);
      for (let i = 0; i <= 1; i++) { pos.push((i - 0.5) * w, y, z); uv.push(i, t); }
    }
    for (let j = 0; j < segY; j++) { const k = j * 2; idx.push(k, k + 1, k + 2, k + 1, k + 3, k + 2); }
    const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3)); g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2)); g.setIndex(idx); g.computeVertexNormals(); return g;
  };
  const screenTex = new THREE.CanvasTexture(screenCanvas); screenTex.colorSpace = THREE.SRGBColorSpace;
  root.add(kit.mesh(curved(0.6, 0), new THREE.MeshBasicMaterial({ name: 'ScreenImage', map: screenTex, toneMapped: false }), { name: 'Screen' }));
  root.add(kit.mesh(curved(0.6, 0.004), new THREE.MeshPhysicalMaterial({ name: 'ScreenGlass', color: 0xffffff, roughness: 0.02, metalness: 0, transparent: true, opacity: 0.07, clearcoat: 1, depthWrite: false }), { name: 'ScreenGlass' }));
  // bezel: a curved strip slightly larger than the screen, just behind it
  const bz = new THREE.Mesh(curved(0.664, -0.003), lacquer); bz.name = 'Bezel'; bz.scale.set(1, 1, 1); root.add(bz);
  for (const t of [-0.03, 1.03]) { // extend bezel above/below the image
    const [z, y] = arc(Math.min(1, Math.max(0, t)), -0.003);
    root.add(kit.roundedBox(0.664, 0.034, 0.012, 0.005, lacquer, { name: 'BezelEdge', pos: [0, y + (t < 0 ? -0.016 : 0.016), z - 0.002], rot: [-7, 0, 0] }));
  }
  // speaker bar with light under the screen
  root.add(kit.roundedBox(0.56, 0.03, 0.025, 0.01, grilleMat, { name: 'SpeakerBar', pos: [0, 0.9, 0.162], rot: [-7, 0, 0] }));

  // ---------- arched topper ----------
  const archShape = (w, hSide, hPeak, k = 28) => {
    const pts = [[-w / 2, 0], [w / 2, 0], [w / 2, hSide]];
    for (let i = 1; i < k; i++) { const a = i / k, x = w / 2 - a * w; pts.push([x, hSide + (hPeak - hSide) * Math.sin(a * Math.PI)]); }
    pts.push([-w / 2, hSide]); return pts;
  };
  const topper = kit.group('Topper', { pos: [0, 1.955, -0.075] });
  topper.add(kit.extrude(archShape(0.69, 0.13, 0.25), 0.25, lacquer, { name: 'TopperBody', bevel: 0.008 }));
  const signShape = archShape(0.62, 0.105, 0.215);
  const sg = new THREE.ShapeGeometry(new THREE.Shape(signShape.map(([x, y]) => new THREE.Vector2(x, y))), 32);
  { const p = sg.attributes.position, uv = sg.attributes.uv; for (let i = 0; i < p.count; i++) uv.setXY(i, (p.getX(i) + 0.31) / 0.62, p.getY(i) / 0.215); }
  const sign = lit('topper', 1024, 356, (g, w, h) => {
    g.fillStyle = grad(g, 0, 0, w, 0, [[0, '#1d0538'], [0.5, '#4a0c88'], [1, '#1d0538']]); g.fillRect(0, 0, w, h);
    g.strokeStyle = 'rgba(255,214,90,0.35)'; g.lineWidth = 4; for (let i = 0; i < 22; i++) { g.beginPath(); g.moveTo(w / 2, h * 1.1); g.lineTo(i * w / 21, -10); g.stroke(); }
    g.textAlign = 'center'; g.textBaseline = 'middle'; g.font = '900 210px Georgia, serif'; g.shadowColor = '#ffae00'; g.shadowBlur = 36;
    g.lineWidth = 12; g.strokeStyle = '#2a1000'; g.strokeText(title, w / 2, h * 0.6); g.fillStyle = grad(g, 0, 90, 0, 300, GOLD); g.fillText(title, w / 2, h * 0.6);
  }, 0.75);
  const signMesh = new THREE.Mesh(sg, sign); signMesh.name = 'TopperSign'; signMesh.position.set(0, 0.012, 0.137); topper.add(signMesh);
  // LED rim along the arch
  const rim = archShape(0.655, 0.12, 0.237, 40).slice(2);
  for (let i = 0; i < rim.length - 1; i++) {
    const [x0, y0] = rim[i], [x1, y1] = rim[i + 1], m = new THREE.Mesh(new THREE.BoxGeometry(Math.hypot(x1 - x0, y1 - y0) * 0.8, 0.01, 0.008), LED[i % 3]);
    m.position.set((x0 + x1) / 2, (y0 + y1) / 2 + 0.006, 0.138); m.rotation.z = Math.atan2(y1 - y0, x1 - x0); m.name = 'TopperLED'; topper.add(m);
  }
  topper.add(kit.tube(archShape(0.672, 0.124, 0.245, 40).slice(2).map(([x, y]) => [x, y + 0.006, 0.134]), 0.004, chrome, { name: 'TopperRim', segments: 120, radialSegments: 8 }));
  root.add(topper);
  // candle (tower light) on top: chrome pole + two glowing tiers, animated by the app (Candle_A / Candle_B)
  const candle = kit.group('Candle', { pos: [0.24, 1.955 + 0.2, -0.16] });
  candle.add(kit.cylinder(0.012, 0.014, 0.14, chrome, { name: 'CandlePole', pos: [0, 0.07, 0], segments: 20 }));
  candle.add(kit.cylinder(0.034, 0.034, 0.008, chrome, { name: 'CandleRing', pos: [0, 0.144, 0], segments: 32 }));
  const tierA = new THREE.MeshPhysicalMaterial({ name: 'Candle_A', color: 0xfff1d0, emissive: 0xfff1d0, emissiveIntensity: 1.2, roughness: 0.15, transparent: true, opacity: 0.92, clearcoat: 1 });
  const tierB = new THREE.MeshPhysicalMaterial({ name: 'Candle_B', color: 0xff3d6a, emissive: 0xff2a55, emissiveIntensity: 1.2, roughness: 0.15, transparent: true, opacity: 0.92, clearcoat: 1 });
  candle.add(kit.cylinder(0.03, 0.03, 0.06, tierB, { name: 'CandleB', pos: [0, 0.18, 0], segments: 32 }));
  candle.add(kit.cylinder(0.034, 0.034, 0.006, chrome, { name: 'CandleRing2', pos: [0, 0.213, 0], segments: 32 }));
  candle.add(kit.cylinder(0.03, 0.03, 0.06, tierA, { name: 'CandleA', pos: [0, 0.246, 0], segments: 32 }));
  candle.add(kit.sphere(0.03, chrome, { name: 'CandleCap', pos: [0, 0.276, 0], segments: 24 }));
  root.add(candle);

  // ---------- pull lever (right side) ----------
  const mount = kit.group('LeverMount', { pos: [0.346, 0.98, 0.08] });
  mount.add(kit.roundedBox(0.012, 0.16, 0.12, 0.005, chrome, { name: 'LeverPlate', pos: [0.006, 0, 0] }));
  mount.add(kit.cylinder(0.05, 0.05, 0.06, chrome, { name: 'LeverHub', pos: [0.04, 0, 0], rot: [0, 0, 90], segments: 40 }));
  mount.add(kit.cylinder(0.035, 0.035, 0.02, darkMetal, { name: 'LeverCap', pos: [0.075, 0, 0], rot: [0, 0, 90], segments: 32 }));
  const pivot = kit.group('LeverPivot', { pos: [0.055, 0, 0], rot: [-12, 0, 0] });
  pivot.add(kit.cylinder(0.011, 0.013, 0.42, chrome, { name: 'LeverShaft', pos: [0, 0.21, 0], segments: 24 }));
  pivot.add(kit.cylinder(0.018, 0.018, 0.04, chrome, { name: 'LeverCollar', pos: [0, 0.4, 0], segments: 24 }));
  pivot.add(kit.sphere(0.046, red, { name: 'LeverKnob', pos: [0, 0.455, 0] }));
  mount.add(pivot); root.add(mount);

  // ---------- floor light wash (soft purple glow on the real floor) ----------
  const glowTex = kit.canvasTexture(256, 256, (g, w, h) => {
    const r = g.createRadialGradient(w / 2, h / 2, 0, w / 2, h / 2, w / 2); r.addColorStop(0, 'rgba(170,80,255,0.55)'); r.addColorStop(0.5, 'rgba(140,50,255,0.2)'); r.addColorStop(1, 'rgba(120,40,255,0)');
    g.fillStyle = r; g.fillRect(0, 0, w, h);
  });
  const fg = new THREE.Mesh(new THREE.PlaneGeometry(1.5, 1.1), new THREE.MeshBasicMaterial({ name: 'FloorGlow', map: glowTex, transparent: true, depthWrite: false, toneMapped: false }));
  fg.rotation.x = -Math.PI / 2; fg.position.set(0, 0.003, 0.25); fg.name = 'FloorGlow'; root.add(fg);

  const keep = ['Screen', 'ScreenGlass', 'SpinButton', 'BetDownButton', 'BetUpButton', 'MaxBetButton', 'AutoButton', 'LeverPivot', 'LeverKnob', 'FloorGlow', 'TopperSign'];
  if (merge) kit.mergeByMaterial(root, keep);
  root.traverse((o) => { if (o.isMesh) { o.receiveShadow = false; o.castShadow = !['FloorGlow', 'Screen', 'ScreenGlass'].includes(o.name); } });
  // pull animation for viewers (the app drives the lever itself)
  const clip = new THREE.AnimationClip('Pull', 2.4, [new THREE.QuaternionKeyframeTrack('LeverPivot.quaternion', [0, 0.35, 0.6, 1.1, 2.4],
    [-12, 58, 58, -12, -12].flatMap((a) => new THREE.Quaternion().setFromEuler(new THREE.Euler(a * D2R, 0, 0)).toArray()))]);
  return { root, animations: [clip] };   // no re-centering: origin = floor centre of the cabinet, same frame as the FX GLBs

  // ---------- procedural detail maps ----------
  function noiseNormal(s, k) {
    return kit.canvasTexture(s, s, (g, w, h) => {
      const img = g.createImageData(w, h); let seed = 7; const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
      const hgt = new Float32Array(w * h).map(() => rnd());
      for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
        const i = y * w + x, dx = (hgt[y * w + ((x + 1) % w)] - hgt[i]) * k, dy = (hgt[((y + 1) % h) * w + x] - hgt[i]) * k;
        const nz = 1 / Math.hypot(dx, dy, 1); img.data.set([(-dx * nz * 0.5 + 0.5) * 255, (-dy * nz * 0.5 + 0.5) * 255, nz * 255, 255], i * 4);
      }
      g.putImageData(img, 0, 0);
    });
  }
  function brushedNormal(s) {
    const t = kit.canvasTexture(s, s, (g, w, h) => {
      const img = g.createImageData(w, h); let seed = 3; const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
      const row = new Float32Array(h).map(() => rnd());
      for (let y = 0; y < h; y++) { const d = (row[(y + 1) % h] - row[y]) * 1.6, nz = 1 / Math.hypot(d, 1);
        for (let x = 0; x < w; x++) img.data.set([128, (-d * nz * 0.5 + 0.5) * 255, nz * 255, 255], (y * w + x) * 4); }
      g.putImageData(img, 0, 0);
    });
    t.colorSpace = THREE.NoColorSpace; t.wrapS = t.wrapT = THREE.RepeatWrapping; t.repeat.set(2, 2); return t;
  }
  function perforationNormal(s) {
    const t = kit.canvasTexture(s, s, (g, w, h) => {
      g.fillStyle = 'rgb(128,128,255)'; g.fillRect(0, 0, w, h);
      for (let y = 8; y < h; y += 16) for (let x = (y / 16) % 2 ? 16 : 8; x < w; x += 16) {
        const r = g.createRadialGradient(x - 1, y - 1, 0, x, y, 5); r.addColorStop(0, 'rgb(128,128,255)'); r.addColorStop(0.6, 'rgb(90,90,230)'); r.addColorStop(1, 'rgb(170,170,230)');
        g.fillStyle = r; g.beginPath(); g.arc(x, y, 5, 0, 7); g.fill();
      }
    });
    t.colorSpace = THREE.NoColorSpace; t.wrapS = t.wrapT = THREE.RepeatWrapping; t.repeat.set(8, 2); return t;
  }
  function perforationColor(s) {
    const t = kit.canvasTexture(s, s, (g, w, h) => {
      g.fillStyle = '#5a5a62'; g.fillRect(0, 0, w, h);
      for (let y = 8; y < h; y += 16) for (let x = (y / 16) % 2 ? 16 : 8; x < w; x += 16) { g.fillStyle = '#050507'; g.beginPath(); g.arc(x, y, 4, 0, 7); g.fill(); }
    });
    t.wrapS = t.wrapT = THREE.RepeatWrapping; t.repeat.set(8, 2); return t;
  }
}
