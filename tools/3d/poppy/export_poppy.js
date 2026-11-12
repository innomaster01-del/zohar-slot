// Builds Poppy with the original makePet() code (three.js r128 from the desktop pet) and writes a GLB with:
//  - named parts the app moves: Body, Torso, Face, ArmL/ArmR, FootL/FootR, EarL/EarR
//  - faces the app switches by scale: EyeOpenL/R, EyeHappyL/R, EyeClosedL/R, MouthSmile, MouthGrin, MouthO
//  - clips: 0 "walk" (0.56 s loop: feet step, arms swing, ears bounce, body bobs), 1 "idle" (2.4 s: breathing, ears sway)
// usage: node export_poppy.js <three.min.js> <poppy_build.js> <girl|boy> <out.glb>
const fs = require('fs'), vm = require('vm');
const { writeGLB, loadThree } = require('./gltf_writer');
const [threePath, buildPath, gender, outPath] = process.argv.slice(2);

const sb = loadThree(threePath);
const prelude = `const mk=(w,h)=>{const c=document.createElement('canvas');c.width=w;c.height=h;return c;};
const V=(x=0,y=0,z=0)=>new THREE.Vector3(x,y,z); const rnd=(a,b)=>a+Math.random()*(b-a); const pick=a=>a[0];
const scene=new THREE.Scene();`;
vm.runInContext(prelude + fs.readFileSync(buildPath, 'utf8') + `\n;globalThis.PET=makePet(${JSON.stringify(gender)});globalThis.NOISE_IMG=NOISE.image;`, sb);
const THREE = sb.THREE, P = sb.PET;

// fur: keep the shell look with fewer shells (phone GPU); the strands are the alpha of one shared texture
const KEEP = 5;
(function thin(o) {
  const shells = o.children.filter(c => c.isMesh && P.fur.includes(c.material));
  if (shells.length > KEEP) { const keep = new Set([...Array(KEEP)].map((_, k) => Math.round(k * (shells.length - 1) / (KEEP - 1)))); shells.forEach((m, i) => { if (!keep.has(i)) m.visible = false; }); }
  o.children.forEach(thin);
})(P.root);
const ni = sb.NOISE_IMG.data, noise = { width: ni.width, height: ni.height, data: new Uint8Array(ni.width * ni.height * 4), nearest: true };
for (let i = 0; i < ni.width * ni.height; i++) { noise.data[i * 4] = noise.data[i * 4 + 1] = noise.data[i * 4 + 2] = 255; noise.data[i * 4 + 3] = ni.data[i * 4]; }
P.fur.forEach(m => { if (m.alphaMap) m.userData.rgba = noise; });

// names for the parts the app and the clips move
P.root.name = 'Poppy'; P.body.name = 'Body'; P.bodyG.name = 'Torso'; P.face.name = 'Face';
P.feet.forEach((f, i) => f.name = i ? 'FootR' : 'FootL'); P.arms.forEach((a, i) => a.name = i ? 'ArmR' : 'ArmL'); P.ears.forEach((e, i) => e.g.name = i ? 'EarR' : 'EarL');

// faces: each look in its own wrapper; the hidden ones are written at scale 0.0001
const hidden = new Set();
function wrap(parent, name, kids, show) {
  const w = new THREE.Group(); w.name = name; parent.add(w);
  for (const k of kids) if (k) { parent.remove(k); k.visible = true; w.add(k); }
  if (!show) hidden.add(w); return w;
}
P.eyes.forEach((e, i) => {
  const side = i ? 'R' : 'L';
  wrap(e.g, 'EyeOpen' + side, [e.ball, e.h1, e.h2, e.lashes], true);
  wrap(e.g, 'EyeHappy' + side, [e.happy], false);
  wrap(e.g, 'EyeClosed' + side, [e.closed], false);
  e.spiral.visible = false;
});
wrap(P.mouthG, 'MouthSmile', [P.mouths.smile], true);
wrap(P.mouthG, 'MouthGrin', [P.mouths.grin], false);
wrap(P.mouthG, 'MouthO', [P.mouths.o], false);
const skip = o => !o.visible || o === P.shadow;

// clips
const q = (ex, ey, ez) => { const v = new THREE.Quaternion().setFromEuler(new THREE.Euler(ex, ey, ez)); return [v.x, v.y, v.z, v.w]; };
const walk = { name: 'walk', duration: 0.56, frames: 17, tracks: [] }, idle = { name: 'idle', duration: 2.4, frames: 25, tracks: [] };
walk.tracks.push({ object: P.body, path: 'translation', fn: a => [0, Math.abs(Math.sin(a)) * 0.06, 0] });
walk.tracks.push({ object: P.body, path: 'rotation', fn: a => q(0.06, 0, Math.sin(a) * 0.09) });
P.feet.forEach((f, s) => { const b = f.position.clone(), sg = s ? 1 : -1; walk.tracks.push({ object: f, path: 'translation', fn: a => [b.x, b.y + Math.max(0, Math.sin(a) * sg) * 0.07, b.z + Math.cos(a) * sg * 0.09] }); });
P.arms.forEach((g, s) => { const sg = s ? 1 : -1; walk.tracks.push({ object: g, path: 'rotation', fn: a => q(Math.cos(a) * sg * 0.55, 0, -sg * 0.15) }); });
P.ears.forEach((e, s) => { const sg = s ? 1 : -1; walk.tracks.push({ object: e.g, path: 'rotation', fn: a => q(-Math.abs(Math.sin(a)) * 0.12, 0, e.base + sg * Math.sin(2 * a) * 0.08) }); });
idle.tracks.push({ object: P.body, path: 'translation', fn: a => [0, (1 - Math.cos(a)) * 0.006, 0] });
idle.tracks.push({ object: P.body, path: 'rotation', fn: a => q(0, 0, 0) });
P.feet.forEach(f => { const b = f.position.clone(); idle.tracks.push({ object: f, path: 'translation', fn: () => [b.x, b.y, b.z] }); });
P.arms.forEach((g, s) => { const sg = s ? 1 : -1; idle.tracks.push({ object: g, path: 'rotation', fn: a => q(0, 0, -sg * (0.1 + 0.05 * Math.sin(a))) }); });
P.ears.forEach((e, s) => { const sg = s ? 1 : -1; idle.tracks.push({ object: e.g, path: 'rotation', fn: a => q(0, 0, e.base + sg * 0.06 * Math.sin(a + s)) }); });

const { glb, gltf } = writeGLB(THREE, P.root, { skip, hidden, animations: [walk, idle] });
fs.writeFileSync(outPath, glb);
console.log(outPath, (glb.length / 1024).toFixed(0) + ' KB,', gltf.nodes.length, 'nodes,', gltf.meshes.length, 'meshes,', gltf.materials.length, 'materials,', gltf.animations.map(a => a.name).join('+'));
