// fx.js — AR effects as small animated GLBs in the SAME frame as the cabinet (floor centre = origin, front +Z).
// Each returns { scene, animations:[clip 'Play'] }. Unlit bright materials so they glow over the camera image.
const rng = (s) => () => ((s = (s * 16807) % 2147483647) / 2147483647);
const unlit = (THREE, name, color, opacity = 1) => new THREE.MeshBasicMaterial({ name, color, transparent: opacity < 1, opacity, depthWrite: opacity >= 1, toneMapped: false, side: THREE.DoubleSide });
// visibility track: scale 1 while any [t0,t1] window is open, ~0 otherwise (STEP interpolation)
function blinkTrack(THREE, node, windows, dur) {
  const times = [0], vals = [1e-4, 1e-4, 1e-4];
  for (const [a, b] of windows) { times.push(a, b); vals.push(1, 1, 1, 1e-4, 1e-4, 1e-4); }
  times.push(dur); vals.push(1e-4, 1e-4, 1e-4);
  return new THREE.VectorKeyframeTrack(`${node.name}.scale`, times, vals, THREE.InterpolateDiscrete);
}

export function lightning(THREE) {
  const g = new THREE.Group(); g.name = 'FxLightning'; const R = rng(11), dur = 1.8, tracks = [];
  const core = unlit(THREE, 'BoltCore', 0xeaf6ff), glow = unlit(THREE, 'BoltGlow', 0x5ab4ff, 0.35);
  const bolt = (from, to, k, jitter) => {
    const pts = []; for (let i = 0; i <= k; i++) { const a = i / k, j = i === 0 || i === k ? 0 : jitter;
      pts.push(new THREE.Vector3(from[0] + (to[0] - from[0]) * a + (R() - 0.5) * j, from[1] + (to[1] - from[1]) * a + (R() - 0.5) * j * 0.4, from[2] + (to[2] - from[2]) * a + (R() - 0.5) * j)); }
    return new THREE.CatmullRomCurve3(pts, false, 'catmullrom', 0.1);
  };
  const targets = [[0, 2.2, 0.0], [-0.9, 0, 0.6], [0.9, 0, 0.5], [-0.6, 0, -0.5], [0.7, 0, -0.4], [0, 1.5, 0.35]];
  targets.forEach((to, i) => {
    const from = [to[0] * 0.4 + (R() - 0.5) * 0.6, 3.1 + R() * 0.4, to[2] * 0.4 + (R() - 0.5) * 0.4];
    const curve = bolt(from, to, 14, 0.35);
    const b = new THREE.Group(); b.name = `Bolt${i}`;
    b.add(new THREE.Mesh(new THREE.TubeGeometry(curve, 60, 0.006, 5), core), new THREE.Mesh(new THREE.TubeGeometry(curve, 60, 0.03, 6), glow));
    // a branch off the middle
    const mid = curve.getPoint(0.4), br = bolt([mid.x, mid.y, mid.z], [mid.x + (R() - 0.5) * 0.8, mid.y - 0.5 - R() * 0.4, mid.z + (R() - 0.5) * 0.6], 6, 0.15);
    b.add(new THREE.Mesh(new THREE.TubeGeometry(br, 24, 0.004, 5), core), new THREE.Mesh(new THREE.TubeGeometry(br, 24, 0.018, 6), glow));
    g.add(b);
    const t0 = 0.05 + i * 0.18 + R() * 0.1;
    tracks.push(blinkTrack(THREE, b, [[t0, t0 + 0.07], [t0 + 0.12, t0 + 0.2], [t0 + 0.5, t0 + 0.56]], dur));
  });
  // halo flash ring at the topper
  const ring = new THREE.Mesh(new THREE.TorusGeometry(0.5, 0.02, 8, 64), glow); ring.name = 'FlashRing'; ring.position.set(0, 2.15, 0); ring.rotation.x = Math.PI / 2; g.add(ring);
  tracks.push(blinkTrack(THREE, ring, [[0.05, 0.15], [0.5, 0.6], [1.0, 1.08]], dur));
  return { scene: g, animations: [new THREE.AnimationClip('Play', dur, tracks)] };
}

export function coins(THREE, { count = 36, dur = 3.0, seed = 5 } = {}) {
  const g = new THREE.Group(); g.name = 'FxCoins'; const R = rng(seed), tracks = [];
  const gold = new THREE.MeshStandardMaterial({ name: 'CoinGold', color: 0xffc935, metalness: 1, roughness: 0.22, emissive: 0x6a4200, emissiveIntensity: 0.6 });
  const face = new THREE.MeshStandardMaterial({ name: 'CoinFace', color: 0xffe07a, metalness: 1, roughness: 0.15, emissive: 0x7a5200, emissiveIntensity: 0.6 });
  const geo = new THREE.CylinderGeometry(0.028, 0.028, 0.005, 24);
  for (let i = 0; i < count; i++) {
    const c = new THREE.Mesh(geo, [gold, face, face]); c.name = `Coin${i}`; g.add(c);
    const start = [(R() - 0.5) * 0.4, 1.2 + R() * 0.3, 0.32], ang = (R() - 0.5) * 2.2, dist = 0.5 + R() * 1.0;
    const end = [Math.sin(ang) * dist, 0.003, 0.35 + Math.cos(ang) * dist], up = 1.2 + R() * 1.0, delay = R() * 0.9, flight = 0.9 + R() * 0.4;
    const times = [], pos = [], quat = [], scl = [], q = new THREE.Quaternion(), e = new THREE.Euler(), spin = 8 + R() * 10;
    const steps = 16;
    times.push(0); pos.push(...start); scl.push(1e-4, 1e-4, 1e-4); quat.push(...q.toArray());
    for (let k = 0; k <= steps; k++) {
      const a = k / steps, t = delay + a * flight;
      // ballistic arc start→end with apex `up` above the straight line
      pos.push(start[0] + (end[0] - start[0]) * a, start[1] + (end[1] - start[1]) * a + 4 * up * a * (1 - a) * 0.5, start[2] + (end[2] - start[2]) * a);
      times.push(t); e.set(spin * t, spin * 0.6 * t, 0); q.setFromEuler(e); quat.push(...q.toArray()); scl.push(1, 1, 1);
    }
    // bounce + settle flat + fade (shrink)
    const tl = delay + flight; e.set(Math.PI / 2 * 0, 0, 0);
    times.push(tl + 0.18, tl + 0.4, dur - 0.3, dur);
    pos.push(end[0] + (end[0] - start[0]) * 0.05, 0.06, end[2] + (end[2] - start[2]) * 0.05, end[0] + (end[0] - start[0]) * 0.08, 0.003, end[2] + (end[2] - start[2]) * 0.08, end[0] + (end[0] - start[0]) * 0.08, 0.003, end[2] + (end[2] - start[2]) * 0.08, end[0] + (end[0] - start[0]) * 0.08, 0.003, end[2] + (end[2] - start[2]) * 0.08);
    for (let k = 0; k < 4; k++) { q.setFromEuler(new THREE.Euler(0, R() * 6, 0)); quat.push(...q.toArray()); }
    scl.push(1, 1, 1, 1, 1, 1, 1, 1, 1, 1e-4, 1e-4, 1e-4);
    // keyframe times must be increasing
    const order = times.map((t, i) => [t, i]);
    for (let k = 1; k < order.length; k++) if (order[k][0] <= order[k - 1][0]) order[k][0] = order[k - 1][0] + 0.001;
    const T = order.map((o) => Math.min(dur, o[0]));
    for (let k = 1; k < T.length; k++) if (T[k] <= T[k - 1]) T[k] = T[k - 1] + 1e-4;
    tracks.push(new THREE.VectorKeyframeTrack(`${c.name}.position`, T, pos), new THREE.QuaternionKeyframeTrack(`${c.name}.quaternion`, T, quat), new THREE.VectorKeyframeTrack(`${c.name}.scale`, T, scl));
  }
  return { scene: g, animations: [new THREE.AnimationClip('Play', dur + 0.01, tracks)] };
}

export function bigWin(THREE, kit) {
  const g = new THREE.Group(); g.name = 'FxBigWin'; const R = rng(23), dur = 4.0, tracks = [];
  // light beams around the machine: tall open cones with a vertical fade texture
  const beamTex = kit.canvasTexture(16, 256, (c, w, h) => { const l = c.createLinearGradient(0, h, 0, 0); l.addColorStop(0, 'rgba(255,230,140,0.85)'); l.addColorStop(0.5, 'rgba(255,200,60,0.3)'); l.addColorStop(1, 'rgba(255,180,40,0)'); c.fillStyle = l; c.fillRect(0, 0, w, h); });
  const beamMat = new THREE.MeshBasicMaterial({ name: 'Beam', map: beamTex, transparent: true, depthWrite: false, side: THREE.DoubleSide, toneMapped: false });
  for (let i = 0; i < 8; i++) {
    const a = (i / 8) * Math.PI * 2, b = new THREE.Mesh(new THREE.CylinderGeometry(0.09, 0.05, 2.6, 16, 1, true), beamMat);
    b.name = `Beam${i}`; b.geometry.translate(0, 1.3, 0); b.position.set(Math.sin(a) * 1.0, 0, 0.2 + Math.cos(a) * 0.9); g.add(b);
    const t0 = i * 0.08, tilt = (R() - 0.5) * 0.5;
    tracks.push(new THREE.VectorKeyframeTrack(`${b.name}.scale`, [0, t0, t0 + 0.4, dur - 0.6, dur], [1e-4, 1e-4, 1e-4, 1e-4, 1e-4, 1e-4, 1, 1, 1, 1, 1, 1, 1e-4, 1e-4, 1e-4]));
    tracks.push(new THREE.QuaternionKeyframeTrack(`${b.name}.quaternion`, [0, dur / 2, dur],
      [[0, 0, -tilt], [0, 0, tilt], [0, 0, -tilt]].flatMap(([x, y, z]) => new THREE.Quaternion().setFromEuler(new THREE.Euler(x + 0.15 * Math.cos(a), y, z)).toArray())));
  }
  // shockwave ring on the floor
  const ringMat = unlit(THREE, 'Shock', 0xffd34d, 0.7);
  for (let k = 0; k < 2; k++) {
    const r = new THREE.Mesh(new THREE.RingGeometry(0.42, 0.5, 64), ringMat); r.name = `Shock${k}`; r.rotation.x = -Math.PI / 2; r.position.set(0, 0.005, 0.2); g.add(r);
    const t0 = k * 0.7; tracks.push(new THREE.VectorKeyframeTrack(`${r.name}.scale`, [0, t0, t0 + 1.2, t0 + 1.21, dur], [1e-4, 1e-4, 1e-4, 0.2, 0.2, 0.2, 4, 4, 4, 1e-4, 1e-4, 1e-4, 1e-4, 1e-4, 1e-4]));
  }
  // sparkles rising around the cabinet
  const sp = unlit(THREE, 'Sparkle', 0xfff3b0);
  const sg = new THREE.OctahedronGeometry(0.018);
  for (let i = 0; i < 40; i++) {
    const s = new THREE.Mesh(sg, sp); s.name = `Spark${i}`; g.add(s);
    const a = R() * Math.PI * 2, r = 0.45 + R() * 0.6, x = Math.sin(a) * r, z = 0.1 + Math.cos(a) * r, t0 = R() * 2.2, life = 1.0 + R() * 0.8;
    tracks.push(new THREE.VectorKeyframeTrack(`${s.name}.position`, [0, t0, t0 + life, dur], [x, 0, z, x, 0.05, z, x + (R() - 0.5) * 0.3, 1.6 + R() * 1.0, z, x, 0, z]));
    tracks.push(new THREE.VectorKeyframeTrack(`${s.name}.scale`, [0, t0, t0 + 0.01, t0 + life, t0 + life + 0.01, dur].map((t) => Math.min(t, dur - 0.001 + 0.001)).map((t, i, A) => i && t <= A[i - 1] ? A[i - 1] + 1e-4 : t),
      [1e-4, 1e-4, 1e-4, 1e-4, 1e-4, 1e-4, 1, 1, 1, 0.6, 0.6, 0.6, 1e-4, 1e-4, 1e-4, 1e-4, 1e-4, 1e-4], THREE.InterpolateLinear));
  }
  return { scene: g, animations: [new THREE.AnimationClip('Play', dur + 0.01, tracks)] };
}

export function freeSpins(THREE, kit) {
  const g = new THREE.Group(); g.name = 'FxFree'; const R = rng(31), dur = 4.0, tracks = [];
  const runes = kit.canvasTexture(1024, 1024, (c, w, h) => {
    c.clearRect(0, 0, w, h); c.translate(w / 2, h / 2); c.shadowColor = '#ff4fd8'; c.shadowBlur = 24;
    c.strokeStyle = 'rgba(255,120,230,0.95)'; c.lineWidth = 10; c.beginPath(); c.arc(0, 0, 480, 0, 7); c.stroke();
    c.lineWidth = 5; c.beginPath(); c.arc(0, 0, 420, 0, 7); c.stroke(); c.beginPath(); c.arc(0, 0, 260, 0, 7); c.stroke();
    // casino medallion: gold diamonds and stars around the ring (no occult symbols)
    for (let i = 0; i < 12; i++) { const a = (i / 12) * Math.PI * 2; c.save(); c.rotate(a); c.translate(0, -340);
      c.fillStyle = i % 2 ? 'rgba(255,215,90,0.95)' : 'rgba(255,140,235,0.95)'; c.beginPath(); c.moveTo(0, -40); c.lineTo(22, 0); c.lineTo(0, 40); c.lineTo(-22, 0); c.closePath(); c.fill(); c.restore(); }
    c.fillStyle = 'rgba(255,220,250,0.95)'; c.font = '700 40px sans-serif'; c.textAlign = 'center'; c.textBaseline = 'middle';
    for (let i = 0; i < 36; i++) { const a = (i / 36) * Math.PI * 2; c.save(); c.rotate(a); c.fillText('★', 0, -450); c.restore(); }
    c.font = '900 120px Georgia, serif'; c.fillStyle = 'rgba(255,215,90,0.95)'; c.fillText('×3', 0, 0);
  });
  const circle = new THREE.Mesh(new THREE.PlaneGeometry(1.8, 1.8), new THREE.MeshBasicMaterial({ name: 'RuneCircle', map: runes, transparent: true, depthWrite: false, toneMapped: false }));
  circle.name = 'RuneCircle'; circle.rotation.x = -Math.PI / 2; circle.position.set(0, 0.006, 0.25);
  const spinner = new THREE.Group(); spinner.name = 'RuneSpin'; spinner.position.set(0, 0.006, 0.25); circle.position.set(0, 0, 0); spinner.add(circle); g.add(spinner);
  const qs = [0, 1, 2, 3, 4].map((k) => new THREE.Quaternion().setFromEuler(new THREE.Euler(0, k * Math.PI / 2, 0)).toArray());
  tracks.push(new THREE.QuaternionKeyframeTrack('RuneSpin.quaternion', [0, 1, 2, 3, 4].map((k) => k * dur / 4), qs.flat()));
  tracks.push(new THREE.VectorKeyframeTrack('RuneSpin.scale', [0, 0.5, dur], [0.2, 0.2, 0.2, 1, 1, 1, 1, 1, 1]));
  // rising pink motes
  const mote = unlit(THREE, 'Mote', 0xff7ae6), mg = new THREE.OctahedronGeometry(0.014);
  for (let i = 0; i < 30; i++) {
    const m = new THREE.Mesh(mg, mote); m.name = `Mote${i}`; g.add(m);
    const a = R() * Math.PI * 2, r = 0.3 + R() * 0.55, x = Math.sin(a) * r, z = 0.25 + Math.cos(a) * r, t0 = R() * (dur - 1.5);
    tracks.push(new THREE.VectorKeyframeTrack(`${m.name}.position`, [0, t0, t0 + 1.4, dur], [x, -1, z, x, 0.02, z, x * 0.6, 2.0, z * 0.6 + 0.1, x, -1, z]));
  }
  return { scene: g, animations: [new THREE.AnimationClip('Play', dur, tracks)] };
}
