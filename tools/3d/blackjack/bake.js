// Builds dealer.glb: Rocketbox Business_Male_04 (MIT) re-dressed as a casino dealer, mocap clips + baked IK dealing clips.
import * as THREE from 'three';
import { GLTFExporter } from 'three/addons/exporters/GLTFExporter.js';
import { avatar, clip, pending } from './common.js';
import { L } from '../shared/layout.js';

const FPS = 30;
const BOW_Y = 1.480;
const LEAN_SIGN = +(new URLSearchParams(location.search).get('ls') || 1);
const V = (x, y, z) => new THREE.Vector3(x, y, z);

export async function build() {
  const a = await avatar('dealer');
  await Promise.all(pending);
  a.name = 'Dealer';
  a.position.set(0, 0, L.dealerZ);           // the baker works in table space; the export resets it
  a.updateMatrixWorld(true);
  const bone = (n) => a.getObjectByName(n);
  const bones = []; a.traverse((n) => { if (n.isBone) bones.push(n); });

  // ---- mocap clips (Rocketbox, same Biped skeleton) ----
  const trim = (c, t0, t1, name) => { const s = THREE.AnimationUtils.subclip(c, name, Math.round(t0 * 30), Math.round(t1 * 30), 30); return s; };
  const idle = await clip('m_idle_breathe_01.max.fbx', 'Idle');
  const idle2 = await clip('m_idle_neutral_01.max.fbx', 'Idle2');
  const welcome = await clip('m_invite_sit.max.fbx', 'Welcome');
  const clapRaw = await clip('m_claphands_01.max.fbx', 'ClapRaw');
  const shrug = await clip('m_gestic_shrug_01.max.fbx', 'Shrug');
  const talkRaw = await clip('m_gestic_talk_neutral_01.max.fbx', 'TalkRaw');
  const nod = await clip('m_gestic_listen_accept_01.max.fbx', 'Nod');
  const wave = await clip('m_wave_01.max.fbx', 'Wave');
  const clap = trim(clapRaw, 0, 3.0, 'Clap');
  const talk = trim(talkRaw, 2, 8, 'Talk');

  // ---- sample a clip into per-bone local poses ----
  const mixer = new THREE.AnimationMixer(a);
  function poseAt(c, t) {
    mixer.stopAllAction(); mixer.uncacheRoot(a);
    for (const b of bones) { /* reset to bind */ }
    const act = mixer.clipAction(c); act.play(); mixer.setTime(Math.min(t, c.duration - 1e-3));
    a.updateMatrixWorld(true);
    const p = new Map(); for (const b of bones) p.set(b, { q: b.quaternion.clone(), v: b.position.clone() });
    act.stop(); return p;
  }
  function applyPose(p) { for (const [b, s] of p) { b.quaternion.copy(s.q); b.position.copy(s.v); } a.updateMatrixWorld(true); }

  // world-space rotate a bone by quaternion r (keeps children following)
  function rotWorld(b, r) {
    const pw = new THREE.Quaternion(); b.parent.getWorldQuaternion(pw);
    const bw = new THREE.Quaternion(); b.getWorldQuaternion(bw);
    const nw = r.clone().multiply(bw);
    b.quaternion.copy(pw.invert().multiply(nw)); b.updateMatrixWorld(true);
  }
  function setWorldQuat(b, q) { const pw = new THREE.Quaternion(); b.parent.getWorldQuaternion(pw); b.quaternion.copy(pw.invert().multiply(q)); b.updateMatrixWorld(true); }
  const wpos = (b) => b.getWorldPosition(new THREE.Vector3());

  // analytic two-bone IK (upper arm, forearm) with an elbow pole direction
  function ik2(side, target, pole) {
    const ua = bone(`Bip01_${side}_UpperArm`), fa = bone(`Bip01_${side}_Forearm`), hd = bone(`Bip01_${side}_Hand`);
    for (let iter = 0; iter < 2; iter++) {
      const A = wpos(ua), B = wpos(fa), C = wpos(hd);
      const lab = B.distanceTo(A), lcb = C.distanceTo(B);
      const lat = THREE.MathUtils.clamp(target.distanceTo(A), 0.05, lab + lcb - 1e-3);
      const ac = C.clone().sub(A), ab = B.clone().sub(A), ba = A.clone().sub(B), bc = C.clone().sub(B), at = target.clone().sub(A);
      const ang = (u, v) => Math.acos(THREE.MathUtils.clamp(u.clone().normalize().dot(v.clone().normalize()), -1, 1));
      const ac_ab_0 = ang(ac, ab), ba_bc_0 = ang(ba, bc), ac_at_0 = ang(ac, at);
      const ac_ab_1 = Math.acos(THREE.MathUtils.clamp((lcb * lcb - lab * lab - lat * lat) / (-2 * lab * lat), -1, 1));
      const ba_bc_1 = Math.acos(THREE.MathUtils.clamp((lat * lat - lab * lab - lcb * lcb) / (-2 * lab * lcb), -1, 1));
      // bend plane from the pole: elbow goes toward `pole`
      let axis0 = new THREE.Vector3().crossVectors(ac, ab); if (pole) { axis0 = new THREE.Vector3().crossVectors(ac, pole); if (axis0.lengthSq() < 1e-8) axis0 = new THREE.Vector3().crossVectors(ac, ab); }
      axis0.normalize();
      // the current elbow might be on the other side of the plane: measure signed
      const cur = new THREE.Vector3().crossVectors(ac, ab).normalize();
      const sign = cur.dot(axis0) >= 0 ? 1 : -1;
      const axis1 = new THREE.Vector3().crossVectors(ac, at); if (axis1.lengthSq() > 1e-10) axis1.normalize();
      rotWorld(ua, new THREE.Quaternion().setFromAxisAngle(axis0, sign * (ac_ab_1 - ac_ab_0)));
      rotWorld(fa, new THREE.Quaternion().setFromAxisAngle(axis0, sign * (ba_bc_1 - ba_bc_0)));
      if (axis1.lengthSq() > 0.5) rotWorld(ua, new THREE.Quaternion().setFromAxisAngle(axis1, ac_at_0));
      // second pass fixes the twist after the swing
      if (iter === 0 && pole) {
        // rotate the whole arm about the shoulder→target axis so the elbow points to the pole
        const A2 = wpos(ua), B2 = wpos(fa), axis = target.clone().sub(A2).normalize();
        const e = B2.clone().sub(A2); e.sub(axis.clone().multiplyScalar(e.dot(axis)));
        const pp = pole.clone(); pp.sub(axis.clone().multiplyScalar(pp.dot(axis)));
        if (e.lengthSq() > 1e-8 && pp.lengthSq() > 1e-8) {
          e.normalize(); pp.normalize();
          let ang2 = Math.acos(THREE.MathUtils.clamp(e.dot(pp), -1, 1)); if (new THREE.Vector3().crossVectors(e, pp).dot(axis) < 0) ang2 = -ang2;
          rotWorld(ua, new THREE.Quaternion().setFromAxisAngle(axis, ang2));
        }
      }
    }
  }
  // palm down, fingers pointing along `fwd` (horizontal-ish)
  function handFlat(side, fwd, palmDown = 1) {
    const hd = bone(`Bip01_${side}_Hand`);
    const X = fwd.clone().normalize();
    const Y = V(0, -1, 0).multiplyScalar(palmDown); Y.sub(X.clone().multiplyScalar(Y.dot(X))).normalize();   // palm normal
    // left hand: Y axis = palm normal; right hand: Y axis = palm normal too (both point to the body at rest)
    const Z = new THREE.Vector3().crossVectors(X, Y);
    const m = new THREE.Matrix4().makeBasis(X, Y, Z);
    setWorldQuat(hd, new THREE.Quaternion().setFromRotationMatrix(m));
  }
  // lean the torso forward (radians spread over pelvis / spine bones)
  function lean(amount, twist = 0) {
    const fwdAxis = V(LEAN_SIGN, 0, 0);    // bending about table x tips the chest toward +z
    for (const [n, k] of [['Bip01_Spine', 0.35], ['Bip01_Spine1', 0.35], ['Bip01_Spine2', 0.3]]) {
      rotWorld(bone(n), new THREE.Quaternion().setFromAxisAngle(fwdAxis, amount * k));
      if (twist) rotWorld(bone(n), new THREE.Quaternion().setFromAxisAngle(V(0, 1, 0), twist * k));
    }
    // keep the head looking at the table / player
    rotWorld(bone('Bip01_Neck'), new THREE.Quaternion().setFromAxisAngle(fwdAxis, -amount * 0.45));
  }
  function slerpPose(p0, p1, w) { const out = new Map(); for (const [b, s] of p0) { const e = p1.get(b); out.set(b, { q: s.q.clone().slerp(e.q, w), v: s.v.clone().lerp(e.v, w) }); } return out; }
  const snap = () => { const p = new Map(); for (const b of bones) p.set(b, { q: b.quaternion.clone(), v: b.position.clone() }); return p; };
  const smooth = (x) => x <= 0 ? 0 : x >= 1 ? 1 : x * x * (3 - 2 * x);

  // keyframed IK clip: keys = [{t, L:{p,fwd,pole}|null, R:..., lean, twist}] ; base = idle; weight ramps in/out
  function ikClip(name, dur, keys, wIn = 0.25, wOut = 0.3) {
    const n = Math.round(dur * FPS) + 1; const times = []; const tracks = new Map();
    for (const b of bones) tracks.set(b, { q: [], v: [] });
    const interp = (side, t) => {
      const ks = keys.filter((k) => k[side]); if (!ks.length) return null;
      let i = 0; while (i < ks.length - 1 && ks[i + 1].t <= t) i++;
      const k0 = ks[i], k1 = ks[Math.min(i + 1, ks.length - 1)];
      const u = k1.t === k0.t ? 0 : smooth((t - k0.t) / (k1.t - k0.t));
      return { p: k0[side].p.clone().lerp(k1[side].p, u), fwd: k0[side].fwd.clone().lerp(k1[side].fwd, u), pole: (k0[side].pole || V(0.3, -1, -0.3)).clone().lerp(k1[side].pole || V(0.3, -1, -0.3), u), w: (k0[side].w ?? 1) * (1 - u) + (k1[side].w ?? 1) * u };
    };
    const scalar = (prop, t) => { let i = 0; while (i < keys.length - 1 && keys[i + 1].t <= t) i++; const k0 = keys[i], k1 = keys[Math.min(i + 1, keys.length - 1)]; const u = k1.t === k0.t ? 0 : smooth((t - k0.t) / (k1.t - k0.t)); return (k0[prop] ?? 0) * (1 - u) + (k1[prop] ?? 0) * u; };
    for (let f = 0; f < n; f++) {
      const t = f / FPS; times.push(t);
      const base = poseAt(idle, (t + 0.5) % idle.duration); applyPose(base);
      lean(scalar('lean', t), scalar('twist', t));
      for (const side of ['L', 'R']) {
        const s = interp(side, t); if (!s) continue;
        const before = snap();
        ik2(side, s.p, s.pole); handFlat(side, s.fwd);
        if (s.w < 1) applyPose(slerpPose(before, snap(), s.w));
      }
      const w = Math.min(smooth(t / wIn), smooth((dur - t) / wOut));
      const final = slerpPose(base, snap(), w); applyPose(final);
      for (const b of bones) { const tr = tracks.get(b); tr.q.push(...b.quaternion.toArray()); tr.v.push(...b.position.toArray()); }
    }
    const kt = [];
    for (const [b, tr] of tracks) {
      kt.push(new THREE.QuaternionKeyframeTrack(`${b.name}.quaternion`, times, tr.q));
      if (b.name === 'Bip01_Pelvis') kt.push(new THREE.VectorKeyframeTrack(`${b.name}.position`, times, tr.v));
    }
    return new THREE.AnimationClip(name, dur, kt);
  }

  const P = (x, y, z) => V(x, y, z);
  const hy = L.feltY + 0.025;                       // palm height over the felt
  const shoe = P(L.shoe[0] - 0.03, hy + 0.02, L.shoe[2] - 0.02);
  const elbowL = V(0.6, -1, -0.4), elbowR = V(-0.6, -1, -0.4);
  // deal with the left hand: reach the shoe, pull a card, carry it to the target, slide, back
  const deal = (name, tx, tz) => ikClip(name, 1.25, [
    { t: 0.0, lean: 0.0, twist: 0 },
    { t: 0.30, lean: 0.38, twist: 0.10, L: { p: shoe, fwd: V(0.15, -0.25, 1), pole: elbowL } },
    { t: 0.45, lean: 0.38, twist: 0.10, L: { p: shoe.clone().add(V(-0.04, 0.01, -0.03)), fwd: V(0, -0.25, 1), pole: elbowL } },
    { t: 0.80, lean: 0.72, twist: 0.0, L: { p: P(tx + 0.05, hy, tz), fwd: V(-0.2, -0.15, 1), pole: elbowL } },
    { t: 0.92, lean: 0.68, twist: 0.0, L: { p: P(tx + 0.05, hy + 0.01, tz - 0.04), fwd: V(-0.2, -0.15, 1), pole: elbowL } },
    { t: 1.25, lean: 0.0, twist: 0, L: { p: P(0.2, 0.95, L.dealerZ + 0.22), fwd: V(0, -0.6, 1), pole: elbowL, w: 0 } },
  ], 0.2, 0.3);
  const reachZ = (z) => Math.min(z, 0.40);          // the hand stops here; the card slides the rest
  const dealPlayer = deal('DealPlayer', 0, reachZ(L.playerCards[2]));
  const dealLeft = deal('DealPlayerL', -L.splitDx, reachZ(L.playerCards[2]));
  const dealRight = deal('DealPlayerR', L.splitDx, reachZ(L.playerCards[2]));
  const dealSelf = deal('DealDealer', L.dealerCards[0] + 0.05, L.dealerCards[2]);
  // flip the hole card with the right hand
  const hole = P(L.dealerCards[0] + L.cardW * 0.6, hy, L.dealerCards[2]);
  const flip = ikClip('Flip', 1.1, [
    { t: 0, lean: 0 },
    { t: 0.35, lean: 0.35, R: { p: hole.clone().add(V(0, 0.01, -0.03)), fwd: V(0.2, -0.2, 1), pole: elbowR } },
    { t: 0.55, lean: 0.35, R: { p: hole.clone().add(V(0, 0.06, 0.0)), fwd: V(0.2, -0.2, 1), pole: elbowR } },
    { t: 0.75, lean: 0.33, R: { p: hole.clone().add(V(-0.02, 0.02, 0.02)), fwd: V(0.2, -0.2, 1), pole: elbowR } },
    { t: 1.1, lean: 0, R: { p: P(-0.2, 0.95, L.dealerZ + 0.22), fwd: V(0, -0.6, 1), pole: elbowR, w: 0 } },
  ]);
  // take the lost bet: both hands sweep in and back toward the rack
  const pz = reachZ(L.playerBet[2]);
  const collect = ikClip('Collect', 1.3, [
    { t: 0, lean: 0 },
    { t: 0.4, lean: 0.72, L: { p: P(0.12, hy, pz), fwd: V(-0.4, -0.2, 1), pole: elbowL }, R: { p: P(-0.12, hy, pz), fwd: V(0.4, -0.2, 1), pole: elbowR } },
    { t: 0.8, lean: 0.35, L: { p: P(0.08, hy, 0.12), fwd: V(-0.4, -0.2, 1), pole: elbowL }, R: { p: P(-0.08, hy, 0.12), fwd: V(0.4, -0.2, 1), pole: elbowR } },
    { t: 1.3, lean: 0, L: { p: P(0.2, 0.95, L.dealerZ + 0.22), fwd: V(0, -0.6, 1), pole: elbowL, w: 0 }, R: { p: P(-0.2, 0.95, L.dealerZ + 0.22), fwd: V(0, -0.6, 1), pole: elbowR, w: 0 } },
  ]);
  // pay a win: right hand from the rack to the bet
  const pay = ikClip('Pay', 1.25, [
    { t: 0, lean: 0 },
    { t: 0.3, lean: 0.25, R: { p: P(-0.12, hy + 0.01, L.rack[2] + 0.02), fwd: V(0, -0.4, 1), pole: elbowR } },
    { t: 0.75, lean: 0.72, R: { p: P(-0.07, hy + 0.01, pz), fwd: V(0.15, -0.2, 1), pole: elbowR } },
    { t: 0.9, lean: 0.68, R: { p: P(-0.07, hy + 0.03, pz - 0.05), fwd: V(0.15, -0.2, 1), pole: elbowR } },
    { t: 1.25, lean: 0, R: { p: P(-0.2, 0.95, L.dealerZ + 0.22), fwd: V(0, -0.6, 1), pole: elbowR, w: 0 } },
  ]);
  // pick up the dealt cards to the discard tray
  const clear = ikClip('Clear', 1.3, [
    { t: 0, lean: 0 },
    { t: 0.4, lean: 0.72, R: { p: P(-0.02, hy, reachZ(L.playerCards[2])), fwd: V(0.2, -0.2, 1), pole: elbowR } },
    { t: 0.85, lean: 0.3, R: { p: P(L.discard[0] + 0.06, hy + 0.04, L.discard[2]), fwd: V(-0.5, -0.2, 1), pole: elbowR } },
    { t: 1.3, lean: 0, R: { p: P(-0.2, 0.95, L.dealerZ + 0.22), fwd: V(0, -0.6, 1), pole: elbowR, w: 0 } },
  ]);

  // ---- black bow tie on the collar, rigidly attached to the neck bone ----
  applyPose(poseAt(idle, 0.5));
  {
    const rc = new THREE.Raycaster(V(0, BOW_Y, L.dealerZ + 0.6), V(0, 0, -1));
    const hit = rc.intersectObject(a, true)[0];
    const front = hit ? hit.point : V(0, BOW_Y, L.dealerZ + 0.09);
    const mat = new THREE.MeshStandardMaterial({ name: 'BowTie', color: 0x0d0d10, roughness: 0.75, metalness: 0 });
    const bow = new THREE.Group(); bow.name = 'BowTie';
    // butterfly outline: narrow at the knot, flared at the wing ends
    const sh = new THREE.Shape();
    const w = 0.055, h = 0.020, k = 0.006;
    sh.moveTo(0, k); sh.bezierCurveTo(w * 0.35, k * 1.2, w * 0.7, h, w, h * 0.95); sh.bezierCurveTo(w * 1.06, h * 0.3, w * 1.06, -h * 0.3, w, -h * 0.95);
    sh.bezierCurveTo(w * 0.7, -h, w * 0.35, -k * 1.2, 0, -k); sh.bezierCurveTo(-w * 0.35, -k * 1.2, -w * 0.7, -h, -w, -h * 0.95);
    sh.bezierCurveTo(-w * 1.06, -h * 0.3, -w * 1.06, h * 0.3, -w, h * 0.95); sh.bezierCurveTo(-w * 0.7, h, -w * 0.35, k * 1.2, 0, k);
    const wings = new THREE.ExtrudeGeometry(sh, { depth: 0.006, bevelEnabled: true, bevelThickness: 0.003, bevelSize: 0.002, bevelSegments: 3, curveSegments: 16 });
    wings.translate(0, 0, -0.003); bow.add(new THREE.Mesh(wings, mat));
    const knot = new THREE.Mesh(new THREE.BoxGeometry(0.014, 0.016, 0.012, 2, 2, 2), mat); knot.position.z = 0.003; bow.add(knot);
    bow.position.copy(front).add(V(0, 0, 0.024));
    const neck = bone('Bip01_Neck'); neck.attach(bow);       // keeps the world placement, follows the neck from now on
    window.BOW = [front.toArray(), !!hit];
  }

  const clips = [idle, idle2, welcome, clap, shrug, talk, nod, wave, dealPlayer, dealLeft, dealRight, dealSelf, flip, collect, pay, clear];
  // reset pose
  applyPose(poseAt(idle, 0.5));
  return { a, clips, bone, mixer };
}

export async function exportGlb(a, clips) {
  // export in the avatar's own space (feet at the origin, facing +z); the app places it at L.dealerZ
  a.position.set(0, 0, 0); a.updateMatrixWorld(true);
  const keep = clips.map((c) => { const k = c.clone(); k.tracks = k.tracks.filter((t) => !/Bip01_(M|R|L)?(Eye|Mouth|Jaw|Lip|Tongue|Caninus|Masseter|Cheek|Eyebrow|InnerEyebrow|OuterEyebrow|MiddleEyebrow|Nose|Upperlip|UpperLip|BottomLip)/.test(t.name.split('.')[0]) || /Hand|Finger/.test(t.name)); k.optimize(); return k; });
  const ex = new GLTFExporter();
  return await ex.parseAsync(a, { binary: true, animations: keep, onlyVisible: false, maxTextureSize: 1024 });
}
