// Builds Pufi with the original makePet() code (three.js r128 from the installer) and writes a GLB with a walk loop.
// usage: node export_pufi.js <three.js> <pufi_build.js> <girl|boy> <out.glb>
const fs = require('fs'), zlib = require('zlib'), vm = require('vm');
const [threePath, buildPath, gender, outPath] = process.argv.slice(2);

// ---- a fake canvas, enough for the textures makePet() paints ----
function fakeCanvas() {
  const c = { width: 0, height: 0, data: null };
  c.getContext = () => ({
    createImageData: (w, h) => ({ width: w, height: h, data: new Uint8ClampedArray(w * h * 4) }),
    putImageData: (img) => { c.data = img; },
    createRadialGradient: () => ({ addColorStop() {} }),
    beginPath() {}, moveTo() {}, lineTo() {}, stroke() {}, fillRect() {}, fill() {}, closePath() {},
    save() {}, restore() {}, translate() {}, rotate() {}, fillText() {}, strokeText() {},
  });
  return c;
}
const sandbox = { console, Math, Date, Float32Array, Uint8ClampedArray, Uint16Array, Uint32Array, Int32Array, Uint8Array, Array, Object, Number, JSON,
  document: { createElement: () => fakeCanvas(), createElementNS: () => fakeCanvas() }, self: {}, window: {} };
sandbox.globalThis = sandbox; vm.createContext(sandbox);
vm.runInContext(fs.readFileSync(threePath, 'utf8'), sandbox);
const prelude = `const mk=(w,h)=>{const c=document.createElement('canvas');c.width=w;c.height=h;return c;};
const V=(x=0,y=0,z=0)=>new THREE.Vector3(x,y,z); const rnd=(a,b)=>a+Math.random()*(b-a); const pick=a=>a[0];
const scene=new THREE.Scene();`;
vm.runInContext(prelude + fs.readFileSync(buildPath, 'utf8') + `\n;globalThis.PET=makePet(${JSON.stringify(gender)});globalThis.NOISE_IMG=NOISE.image;`, sandbox);
const THREE = sandbox.THREE, P = sandbox.PET;

// ---- fur: keep the shell look but with fewer shells (phone GPU) ----
const KEEP = 5;
function thinFur(obj) {
  const shells = obj.children.filter(c => c.isMesh && P.fur.includes(c.material));
  if (shells.length > KEEP) { const keep = new Set([...Array(KEEP)].map((_, k) => Math.round(k * (shells.length - 1) / (KEEP - 1)))); shells.forEach((m, i) => { if (!keep.has(i)) m.visible = false; }); }
  obj.children.forEach(thinFur);
}
thinFur(P.root);

// ---- glTF writer ----
const gltf = { asset: { version: '2.0', generator: 'pufi-export' }, scene: 0, scenes: [{ nodes: [] }], nodes: [], meshes: [], materials: [], accessors: [], bufferViews: [], buffers: [], textures: [], images: [], samplers: [], animations: [], extensionsUsed: [] };
const chunks = []; let byteLen = 0;
function addView(buf, target) { while (byteLen % 4) { chunks.push(Buffer.alloc(1)); byteLen++; } const v = { buffer: 0, byteOffset: byteLen, byteLength: buf.length }; if (target) v.target = target; chunks.push(buf); byteLen += buf.length; gltf.bufferViews.push(v); return gltf.bufferViews.length - 1; }
function addAccessor(arr, type, compType, target, minmax) {
  const buf = Buffer.from(arr.buffer, arr.byteOffset, arr.byteLength); const view = addView(Buffer.from(buf), target);
  const n = { SCALAR: 1, VEC2: 2, VEC3: 3, VEC4: 4 }[type]; const a = { bufferView: view, componentType: compType, count: arr.length / n, type };
  if (minmax) { const mn = Array(n).fill(Infinity), mx = Array(n).fill(-Infinity); for (let i = 0; i < arr.length; i++) { const k = i % n; mn[k] = Math.min(mn[k], arr[i]); mx[k] = Math.max(mx[k], arr[i]); } a.min = mn; a.max = mx; }
  gltf.accessors.push(a); return gltf.accessors.length - 1;
}
// noise alpha texture (white RGB, alpha = fur strands)
function png(w, h, rgba) {
  const raw = Buffer.alloc((w * 4 + 1) * h); for (let y = 0; y < h; y++) { raw[y * (w * 4 + 1)] = 0; Buffer.from(rgba.buffer, y * w * 4, w * 4).copy(raw, y * (w * 4 + 1) + 1); }
  const crcT = []; for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; crcT[n] = c >>> 0; }
  const crc = b => { let c = 0xffffffff; for (const x of b) c = crcT[(c ^ x) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const chunk = (t, d) => { const l = Buffer.alloc(4); l.writeUInt32BE(d.length); const td = Buffer.concat([Buffer.from(t), d]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([l, td, c]); };
  const ih = Buffer.alloc(13); ih.writeUInt32BE(w, 0); ih.writeUInt32BE(h, 4); ih[8] = 8; ih[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ih), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
}
const ni = sandbox.NOISE_IMG.data; const nw = ni.width, nh = ni.height, rgba = new Uint8Array(nw * nh * 4);
for (let i = 0; i < nw * nh; i++) { rgba[i * 4] = rgba[i * 4 + 1] = rgba[i * 4 + 2] = 255; rgba[i * 4 + 3] = ni.data[i * 4]; }
gltf.images.push({ bufferView: addView(png(nw, nh, rgba)), mimeType: 'image/png' });
gltf.samplers.push({ magFilter: 9728, minFilter: 9729, wrapS: 10497, wrapT: 10497 });
gltf.textures.push({ source: 0, sampler: 0 });

const matIdx = new Map();
function material(m) {
  if (matIdx.has(m)) return matIdx.get(m);
  const c = m.color; const g = { name: m.type, pbrMetallicRoughness: { baseColorFactor: [c.r, c.g, c.b, m.transparent ? m.opacity : 1], metallicFactor: m.metalness ?? 0, roughnessFactor: m.roughness ?? 1 }, doubleSided: m.side === THREE.DoubleSide };
  if (m.isMeshBasicMaterial) { g.extensions = { KHR_materials_unlit: {} }; if (!gltf.extensionsUsed.includes('KHR_materials_unlit')) gltf.extensionsUsed.push('KHR_materials_unlit'); }
  if (m.alphaMap) { g.pbrMetallicRoughness.baseColorTexture = { index: 0 }; g.alphaMode = 'MASK'; g.alphaCutoff = m.alphaTest; }
  else if (m.transparent) g.alphaMode = 'BLEND';
  gltf.materials.push(g); matIdx.set(m, gltf.materials.length - 1); return gltf.materials.length - 1;
}
const geoIdx = new Map();
function geometry(geo) {
  if (geoIdx.has(geo)) return geoIdx.get(geo);
  const pos = geo.attributes.position.array, nor = geo.attributes.normal.array, uv = geo.attributes.uv && geo.attributes.uv.array;
  const attrs = { POSITION: addAccessor(new Float32Array(pos), 'VEC3', 5126, 34962, true), NORMAL: addAccessor(new Float32Array(nor), 'VEC3', 5126, 34962) };
  if (uv) { const u = new Float32Array(uv); for (let i = 1; i < u.length; i += 2) u[i] = 1 - u[i]; attrs.TEXCOORD_0 = addAccessor(u, 'VEC2', 5126, 34962); }
  let idx = geo.index ? geo.index.array : Uint32Array.from({ length: pos.length / 3 }, (_, i) => i);
  const r = { attrs, indices: addAccessor(pos.length / 3 < 65536 ? new Uint16Array(idx) : new Uint32Array(idx), 'SCALAR', pos.length / 3 < 65536 ? 5123 : 5125, 34963) };
  geoIdx.set(geo, r); return r;
}
const nodeOf = new Map();
function node(o) {
  if (!o.visible || o === P.shadow) return null;
  const n = { name: o.name || undefined };
  const t = o.position, q = o.quaternion, s = o.scale;
  if (t.x || t.y || t.z) n.translation = [t.x, t.y, t.z];
  if (q.x || q.y || q.z || q.w !== 1) n.rotation = [q.x, q.y, q.z, q.w];
  if (s.x !== 1 || s.y !== 1 || s.z !== 1) n.scale = [s.x, s.y, s.z];
  if (o.isMesh) { const g = geometry(o.geometry); gltf.meshes.push({ primitives: [{ attributes: g.attrs, indices: g.indices, material: material(o.material) }] }); n.mesh = gltf.meshes.length - 1; }
  gltf.nodes.push(n); const i = gltf.nodes.length - 1; nodeOf.set(o, i);
  const kids = o.children.map(node).filter(k => k !== null); if (kids.length) gltf.nodes[i].children = kids;
  return i;
}
// names for the parts the walk animation moves
P.body.name = 'Body'; P.bodyG.name = 'Torso'; P.face.name = 'Face';
P.feet.forEach((f, i) => f.name = i ? 'FootR' : 'FootL'); P.arms.forEach((a, i) => a.name = i ? 'ArmR' : 'ArmL'); P.ears.forEach((e, i) => e.g.name = i ? 'EarR' : 'EarL');
P.mouths.smile.visible = true;   // the default face: open eyes and a smile
P.root.name = 'Pufi';
gltf.scenes[0].nodes.push(node(P.root));

// ---- walk loop (0.56 s): feet step, body bobs and waddles, arms swing, ears bounce ----
const T = 0.56, N = 17, times = Float32Array.from({ length: N }, (_, i) => T * i / (N - 1));
const anim = { name: 'walk', samplers: [], channels: [] };
function channel(o, path, fn) {
  const i = nodeOf.get(o); if (i === undefined) return;
  const n = path === 'rotation' ? 4 : 3, out = new Float32Array(N * n);
  times.forEach((t, k) => { const v = fn(2 * Math.PI * t / T); for (let j = 0; j < n; j++) out[k * n + j] = v[j]; });
  anim.samplers.push({ input: addAccessor(times, 'SCALAR', 5126, undefined, true), output: addAccessor(out, n === 4 ? 'VEC4' : 'VEC3', 5126), interpolation: 'LINEAR' });
  anim.channels.push({ sampler: anim.samplers.length - 1, target: { node: i, path } });
}
const quat = (o, ex, ey, ez) => { const q = new THREE.Quaternion().setFromEuler(new THREE.Euler(ex, ey, ez)); return [q.x, q.y, q.z, q.w]; };
const base = o => o.position.clone();
channel(P.body, 'translation', a => [0, Math.abs(Math.sin(a)) * 0.06, 0]);
channel(P.body, 'rotation', a => quat(P.body, 0.06, 0, Math.sin(a) * 0.09));
P.feet.forEach((f, s) => { const b = base(f), sg = s ? 1 : -1; channel(f, 'translation', a => [b.x, b.y + Math.max(0, Math.sin(a) * sg) * 0.07, b.z + Math.cos(a) * sg * 0.09]); });
P.arms.forEach((g, s) => { const sg = s ? 1 : -1; channel(g, 'rotation', a => quat(g, Math.cos(a) * sg * 0.55, 0, -sg * 0.15)); });
P.ears.forEach((e, s) => { const sg = s ? 1 : -1; channel(e.g, 'rotation', a => quat(e.g, -Math.abs(Math.sin(a)) * 0.12, 0, e.base + sg * Math.sin(2 * a) * 0.08)); });
gltf.animations.push(anim);

// ---- pack ----
for (const k of ['textures', 'images', 'samplers', 'animations', 'extensionsUsed']) if (!gltf[k].length) delete gltf[k];
while (byteLen % 4) { chunks.push(Buffer.alloc(1)); byteLen++; }
const bin = Buffer.concat(chunks); gltf.buffers.push({ byteLength: bin.length });
let json = Buffer.from(JSON.stringify(gltf)); json = Buffer.concat([json, Buffer.alloc((4 - json.length % 4) % 4, 0x20)]);
const head = Buffer.alloc(12); head.writeUInt32LE(0x46546c67, 0); head.writeUInt32LE(2, 4); head.writeUInt32LE(12 + 8 + json.length + 8 + bin.length, 8);
const ch = (len, type) => { const b = Buffer.alloc(8); b.writeUInt32LE(len, 0); b.writeUInt32LE(type, 4); return b; };
fs.writeFileSync(outPath, Buffer.concat([head, ch(json.length, 0x4e4f534a), json, ch(bin.length, 0x004e4942), bin]));
console.log(outPath, (bin.length / 1024).toFixed(0) + ' KB bin,', gltf.nodes.length, 'nodes,', gltf.meshes.length, 'meshes,', gltf.materials.length, 'materials');
