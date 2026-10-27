// Builds host.glb: Rocketbox Business_Female_04 (MIT) restyled as a wheel-show host (recolor.py), mocap clips + a baked spin.
import * as THREE from 'three';
import { GLTFExporter } from 'three/addons/exporters/GLTFExporter.js';
import { avatar, clip, pending } from './common.js';
import { L } from '../shared/layout.js';

const FPS = 30;
const BOW_Y = 1.480;
const LEAN_SIGN = +(new URLSearchParams(location.search).get('ls') || 1);
const V = (x, y, z) => new THREE.Vector3(x, y, z);

export async function build() {
  const a = await avatar('host', 'Business_Female_04_facial.fbx');
  await Promise.all(pending);
  a.name = 'Host';
  a.position.set(0, 0, 0);                   // the host's own frame: feet at the origin, facing +z
  a.updateMatrixWorld(true);
  const bone = (n) => a.getObjectByName(n);
  const bones = []; a.traverse((n) => { if (n.isBone) bones.push(n); });

  // ---- mocap clips (Rocketbox, same Biped skeleton) ----
  const trim = (c, t0, t1, name) => { const s = THREE.AnimationUtils.subclip(c, name, Math.round(t0 * 30), Math.round(t1 * 30), 30); return s; };
  const idle = await clip('f_idle_breathe_01.max.fbx', 'Idle');
  const idle2 = await clip('f_idle_neutral_05.max.fbx', 'Idle2');
  const welcome = await clip('f_wave_01.max.fbx', 'Welcome');
  const present = await clip('f_gestic_presentation_right_01.max.fbx', 'Present');
  const presentL = await clip('f_gestic_presentation_left_01.max.fbx', 'PresentL');
  const talkRaw = await clip('f_gestic_talk_relaxed_01.max.fbx', 'TalkRaw');
  const excited = await clip('f_gestic_talk_excited_01.max.fbx', 'Excited');
  const clapRaw = await clip('f_claphands_01.max.fbx', 'ClapRaw');
  const cheer = await clip('f_cheer_02.max.fbx', 'Cheer');
  const shrug = await clip('f_gestic_shrug_01.max.fbx', 'Shrug');
  const laugh = await clip('f_gestic_laugh_low.max.fbx', 'Laugh');
  const hair = await clip('f_idle_touch_hair_01.max.fbx', 'Hair');
  const moderate = await clip('f_moderate_01.max.fbx', 'Moderate');
  const clap = trim(clapRaw, 0, Math.min(3.0, clapRaw.duration), 'Clap');
  const idle2s = trim(idle2, 0, 10, 'Idle2'), presentS = trim(present, 0, 5, 'Present'), excitedS = trim(excited, 0, 6, 'Excited'), cheerS = trim(cheer, 0, 5, 'Cheer'), laughS = trim(laugh, 0, 4, 'Laugh');
  const talk = trim(talkRaw, 1, Math.min(7, talkRaw.duration), 'Talk');

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
  function handFlat(side, fwd, palm = null) {
    const hd = bone(`Bip01_${side}_Hand`);
    const X = fwd.clone().normalize();
    const Y = (palm ? palm.clone() : V(0, -1, 0)); Y.sub(X.clone().multiplyScalar(Y.dot(X))).normalize();   // palm normal
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
      return { p: k0[side].p.clone().lerp(k1[side].p, u), fwd: k0[side].fwd.clone().lerp(k1[side].fwd, u), pole: (k0[side].pole || V(0.3, -1, -0.3)).clone().lerp(k1[side].pole || V(0.3, -1, -0.3), u), w: (k0[side].w ?? 1) * (1 - u) + (k1[side].w ?? 1) * u,
        palm: (k0[side].palm || k1[side].palm) ? (k0[side].palm || V(0, -1, 0)).clone().lerp(k1[side].palm || V(0, -1, 0), u) : null };
    };
    const scalar = (prop, t) => { let i = 0; while (i < keys.length - 1 && keys[i + 1].t <= t) i++; const k0 = keys[i], k1 = keys[Math.min(i + 1, keys.length - 1)]; const u = k1.t === k0.t ? 0 : smooth((t - k0.t) / (k1.t - k0.t)); return (k0[prop] ?? 0) * (1 - u) + (k1[prop] ?? 0) * u; };
    for (let f = 0; f < n; f++) {
      const t = f / FPS; times.push(t);
      const base = poseAt(idle, (t + 0.5) % idle.duration); applyPose(base);
      lean(scalar('lean', t), scalar('twist', t));
      // a small step (pelvis shift, in the avatar's own frame): the whole body moves toward the wheel and back
      { const sx = scalar('sx', t), sz = scalar('sz', t); if (sx || sz) { const pel = bone('Bip01_Pelvis'); const wp = pel.getWorldPosition(new THREE.Vector3()).add(V(sx, 0, sz)); pel.position.copy(pel.parent.worldToLocal(wp)); a.updateMatrixWorld(true); } }
      for (const side of ['L', 'R']) {
        const s = interp(side, t); if (!s) continue;
        const before = snap();
        ik2(side, s.p, s.pole); handFlat(side, s.fwd, s.palm);
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
  // ---- the spin: in the host's own frame (stage → host: translate by -host, rotate by -yaw) ----
  const MW = L.mw, yaw = THREE.MathUtils.degToRad(MW.hostYaw);
  const local = (p) => { const x = p[0] - MW.host[0], z = p[2] - MW.host[2]; const c = Math.cos(-yaw), s = Math.sin(-yaw); return V(c * x + s * z, p[1] - MW.host[1], -s * x + c * z); };
  const grab = local(MW.grab), pull = local(MW.pull), elbowR = V(-0.8, -0.6, -0.2);
  const down = V(0, -1, 0);
  // she steps a little toward the wheel, takes the rim on its outer edge (never in front of the segments), pulls it down
  // along the rim (the wheel turns clockwise, the right side goes down), lets go outward and steps back
  const ldir = (d) => { const c = Math.cos(-yaw), s2 = Math.sin(-yaw); return V(c * d[0] + s2 * d[2], d[1], -s2 * d[0] + c * d[2]); };
  const Wc = MW.wheel, deg = Math.PI / 180;
  const rimPt = (th, r = 0.86, z = 0.09) => local([Wc[0] + r * Math.sin(th * deg), Wc[1] + r * Math.cos(th * deg), Wc[2] + z]);
  const palmAt = (th) => ldir([-Math.sin(th * deg), -Math.cos(th * deg), 0]);
  const fingers = ldir([0, 0, -1]), elbowOut = ldir([1, -0.6, 0.25]);
  const step = ldir([-0.13, 0, 0.05]);
  const hold = (t, th, extra = {}) => ({ t, lean: 0.14, twist: 0.25, sx: step.x, sz: step.z, R: { p: rimPt(th), fwd: fingers, pole: elbowOut, palm: palmAt(th) }, ...extra });
  const spinWheel = ikClip('SpinWheel', 2.3, [
    { t: 0, lean: 0, sx: 0, sz: 0 },
    { t: 0.42, lean: 0.1, twist: 0.2, sx: step.x, sz: step.z },
    hold(0.66, 78), hold(0.82, 88), hold(0.94, 100), hold(1.05, 112), hold(1.15, 124),
    { t: 1.32, lean: 0.12, twist: 0.2, sx: step.x, sz: step.z, R: { p: rimPt(128, 0.97, 0.2), fwd: ldir([0.3, -0.5, 0.5]), pole: elbowOut } },
    { t: 2.3, lean: 0, twist: 0, sx: 0, sz: 0, R: { p: V(-0.2, 0.9, 0.2), fwd: down, pole: elbowR, w: 0 } },
  ], 0.3, 0.45);
  const clips = [idle, idle2s, welcome, presentS, talk, excitedS, clap, cheerS, shrug, laughS, hair, spinWheel];
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
