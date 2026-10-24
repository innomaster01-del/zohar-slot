// ZOHAR blackjack table, real size, in the shared table frame (see shared/layout.js).
// Parts: felt (printed), padded leather rail, wood trims, apron + pedestal, sunk chip rack with chips,
// card shoe with cards, discard holder, limit sign, soft floor shadow. Named parts for the app.
import { L } from './shared/layout.js';

export async function build(THREE, kit) {
  const g = kit.group('BlackjackTable');
  const R = L.radius, RF = L.radius - L.railW;            // felt radius
  const deg = THREE.MathUtils.degToRad;

  // ---------------- felt ----------------
  const FW = 2048, FH = 1100, X0 = -0.95, Z0 = -0.07, SPAN = 1.9, ZSPAN = 1.02;
  const px = (x) => (x - X0) / SPAN * FW, py = (z) => (z - Z0) / ZSPAN * FH, S = FW / SPAN;
  const feltTex = kit.canvasTexture(FW, FH, (c, w, h) => {
    // base cloth: deep casino green with fibre noise and a soft vignette from the lamp
    c.fillStyle = '#06402a'; c.fillRect(0, 0, w, h);
    const rg = c.createRadialGradient(px(0), py(0.45), 40, px(0), py(0.45), 1100); rg.addColorStop(0, 'rgba(30,110,70,0.35)'); rg.addColorStop(1, 'rgba(0,15,8,0.45)');
    c.fillStyle = rg; c.fillRect(0, 0, w, h);
    let seed = 7; const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
    for (let i = 0; i < 90000; i++) { const x = rnd() * w, y = rnd() * h, a = rnd() * 0.06; c.fillStyle = rnd() > 0.5 ? `rgba(255,255,255,${a})` : `rgba(0,0,0,${a * 1.4})`; c.fillRect(x, y, 1 + rnd() * 2, 1); }
    const gold = '#e2bf62', ink = 'rgba(240,226,180,0.92)';
    const arcText = (text, rM, size, color, font = 'Georgia, serif', weight = '700', spacing = 1.0) => {
      c.save(); c.fillStyle = color; c.font = `${weight} ${size}px ${font}`; c.textAlign = 'center'; c.textBaseline = 'middle';
      const r = rM * S; const widths = [...text].map((ch) => c.measureText(ch).width * spacing); const total = widths.reduce((a, b) => a + b, 0);
      let ang = -total / r / 2;
      for (let i = 0; i < widths.length; i++) {
        const a = ang + widths[i] / r / 2;
        c.save(); c.translate(px(0) + Math.sin(a) * r, py(0) + Math.cos(a) * r); c.rotate(-a); c.fillText(text[i], 0, 0); c.restore();
        ang += widths[i] / r;
      }
      c.restore();
    };
    const arcLine = (rM, a0, a1, color, width) => { c.save(); c.strokeStyle = color; c.lineWidth = width; c.beginPath(); c.arc(px(0), py(0), rM * S, Math.PI / 2 - a1, Math.PI / 2 - a0); c.stroke(); c.restore(); };
    // insurance band
    arcLine(0.295, -1.0, 1.0, gold, 3); arcLine(0.365, -1.0, 1.0, gold, 3);
    arcText('INSURANCE PAYS 2 TO 1', 0.33, 30, gold, 'Georgia, serif', '700', 1.12);
    // the big lines
    arcText('BLACKJACK PAYS 3 TO 2', 0.455, 50, gold, 'Georgia, serif', '700', 1.08);
    arcText('Dealer must draw to 16 and stand on all 17s', 0.405, 24, ink, 'Georgia, serif', 'italic 600', 1.04);
    // five betting spots + card boxes; the middle one is the player's
    const spots = [-0.84, -0.42, 0, 0.42, 0.84];
    for (const a of spots) {
      const r = L.playerBet[2] + 0.005, cx = px(0) + Math.sin(a) * r * S, cy = py(0) + Math.cos(a) * r * S;
      const main = a === 0;
      c.save(); c.translate(cx, cy);
      c.strokeStyle = main ? gold : 'rgba(226,191,98,0.55)'; c.lineWidth = main ? 6 : 4;
      c.beginPath(); c.arc(0, 0, 0.055 * S, 0, Math.PI * 2); c.stroke();
      c.lineWidth = 2; c.beginPath(); c.arc(0, 0, 0.047 * S, 0, Math.PI * 2); c.stroke();
      if (main) { c.fillStyle = 'rgba(226,191,98,0.10)'; c.beginPath(); c.arc(0, 0, 0.047 * S, 0, Math.PI * 2); c.fill(); }
      c.restore();
      // card box (where the player's cards go), rounded rectangle, rotated along the arc
      const rc = L.playerCards[2] + 0.002, bx = px(0) + Math.sin(a) * rc * S, by = py(0) + Math.cos(a) * rc * S;
      c.save(); c.translate(bx, by); c.rotate(-a); c.strokeStyle = main ? 'rgba(240,226,180,0.55)' : 'rgba(240,226,180,0.3)'; c.lineWidth = 2;
      const bw = 0.16 * S, bh = 0.115 * S; c.beginPath(); c.roundRect(-bw / 2, -bh / 2, bw, bh, 14); c.stroke(); c.restore();
    }
    // brand in the dealer's area
    c.save(); c.translate(px(0), py(0.115)); c.fillStyle = 'rgba(226,191,98,0.16)'; c.font = '900 64px Georgia, serif'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText('ZOHAR', 0, 0); c.restore();
  });
  feltTex.anisotropy = 8;
  const felt = new THREE.MeshStandardMaterial({ name: 'Felt', map: feltTex, roughness: 0.97, metalness: 0 });
  {
    const sh = new THREE.Shape(); sh.moveTo(-RF, 0); sh.absarc(0, 0, RF, Math.PI, 0, true); sh.lineTo(RF, -0.06); sh.lineTo(-RF, -0.06); sh.closePath();
    const geo = new THREE.ShapeGeometry(sh, 96);
    // shape is in XY with y = table z (toward the player)… map to XZ, top facing +Y
    const pos = geo.attributes.position; const uv = new Float32Array(pos.count * 2);
    for (let i = 0; i < pos.count; i++) { const x = pos.getX(i), y = pos.getY(i); uv[2 * i] = (x - X0) / SPAN; uv[2 * i + 1] = 1 - (y - Z0) / ZSPAN; }
    geo.setAttribute('uv', new THREE.BufferAttribute(uv, 2));
    geo.rotateX(Math.PI / 2);       // (x, y, 0) → (x, 0, y)
    geo.computeVertexNormals();
    // rotateX(+90°) maps y→z but flips the normal down: fix winding
    const idx = geo.index.array; for (let i = 0; i < idx.length; i += 3) { const t = idx[i + 1]; idx[i + 1] = idx[i + 2]; idx[i + 2] = t; }
    geo.computeVertexNormals();
    const m = new THREE.Mesh(geo, felt); m.name = 'Felt'; m.position.y = L.feltY; m.receiveShadow = true; g.add(m);
  }

  // ---------------- leather rail + wood ----------------
  const leatherTex = kit.canvasTexture(512, 512, (c, w, h) => {
    c.fillStyle = '#2a1712'; c.fillRect(0, 0, w, h);
    let seed = 3; const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
    for (let i = 0; i < 26000; i++) { const x = rnd() * w, y = rnd() * h, r = 0.6 + rnd() * 1.6; c.fillStyle = rnd() > 0.5 ? 'rgba(255,230,200,0.035)' : 'rgba(0,0,0,0.12)'; c.beginPath(); c.arc(x, y, r, 0, 7); c.fill(); }
  });
  leatherTex.wrapS = leatherTex.wrapT = THREE.RepeatWrapping; leatherTex.repeat.set(10, 1);
  const leather = new THREE.MeshPhysicalMaterial({ name: 'Leather', map: leatherTex, roughness: 0.42, clearcoat: 0.35, clearcoatRoughness: 0.45, sheen: 0.3, sheenColor: new THREE.Color(0x553322) });
  const woodTex = kit.canvasTexture(1024, 256, (c, w, h) => {
    const lg = c.createLinearGradient(0, 0, 0, h); lg.addColorStop(0, '#5a2e14'); lg.addColorStop(0.5, '#7a4220'); lg.addColorStop(1, '#4e260f'); c.fillStyle = lg; c.fillRect(0, 0, w, h);
    for (let i = 0; i < 120; i++) { const y = (i * 37.7) % h; c.strokeStyle = `rgba(30,12,4,${0.08 + (i % 7) / 50})`; c.lineWidth = 1 + (i % 3); c.beginPath(); c.moveTo(0, y); for (let x = 0; x <= w; x += 24) c.lineTo(x, y + Math.sin(x / 90 + i * 1.7) * 5); c.stroke(); }
  });
  woodTex.wrapS = woodTex.wrapT = THREE.RepeatWrapping; woodTex.repeat.set(6, 1);
  const wood = new THREE.MeshPhysicalMaterial({ name: 'Wood', map: woodTex, roughness: 0.32, clearcoat: 1, clearcoatRoughness: 0.12 });
  const lathe = (profile, material, name, seg = 128) => {
    const geo = new THREE.LatheGeometry(profile.map(([r, y]) => new THREE.Vector2(r, y)), seg, -Math.PI / 2, Math.PI);
    const m = new THREE.Mesh(geo, material); m.name = name; m.castShadow = true; m.receiveShadow = true; g.add(m); return m;
  };
  // padded cushion: rounded profile from the felt edge to the outer edge
  const yb = L.feltY - 0.012, yt = L.railY;
  const cushion = [];
  for (let i = 0; i <= 16; i++) { const a = Math.PI * i / 16; cushion.push([RF + 0.012 + (L.railW - 0.024) * (1 - Math.cos(a)) / 2, yb + 0.006 + (yt - yb) * Math.sin(a) * 0.98 + (yt - yb) * 0.02 * (1 - i / 16)]); }
  cushion.unshift([RF + 0.012, yb]); cushion.push([R - 0.012, yb]);
  lathe(cushion.reverse(), leather, 'Rail');
  // polished wood: inner trim lip between felt and cushion, outer band under the cushion
  lathe([[RF - 0.001, L.feltY - 0.004], [RF + 0.002, L.feltY + 0.006], [RF + 0.014, L.feltY + 0.008], [RF + 0.016, L.feltY - 0.012]].reverse(), wood, 'WoodInner', 128);
  lathe([[R - 0.014, yb], [R + 0.004, yb - 0.004], [R + 0.008, yb - 0.03], [R + 0.004, yb - 0.075], [R - 0.02, yb - 0.08]], wood, 'WoodOuter', 128);
  // dark apron under it all
  const apronMat = kit.mat.plastic('#141012', 0.6);
  lathe([[R - 0.03, yb - 0.08], [R - 0.06, 0.62], [0.3, 0.6]], apronMat, 'Apron', 96);
  // dealer side: straight wooden edge with a brass strip
  const edge = kit.roundedBox(2 * R + 0.012, 0.05, 0.07, 0.008, wood, { name: 'DealerEdge', pos: [0, L.feltY - 0.024, -0.095] }); g.add(edge);
  g.add(kit.box(2 * R - 0.02, 0.17, 0.05, apronMat, { name: 'DealerApron', pos: [0, L.feltY - 0.13, -0.085] }));
  g.add(kit.box(2 * RF, 0.006, 0.004, kit.mat.metal('#c9a03c', 0.25), { name: 'BrassStrip', pos: [0, L.feltY + 0.0005, -0.0605] }));
  // rail end caps
  for (const sx of [-1, 1]) {
    const cap = new THREE.Mesh(new THREE.SphereGeometry(1, 24, 12, 0, Math.PI), leather); cap.scale.set(L.railW / 2 - 0.012, (yt - yb) * 0.95, 0.02); cap.rotation.y = Math.PI; cap.position.set(sx * (RF + L.railW / 2), yb + 0.002, 0); cap.name = 'RailCap'; g.add(cap);
  }
  // pedestals
  const black = kit.mat.metal('#1b1b1e', 0.45);
  for (const x of [-0.45, 0.45]) {
    g.add(kit.cylinder(0.06, 0.07, 0.6, black, { name: 'Leg', pos: [x, 0.3, 0.32], segments: 32 }));
    g.add(kit.cylinder(0.22, 0.24, 0.025, black, { name: 'Foot', pos: [x, 0.0125, 0.32], segments: 48 }));
  }

  // ---------------- chip rack (sunk, chips lying in tubes) ----------------
  const rackW = 0.66, rackD = 0.105, rackDepth = 0.035;
  const acrylic = kit.mat.plastic('#0d0d10', 0.25);
  g.add(kit.box(rackW, 0.006, rackD, acrylic, { name: 'RackBottom', pos: [L.rack[0], L.feltY - rackDepth, L.rack[2]] }));
  for (const sx of [-1, 1]) g.add(kit.box(0.006, rackDepth, rackD, acrylic, { name: 'RackSide', pos: [sx * rackW / 2, L.feltY - rackDepth / 2, L.rack[2]] }));
  for (const sz of [-1, 1]) g.add(kit.box(rackW, rackDepth, 0.006, acrylic, { name: 'RackSide', pos: [0, L.feltY - rackDepth / 2, L.rack[2] + sz * rackD / 2] }));
  const DENOMS = [['#f5f5f2', '#1e5bd7'], ['#c8262e', '#f5f5f2'], ['#1e8a3e', '#f5f5f2'], ['#141414', '#f5f5f2'], ['#5b2a86', '#f2d16b'], ['#f08a1c', '#3a2200']];
  const edgeTex = (base, stripe) => { const t = kit.canvasTexture(256, 16, (c, w, h) => { c.fillStyle = base; c.fillRect(0, 0, w, h); c.fillStyle = stripe; for (let i = 0; i < 6; i++) c.fillRect(i * w / 6 + 6, 0, w / 12, h); }); t.wrapS = THREE.RepeatWrapping; return t; };
  const tubes = 10, tubeW = (rackW - 0.02) / tubes;
  for (let i = 0; i < tubes; i++) {
    const [base, stripe] = DENOMS[Math.min(DENOMS.length - 1, Math.floor(i / 2))];
    const n = 18 + ((i * 7) % 9);           // chips per tube
    const geo = new THREE.CylinderGeometry(L.chipD / 2, L.chipD / 2, n * L.chipH, 32, 1, false);
    const m = new THREE.MeshStandardMaterial({ name: `RackChips${i}`, map: edgeTex(base, stripe), roughness: 0.5 });
    m.map.repeat.set(1, n);
    const stack = new THREE.Mesh(geo, m); stack.rotation.x = Math.PI / 2; stack.position.set(-rackW / 2 + 0.01 + tubeW * (i + 0.5), L.feltY - L.chipD / 2 + 0.009, L.rack[2]);
    stack.name = 'RackChips'; stack.castShadow = true; g.add(stack);
  }

  // ---------------- shoe ----------------
  // classic dealing shoe: closed black box, the front is a slanted ramp with a finger cut-out; the next card shows its back there
  const shoe = kit.group('Shoe', { pos: [L.shoe[0] + 0.13, L.feltY, L.shoe[2]], rot: [0, -12, 0] });
  const shoeMat = new THREE.MeshPhysicalMaterial({ name: 'ShoeBody', color: 0x0e0b0d, roughness: 0.18, clearcoat: 1, clearcoatRoughness: 0.08 });
  const shoeL = 0.25, shoeW = 0.12, shoeH = 0.10;
  const side = [[-shoeL / 2, 0], [shoeL / 2, 0], [shoeL / 2, shoeH], [-shoeL / 2 + 0.07, shoeH], [-shoeL / 2, 0.028]];
  for (const sz of [-1, 1]) { const m = kit.extrude(side, 0.01, shoeMat, { name: 'ShoeSide', bevel: 0.002 }); m.position.set(0, 0, sz * (shoeW / 2 - 0.005)); shoe.add(m); }
  shoe.add(kit.roundedBox(shoeL, 0.01, shoeW, 0.003, shoeMat, { name: 'ShoeBase', pos: [0, 0.005, 0] }));
  shoe.add(kit.roundedBox(0.012, shoeH, shoeW, 0.003, shoeMat, { name: 'ShoeBack', pos: [shoeL / 2 - 0.006, shoeH / 2, 0] }));
  shoe.add(kit.roundedBox(shoeL - 0.07, 0.008, shoeW, 0.003, shoeMat, { name: 'ShoeTop', pos: [0.035, shoeH - 0.004, 0] }));
  // slanted front face (ramp) between the low front and the top
  const rampLen = Math.hypot(0.07, shoeH - 0.028), rampAng = Math.atan2(shoeH - 0.028, 0.07);
  const ramp = kit.roundedBox(rampLen, 0.006, shoeW, 0.002, shoeMat, { name: 'ShoeRamp' }); ramp.rotation.z = rampAng; ramp.position.set(-shoeL / 2 + 0.035, 0.028 + (shoeH - 0.028) / 2, 0); shoe.add(ramp);
  shoe.add(kit.box(0.004, 0.003, shoeW - 0.004, kit.mat.metal('#c9a03c', 0.25), { name: 'ShoeTrim', pos: [-shoeL / 2 + 0.002, 0.029, 0] }));
  shoe.add(kit.box(shoeL - 0.07, 0.003, 0.004, kit.mat.metal('#c9a03c', 0.25), { name: 'ShoeTrim', pos: [0.035, shoeH, shoeW / 2 - 0.004] }));
  // the next card, back up, peeking out of the mouth at the bottom of the ramp
  const nb = kit.box(0.05, 0.0015, L.cardW * 0.96, new THREE.MeshStandardMaterial({ name: 'ShoeNextCard', color: 0x8c1424, roughness: 0.5 }), { name: 'ShoeNextCard', pos: [-shoeL / 2 - 0.004, 0.012, 0] }); nb.rotation.z = -0.08; shoe.add(nb);
  shoe.add(kit.roundedBox(0.03, 0.012, 0.06, 0.004, kit.mat.metal('#c9a03c', 0.3), { name: 'ShoeFinger', pos: [-shoeL / 2 + 0.035, 0.064, 0] }));
  g.add(shoe);

  const deckEdge = kit.canvasTexture(256, 64, (c, w, h) => { c.fillStyle = '#efece4'; c.fillRect(0, 0, w, h); for (let i = 0; i < w; i += 2) { c.fillStyle = `rgba(0,0,0,${0.05 + (i % 6) / 80})`; c.fillRect(i, 0, 1, h); } });
  const deckMat = new THREE.MeshStandardMaterial({ name: 'DeckEdges', map: deckEdge, roughness: 0.8 });
  // ---------------- discard holder ----------------
  const clear = new THREE.MeshPhysicalMaterial({ name: 'ClearAcrylic', color: 0xffffff, roughness: 0.05, transmission: 0, transparent: true, opacity: 0.28 });
  const dh = kit.group('Discard', { pos: [L.discard[0] - 0.02, L.feltY, L.discard[2]] });
  dh.add(kit.box(0.10, 0.006, 0.075, clear, { name: 'DiscardBase', pos: [0, 0.003, 0] }));
  dh.add(kit.box(0.006, 0.09, 0.075, clear, { name: 'DiscardBack', pos: [-0.047, 0.045, 0] }));
  for (const sz of [-1, 1]) dh.add(kit.box(0.10, 0.05, 0.004, clear, { name: 'DiscardSide', pos: [0, 0.025, sz * 0.036] }));
  dh.add(kit.box(0.09, 0.02, L.cardW, deckMat, { name: 'DiscardCards', pos: [0.0, 0.016, 0] }));
  g.add(dh);

  // ---------------- limit sign ----------------
  const signTex = kit.canvasTexture(512, 256, (c, w, h) => {
    const lg = c.createLinearGradient(0, 0, 0, h); lg.addColorStop(0, '#16161c'); lg.addColorStop(1, '#050507'); c.fillStyle = lg; c.fillRect(0, 0, w, h);
    c.strokeStyle = '#c9a03c'; c.lineWidth = 6; c.strokeRect(8, 8, w - 16, h - 16);
    c.fillStyle = '#ffd36b'; c.font = '800 58px Arial'; c.textAlign = 'center'; c.fillText('BLACKJACK', w / 2, 80);
    c.fillStyle = '#ffffff'; c.font = '700 40px Arial'; c.fillText('MIN 5  ·  MAX 500', w / 2, 145);
    c.fillStyle = '#7fe0a8'; c.font = '600 30px Arial'; c.fillText('PLAY MONEY', w / 2, 205);
  });
  const sign = kit.group('LimitSign', { pos: [-0.66, L.feltY, 0.02], rot: [0, 25, 0] });
  sign.add(kit.box(0.16, 0.08, 0.008, new THREE.MeshStandardMaterial({ name: 'SignFace', map: signTex, emissive: 0xffffff, emissiveMap: signTex, emissiveIntensity: 0.6, roughness: 0.3 }), { name: 'SignFace', pos: [0, 0.07, 0] }));
  sign.add(kit.box(0.17, 0.09, 0.006, black, { name: 'SignFrame', pos: [0, 0.07, -0.006] }));
  sign.add(kit.cylinder(0.006, 0.006, 0.03, black, { name: 'SignPost', pos: [0, 0.015, -0.004], segments: 12 }));
  g.add(sign);

  // ---------------- soft floor shadow ----------------
  const shadowTex = kit.canvasTexture(256, 256, (c, w, h) => { const rg = c.createRadialGradient(w / 2, h / 2, 10, w / 2, h / 2, w / 2); rg.addColorStop(0, 'rgba(0,0,0,0.55)'); rg.addColorStop(0.6, 'rgba(0,0,0,0.3)'); rg.addColorStop(1, 'rgba(0,0,0,0)'); c.fillStyle = rg; c.fillRect(0, 0, w, h); });
  const shadow = new THREE.Mesh(new THREE.PlaneGeometry(2.4, 1.5), new THREE.MeshBasicMaterial({ name: 'FloorShadow', map: shadowTex, transparent: true, depthWrite: false }));
  shadow.rotation.x = -Math.PI / 2; shadow.position.set(0, 0.002, 0.38); shadow.name = 'FloorShadow'; shadow.castShadow = false; shadow.receiveShadow = false; g.add(shadow);

  g.traverse((o) => { if (o.isMesh && o.name !== 'FloorShadow') { o.castShadow = true; o.receiveShadow = true; } });
  return g;
}
