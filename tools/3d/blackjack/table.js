// ZOHAR blackjack table, real size, in the shared table frame (see shared/layout.js).
// Parts: felt (printed), padded leather rail, wood trims, apron + pedestal, sunk chip rack with chips,
// card shoe with cards, discard holder, limit sign, soft floor shadow. Named parts for the app.
import { L } from './shared/layout.js';
import { CHIPS, chipMesh } from './chips.js';
import { drawBack, CW, CH } from './cards.js';
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js';

export async function build(THREE, kit, game = 'blackjack') {
  const g = kit.group('BlackjackTable');
  const R = L.radius, RF = L.radius - L.railW;            // felt radius
  const deg = THREE.MathUtils.degToRad;

  // ---------------- felt ----------------
  const FW = 2560, FH = 1376, X0 = -0.95, Z0 = -0.07, SPAN = 1.9, ZSPAN = 1.02;
  const px = (x) => (x - X0) / SPAN * FW, py = (z) => (z - Z0) / ZSPAN * FH, S = FW / SPAN;
  const feltTex = kit.canvasTexture(FW, FH, (c, w, h) => {
    // deep emerald cloth, lit from above the dealer's spot
    c.fillStyle = '#064a30'; c.fillRect(0, 0, w, h);
    const rg = c.createRadialGradient(px(0), py(0.42), 60, px(0), py(0.42), 1500); rg.addColorStop(0, 'rgba(40,150,95,0.30)'); rg.addColorStop(0.6, 'rgba(0,0,0,0)'); rg.addColorStop(1, 'rgba(0,10,5,0.55)');
    c.fillStyle = rg; c.fillRect(0, 0, w, h);
    let seed = 7; const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
    for (let i = 0; i < 160000; i++) { const x = rnd() * w, y = rnd() * h, a = rnd() * 0.05; c.fillStyle = rnd() > 0.5 ? `rgba(255,255,255,${a})` : `rgba(0,0,0,${a * 1.5})`; c.fillRect(x, y, 1 + rnd() * 2.5, 1); }
    const gold = '#e9c46a', goldDim = 'rgba(233,196,106,0.55)', ink = 'rgba(245,233,200,0.92)';
    const gGrad = (y0, y1) => { const lg = c.createLinearGradient(0, y0, 0, y1); lg.addColorStop(0, '#fff1b8'); lg.addColorStop(0.5, '#e9c46a'); lg.addColorStop(1, '#b8892c'); return lg; };
    const arcText = (text, rM, size, color, font = 'Georgia, serif', weight = '700', spacing = 1.0) => {
      c.save(); c.fillStyle = color; c.font = `${weight} ${size}px ${font}`; c.textAlign = 'center'; c.textBaseline = 'middle';
      c.shadowColor = 'rgba(0,0,0,0.45)'; c.shadowBlur = 3; c.shadowOffsetY = 1.5;
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
    // outer framing lines along the rail
    arcLine(RF - 0.025, -1.45, 1.45, goldDim, 3); arcLine(RF - 0.034, -1.45, 1.45, 'rgba(233,196,106,0.25)', 1.5);
    if (game === 'blackjack') {
    // insurance ribbon
    c.save(); c.beginPath(); c.arc(px(0), py(0), 0.365 * S, Math.PI / 2 - 1.0, Math.PI / 2 + 1.0); c.arc(px(0), py(0), 0.295 * S, Math.PI / 2 + 1.0, Math.PI / 2 - 1.0, true); c.closePath(); c.fillStyle = 'rgba(0,0,0,0.16)'; c.fill(); c.restore();
    arcLine(0.295, -1.0, 1.0, gold, 3); arcLine(0.365, -1.0, 1.0, gold, 3);
    arcText('INSURANCE PAYS 2 TO 1', 0.33, 34, gGrad(-20, 20), 'Georgia, serif', '700', 1.16);
    // the big lines
    arcText('BLACKJACK PAYS 3 TO 2', 0.458, 60, gGrad(-30, 30), 'Georgia, serif', '700', 1.1);
    arcText('DEALER MUST DRAW TO 16 AND STAND ON ALL 17s', 0.405, 22, ink, 'Georgia, serif', '600', 1.18);
    // five seats: main betting circle + Perfect Pairs (left) and 21+3 (right) side circles + card box; the middle seat is the player's
    const spots = [-1.0, -0.5, 0, 0.5, 1.0];
    spots.forEach((a, si) => {
      const main = a === 0; const alpha = main ? 1 : 0.5;
      const at = (rr, da = 0) => [px(0) + Math.sin(a + da) * rr * S, py(0) + Math.cos(a + da) * rr * S];
      // main circle
      const [cx, cy] = at(L.playerBet[2]);
      c.save(); c.translate(cx, cy); c.globalAlpha = alpha;
      if (main) { const gl = c.createRadialGradient(0, 0, 0, 0, 0, 0.06 * S); gl.addColorStop(0, 'rgba(233,196,106,0.20)'); gl.addColorStop(1, 'rgba(233,196,106,0)'); c.fillStyle = gl; c.beginPath(); c.arc(0, 0, 0.06 * S, 0, 7); c.fill(); }
      c.strokeStyle = gold; c.lineWidth = 7; c.beginPath(); c.arc(0, 0, 0.052 * S, 0, 7); c.stroke();
      c.lineWidth = 2; c.beginPath(); c.arc(0, 0, 0.044 * S, 0, 7); c.stroke();
      c.rotate(-a); c.fillStyle = 'rgba(233,196,106,0.35)'; c.font = '700 44px Georgia, serif'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText(String(si + 1), 0, 2);
      c.restore();
      // side bet circles
      const side = (da, label, sub) => {
        const rr = Math.hypot(L.pairsBet[0], L.pairsBet[2]); const [sx, sy] = at(rr, da);
        c.save(); c.translate(sx, sy); c.globalAlpha = alpha; c.rotate(-(a + da));
        c.strokeStyle = gold; c.lineWidth = 4; c.beginPath(); c.arc(0, 0, 0.03 * S, 0, 7); c.stroke();
        c.fillStyle = 'rgba(0,0,0,0.18)'; c.beginPath(); c.arc(0, 0, 0.028 * S, 0, 7); c.fill();
        c.fillStyle = gold; c.font = '800 23px Arial'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText(label, 0, -5);
        c.font = '600 11px Arial'; c.fillText(sub, 0, 14);
        c.restore();
      };
      const dAng = Math.atan2(Math.abs(L.pairsBet[0]), L.pairsBet[2]);
      side(-dAng, 'PP', 'PERFECT PAIRS'); side(dAng, '21+3', 'SIDE BET');
      // card box
      const [bx, by] = at(L.playerCards[2] + 0.002);
      c.save(); c.translate(bx, by); c.rotate(-a); c.globalAlpha = alpha; c.strokeStyle = 'rgba(245,233,200,0.45)'; c.lineWidth = 2;
      const bw = 0.16 * S, bh = 0.115 * S; c.setLineDash([10, 8]); c.beginPath(); c.roundRect(-bw / 2, -bh / 2, bw, bh, 16); c.stroke(); c.restore();
    });
    } else if (game === 'baccarat') {
    const B = L.bacc;
    const label = (x, z, text, size, color, weight = '800', font = 'Arial') => { c.save(); c.translate(px(x), py(z)); c.fillStyle = color; c.font = `${weight} ${size}px ${font}`; c.textAlign = 'center'; c.textBaseline = 'middle'; c.shadowColor = 'rgba(0,0,0,0.45)'; c.shadowBlur = 3; c.fillText(text, 0, 0); c.restore(); };
    arcText('BACCARAT', 0.452, 58, gGrad(-30, 30), 'Georgia, serif', '700', 1.2);
    // the two hands: card boxes with the name above (third card lies sideways on the outside)
    for (const [h, name, col] of [[B.playerHand, 'PLAYER', '#7fb2ff'], [B.bankerHand, 'BANKER', '#ff8a8a']]) {
      const sgn = Math.sign(h[0]);
      c.save(); c.translate(px(h[0]), py(h[2])); c.strokeStyle = 'rgba(245,233,200,0.55)'; c.lineWidth = 2; c.setLineDash([10, 8]);
      c.beginPath(); c.roundRect(-0.075 * S, -0.055 * S, 0.15 * S, 0.11 * S, 14); c.stroke();
      c.translate(sgn * B.thirdDx * S, 0); c.beginPath(); c.roundRect(-0.053 * S, -0.04 * S, 0.106 * S, 0.08 * S, 12); c.stroke(); c.restore();
      label(h[0], h[2] - 0.072, name, 30, col, '900', 'Georgia, serif');
    }
    // bet areas: Player (blue) and Banker (red) circles, Tie oval, pair circles
    const ring = (x, z, r, color, fill, title, sub, size = 30) => {
      c.save(); c.translate(px(x), py(z));
      const gl = c.createRadialGradient(0, 0, 0, 0, 0, r * S); gl.addColorStop(0, fill); gl.addColorStop(1, 'rgba(0,0,0,0)'); c.fillStyle = gl; c.beginPath(); c.arc(0, 0, r * S, 0, 7); c.fill();
      c.strokeStyle = color; c.lineWidth = 7; c.beginPath(); c.arc(0, 0, r * S, 0, 7); c.stroke(); c.lineWidth = 2; c.beginPath(); c.arc(0, 0, (r - 0.008) * S, 0, 7); c.stroke();
      c.fillStyle = color; c.font = `900 ${size}px Georgia, serif`; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText(title, 0, -8);
      c.font = '700 14px Arial'; c.fillText(sub, 0, size * 0.62); c.restore();
    };
    ring(B.player[0], B.player[2], 0.06, '#8fbcff', 'rgba(60,110,220,0.30)', 'PLAYER', '1 TO 1', 28);
    ring(B.banker[0], B.banker[2], 0.06, '#ff9a9a', 'rgba(220,50,50,0.30)', 'BANKER', '0.95 TO 1', 28);
    c.save(); c.translate(px(B.tie[0]), py(B.tie[2])); c.strokeStyle = gold; c.lineWidth = 5; c.fillStyle = 'rgba(40,160,90,0.25)';
    c.beginPath(); c.ellipse(0, 0, 0.07 * S, 0.033 * S, 0, 0, 7); c.fill(); c.stroke();
    c.fillStyle = gold; c.font = '900 30px Georgia, serif'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText('TIE', 0, -7); c.font = '700 14px Arial'; c.fillText('8 TO 1', 0, 17); c.restore();
    ring(B.ppair[0], B.ppair[2], 0.034, '#8fbcff', 'rgba(60,110,220,0.18)', 'P PAIR', '11 TO 1', 17);
    ring(B.bpair[0], B.bpair[2], 0.034, '#ff9a9a', 'rgba(220,50,50,0.18)', 'B PAIR', '11 TO 1', 17);
    // drawing rules, printed like on a real layout
    const rules = (x, z, title, rows) => {
      c.save(); c.translate(px(x), py(z)); c.globalAlpha = 0.9;
      const w = 0.21 * S, h = (0.04 + rows.length * 0.02) * S;
      c.fillStyle = 'rgba(0,0,0,0.18)'; c.strokeStyle = goldDim; c.lineWidth = 2; c.beginPath(); c.roundRect(-w / 2, -h / 2, w, h, 12); c.fill(); c.stroke();
      c.fillStyle = gold; c.font = '800 21px Georgia, serif'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText(title, 0, -h / 2 + 0.02 * S);
      rows.forEach(([n, p], i) => { const y = -h / 2 + (0.045 + i * 0.02) * S; c.font = '600 16px Arial'; c.fillStyle = ink; c.textAlign = 'left'; c.fillText(n, -w / 2 + 14, y); c.textAlign = 'right'; c.fillStyle = gold; c.fillText(p, w / 2 - 14, y); });
      c.restore();
    };
    rules(-0.52, 0.30, 'PLAYER', [['0 – 5', 'draws'], ['6 – 7', 'stands'], ['8 – 9', 'natural']]);
    rules(0.52, 0.32, 'BANKER DRAWS', [['0 – 2', 'always'], ['3', 'unless P 8'], ['4', 'P 2 – 7'], ['5', 'P 4 – 7'], ['6', 'P 6 – 7'], ['P stood', 'on 0 – 5']]);
    } else {
    const H = L.hold;
    const cardBox = (x, z, w, h, alpha, dash = true) => { c.save(); c.translate(px(x), py(z)); c.globalAlpha = alpha; c.strokeStyle = 'rgba(245,233,200,0.55)'; c.lineWidth = 2; if (dash) c.setLineDash([10, 8]); c.beginPath(); c.roundRect(-w * S / 2, -h * S / 2, w * S, h * S, 14); c.stroke(); c.restore(); };
    const label = (x, z, text, size, color, rot = 0, weight = '800', font = 'Arial') => { c.save(); c.translate(px(x), py(z)); c.rotate(rot); c.fillStyle = color; c.font = `${weight} ${size}px ${font}`; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText(text, 0, 0); c.restore(); };
    // dealer's cards, the five community cards
    cardBox(H.dealerCards[0], H.dealerCards[2], 0.16, 0.105, 0.8);
    label(H.dealerCards[0], H.dealerCards[2] + 0.068, 'DEALER QUALIFIES WITH A PAIR OF 4s OR BETTER', 19, ink, 0, '600', 'Georgia, serif');
    for (let i = -2; i <= 2; i++) cardBox(H.board[0] + i * H.boardDx, H.board[2], 0.068, 0.094, 0.9, false);
    // the title
    arcText("CASINO HOLD'EM", 0.447, 50, gGrad(-30, 30), 'Georgia, serif', '700', 1.12);
    // seats: the player's in the middle, two dimmer ones
    for (const a of [0]) {
      const main = a === 0; const alpha = main ? 1 : 0.45;
      const rot = (rr, dx = 0) => { const x = Math.sin(a) * rr + Math.cos(a) * dx, z = Math.cos(a) * rr - Math.sin(a) * dx; return [x, z]; };
      // ANTE circle
      const [ax, az] = rot(H.ante[2]);
      c.save(); c.translate(px(ax), py(az)); c.rotate(-a); c.globalAlpha = alpha;
      if (main) { const gl = c.createRadialGradient(0, 0, 0, 0, 0, 0.06 * S); gl.addColorStop(0, 'rgba(233,196,106,0.2)'); gl.addColorStop(1, 'rgba(233,196,106,0)'); c.fillStyle = gl; c.beginPath(); c.arc(0, 0, 0.06 * S, 0, 7); c.fill(); }
      c.strokeStyle = gold; c.lineWidth = 7; c.beginPath(); c.arc(0, 0, 0.05 * S, 0, 7); c.stroke(); c.lineWidth = 2; c.beginPath(); c.arc(0, 0, 0.042 * S, 0, 7); c.stroke();
      c.fillStyle = 'rgba(233,196,106,0.55)'; c.font = '800 30px Arial'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText('ANTE', 0, 0);
      c.restore();
      // CALL box (2 × ante)
      const [cx2, cz2] = rot(H.call[2]);
      c.save(); c.translate(px(cx2), py(cz2)); c.rotate(-a); c.globalAlpha = alpha; c.strokeStyle = gold; c.lineWidth = 5;
      c.beginPath(); c.roundRect(-0.05 * S, -0.032 * S, 0.1 * S, 0.064 * S, 16); c.stroke();
      c.fillStyle = 'rgba(233,196,106,0.55)'; c.font = '800 26px Arial'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText('CALL', 0, -6); c.font = '600 13px Arial'; c.fillText('2 × ANTE', 0, 18);
      c.restore();
      // AA BONUS circle
      const [bx, bz] = rot(Math.hypot(H.bonus[0], H.bonus[2]) * Math.cos(Math.atan2(H.bonus[0], H.bonus[2])), Math.hypot(H.bonus[0], H.bonus[2]) * Math.sin(Math.atan2(H.bonus[0], H.bonus[2])));
      c.save(); c.translate(px(bx), py(bz)); c.rotate(-a); c.globalAlpha = alpha;
      c.strokeStyle = gold; c.lineWidth = 4; c.beginPath(); c.arc(0, 0, 0.03 * S, 0, 7); c.stroke(); c.fillStyle = 'rgba(0,0,0,0.2)'; c.beginPath(); c.arc(0, 0, 0.028 * S, 0, 7); c.fill();
      c.fillStyle = gold; c.font = '900 24px Georgia, serif'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText('AA', 0, -6); c.font = '700 12px Arial'; c.fillText('BONUS', 0, 15);
      c.restore();
      // player's card box
      const [kx, kz] = rot(H.playerCards[2]);
      c.save(); c.translate(px(kx), py(kz)); c.rotate(-a); c.globalAlpha = alpha; c.strokeStyle = 'rgba(245,233,200,0.45)'; c.lineWidth = 2; c.setLineDash([10, 8]);
      c.beginPath(); c.roundRect(-0.08 * S, -0.055 * S, 0.16 * S, 0.11 * S, 16); c.stroke(); c.restore();
    }
    // the pay tables, printed like on a real layout
    const table = (x, z, title, rows) => {
      c.save(); c.translate(px(x), py(z)); c.globalAlpha = 0.9;
      const w = 0.2 * S, h = (0.04 + rows.length * 0.021) * S;
      c.fillStyle = 'rgba(0,0,0,0.18)'; c.strokeStyle = goldDim; c.lineWidth = 2; c.beginPath(); c.roundRect(-w / 2, -h / 2, w, h, 12); c.fill(); c.stroke();
      c.fillStyle = gold; c.font = '800 22px Georgia, serif'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText(title, 0, -h / 2 + 0.02 * S);
      rows.forEach(([n, p], i) => { const y = -h / 2 + (0.045 + i * 0.021) * S; c.font = '600 17px Arial'; c.fillStyle = ink; c.textAlign = 'left'; c.fillText(n, -w / 2 + 14, y); c.textAlign = 'right'; c.fillStyle = gold; c.fillText(p, w / 2 - 14, y); });
      c.restore();
    };
    table(-0.5, 0.29, 'ANTE PAYS', [['Royal flush', '100 to 1'], ['Straight flush', '20 to 1'], ['Four of a kind', '10 to 1'], ['Full house', '3 to 1'], ['Flush', '2 to 1'], ['Straight or less', '1 to 1']]);
    table(0.5, 0.33, 'AA BONUS PAYS', [['Royal flush', '100 to 1'], ['Straight flush', '50 to 1'], ['Four of a kind', '40 to 1'], ['Full house', '30 to 1'], ['Flush', '20 to 1'], ['Straight · trips', '7 to 1'], ['Two pair · AA', '7 to 1']]);
    }
    // brand in the dealer's area: crown + ZOHAR
    c.save(); c.translate(px(0), py(0.168)); c.scale(0.8, 0.8); c.globalAlpha = 0.45;
    c.fillStyle = gGrad(-60, 30); c.beginPath(); const cw = 46; c.moveTo(-cw, -18); c.lineTo(-cw, -52); c.lineTo(-cw / 2, -34); c.lineTo(0, -62); c.lineTo(cw / 2, -34); c.lineTo(cw, -52); c.lineTo(cw, -18); c.closePath(); c.fill();
    c.font = '900 62px Georgia, serif'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText('ZOHAR', 0, 26); c.restore();
  });
  feltTex.anisotropy = 8;
  const clothN = kit.canvasTexture(256, 256, (c, w, h) => {
    c.fillStyle = 'rgb(128,128,255)'; c.fillRect(0, 0, w, h);
    for (let y = 0; y < h; y += 4) for (let x = 0; x < w; x += 4) { const o = ((x + y) / 4) % 2 ? 12 : -12; c.fillStyle = `rgb(${128 + o},${128 - o},245)`; c.fillRect(x, y, 4, 2); }
  });
  clothN.colorSpace = THREE.NoColorSpace; clothN.wrapS = clothN.wrapT = THREE.RepeatWrapping; clothN.repeat.set(90, 50);
  const felt = new THREE.MeshStandardMaterial({ name: 'Felt', map: feltTex, normalMap: clothN, normalScale: new THREE.Vector2(0.35, 0.35), roughness: 0.96, metalness: 0 });
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
  // warm LED strip along the felt edge (modern casino tables)
  const led = new THREE.MeshStandardMaterial({ name: 'LedStrip', color: 0xffd38a, emissive: 0xffc46b, emissiveIntensity: 2.2, roughness: 0.4 });
  lathe([[RF + 0.0005, L.feltY + 0.0065], [RF + 0.004, L.feltY + 0.0095], [RF + 0.0085, L.feltY + 0.0095], [RF + 0.012, L.feltY + 0.0065]].reverse(), led, 'LedStrip', 160);
  const goldTrim = kit.mat.metal('#d4a94a', 0.22);
  lathe([[R - 0.004, yb - 0.006], [R + 0.006, yb - 0.008], [R + 0.006, yb - 0.014], [R - 0.004, yb - 0.016]], goldTrim, 'GoldTrim', 160);
  // dark apron under it all
  const apronMat = new THREE.MeshPhysicalMaterial({ name: 'Apron', color: 0x0b0a0c, roughness: 0.3, clearcoat: 0.8, clearcoatRoughness: 0.2 });
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

  // ---------------- shoe ----------------
  // dealing shoe: glossy black body with a slanted dealing ramp, smoked side windows showing the cards inside,
  // the next card's back under the finger cut-out, a rubber roller at the mouth, gold trim and badge
  const shoe = kit.group('Shoe', { pos: [L.shoe[0] + 0.135, L.feltY, L.shoe[2]], rot: [0, -12, 0] });
  const shoeMat = new THREE.MeshPhysicalMaterial({ name: 'ShoeBody', color: 0x0b0a0c, roughness: 0.16, clearcoat: 1, clearcoatRoughness: 0.06 });
  const smokeMat = new THREE.MeshPhysicalMaterial({ name: 'ShoeWindow', color: 0x3a3036, roughness: 0.06, metalness: 0, clearcoat: 1, transparent: true, opacity: 0.45 });
  const gold = kit.mat.metal('#d4a94a', 0.2), rubber = kit.mat.rubber('#151515');
  const shoeL = 0.26, shoeW = 0.125, shoeH = 0.105, frontH = 0.03, rampRun = 0.085;
  const sideOutline = [[-shoeL / 2, 0], [shoeL / 2, 0], [shoeL / 2, shoeH], [-shoeL / 2 + rampRun, shoeH], [-shoeL / 2, frontH]];
  // the two side walls: black frame + smoked window inset
  for (const sz of [-1, 1]) {
    const hole = [[-shoeL / 2 + rampRun + 0.012, 0.018], [shoeL / 2 - 0.018, 0.018], [shoeL / 2 - 0.018, shoeH - 0.016], [-shoeL / 2 + rampRun + 0.012, shoeH - 0.016]];
    const wall = kit.extrude(sideOutline, 0.008, shoeMat, { name: 'ShoeSide', bevel: 0.0025, holes: [hole.slice().reverse()] }); wall.position.set(0, 0, sz * (shoeW / 2 - 0.004)); shoe.add(wall);
    const win = kit.extrude(hole, 0.002, smokeMat, { name: 'ShoeWindow', bevel: 0 });
    win.position.set(0, 0, sz * (shoeW / 2 - 0.004)); shoe.add(win);
  }
  shoe.add(kit.roundedBox(shoeL, 0.012, shoeW, 0.004, shoeMat, { name: 'ShoeBase', pos: [0, 0.006, 0] }));
  shoe.add(kit.roundedBox(0.014, shoeH, shoeW, 0.004, shoeMat, { name: 'ShoeBack', pos: [shoeL / 2 - 0.007, shoeH / 2, 0] }));
  shoe.add(kit.roundedBox(shoeL - rampRun, 0.01, shoeW, 0.004, shoeMat, { name: 'ShoeTop', pos: [rampRun / 2, shoeH - 0.005, 0] }));
  // the ramp face (slanted) with a finger cut-out showing the next card
  const rampLen = Math.hypot(rampRun, shoeH - frontH), rampAng = Math.atan2(shoeH - frontH, rampRun);
  const rampGrp = kit.group('ShoeRampGroup'); rampGrp.position.set(-shoeL / 2 + rampRun / 2, frontH + (shoeH - frontH) / 2, 0); rampGrp.rotation.z = rampAng;
  for (const sz of [-1, 1]) rampGrp.add(kit.roundedBox(rampLen, 0.006, 0.03, 0.002, shoeMat, { name: 'ShoeRamp', pos: [0, 0, sz * (shoeW / 2 - 0.015)] }));
  rampGrp.add(kit.roundedBox(rampLen * 0.35, 0.006, shoeW - 0.06, 0.002, shoeMat, { name: 'ShoeRamp', pos: [rampLen * 0.325, 0, 0] }));
  const backTex = kit.canvasTexture(CW, CH, (c) => drawBack(c)); backTex.anisotropy = 8;
  const nextCard = new THREE.Mesh(new THREE.PlaneGeometry(L.cardH, L.cardW), new THREE.MeshStandardMaterial({ name: 'ShoeNextCard', map: backTex, roughness: 0.5 }));
  nextCard.rotation.x = -Math.PI / 2; nextCard.position.set(-rampLen * 0.12, -0.0035, 0); nextCard.name = 'ShoeNextCard'; rampGrp.add(nextCard);
  rampGrp.add(kit.box(rampLen, 0.002, 0.003, gold, { name: 'ShoeTrim', pos: [0, 0.0035, shoeW / 2 - 0.0015] }));
  rampGrp.add(kit.box(rampLen, 0.002, 0.003, gold, { name: 'ShoeTrim', pos: [0, 0.0035, -shoeW / 2 + 0.0015] }));
  shoe.add(rampGrp);
  // the cards inside: a block of card edges seen through the windows, leaning on the ramp
  const deckEdgeTex = kit.canvasTexture(512, 64, (c, w, h) => { c.fillStyle = '#f1ede3'; c.fillRect(0, 0, w, h); for (let i = 0; i < w; i += 3) { c.fillStyle = `rgba(60,40,30,${0.10 + (i % 9) / 40})`; c.fillRect(i, 0, 1, h); } c.fillStyle = 'rgba(140,20,36,0.85)'; c.fillRect(0, 0, 6, h); });
  shoe.add(kit.box(0.14, L.cardH * 0.92, shoeW - 0.02, new THREE.MeshStandardMaterial({ name: 'ShoeDeck', map: deckEdgeTex, roughness: 0.8 }), { name: 'ShoeDeck', pos: [0.035, 0.012 + L.cardH * 0.46, 0] }));
  // rubber roller at the mouth, gold lip, badge
  const roller = kit.cylinder(0.006, 0.006, shoeW - 0.03, rubber, { name: 'ShoeRoller', segments: 20 }); roller.rotation.x = Math.PI / 2; roller.position.set(-shoeL / 2 + 0.006, frontH + 0.002, 0); shoe.add(roller);
  shoe.add(kit.box(0.004, 0.004, shoeW, gold, { name: 'ShoeLip', pos: [-shoeL / 2 + 0.001, 0.012, 0] }));
  const badgeTex = kit.canvasTexture(256, 64, (c, w, h) => { c.fillStyle = '#0b0a0c'; c.fillRect(0, 0, w, h); c.fillStyle = '#e9c46a'; c.font = '900 40px Georgia, serif'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText('ZOHAR', w / 2, h / 2 + 2); });
  shoe.add(kit.box(0.08, 0.002, 0.022, new THREE.MeshStandardMaterial({ name: 'ShoeBadge', map: badgeTex, metalness: 0.3, roughness: 0.3 }), { name: 'ShoeBadge', pos: [0.05, shoeH + 0.0012, 0] }));
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
    c.fillStyle = '#ffd36b'; c.font = `800 ${game === 'holdem' ? 48 : 58}px Arial`; c.textAlign = 'center'; c.fillText({ blackjack: 'BLACKJACK', holdem: "CASINO HOLD'EM", baccarat: 'BACCARAT' }[game], w / 2, 80);
    c.fillStyle = '#ffffff'; c.font = '700 40px Arial'; c.fillText(game === 'holdem' ? 'ANTE 5  ·  200' : 'MIN 5  ·  MAX 500', w / 2, 145);
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
