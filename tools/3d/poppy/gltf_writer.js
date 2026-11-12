// Minimal GLB writer for three.js (r128) scenes: node hierarchy with TRS, meshes, PBR / unlit materials,
// one optional RGBA texture per material (material.userData.rgba = {width, height, data}), and baked animations.
const zlib = require('zlib');

function png(w, h, rgba) {
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) { raw[y * (w * 4 + 1)] = 0; Buffer.from(rgba.buffer, rgba.byteOffset + y * w * 4, w * 4).copy(raw, y * (w * 4 + 1) + 1); }
  const crcT = []; for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; crcT[n] = c >>> 0; }
  const crc = b => { let c = 0xffffffff; for (const x of b) c = crcT[(c ^ x) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const chunk = (t, d) => { const l = Buffer.alloc(4); l.writeUInt32BE(d.length); const td = Buffer.concat([Buffer.from(t), d]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([l, td, c]); };
  const ih = Buffer.alloc(13); ih.writeUInt32BE(w, 0); ih.writeUInt32BE(h, 4); ih[8] = 8; ih[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ih), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
}

/**
 * root: THREE.Object3D. opts.skip(o) → true to leave an object out. opts.hidden: Set of objects written with a tiny scale
 * (present but invisible; the app shows them by scaling to 1). opts.animations: [{name, duration, frames, tracks:[{object, path, fn(phase 0..2π) → array}]}].
 */
function writeGLB(THREE, root, opts = {}) {
  const gltf = { asset: { version: '2.0', generator: 'follow-poppy' }, scene: 0, scenes: [{ nodes: [] }], nodes: [], meshes: [], materials: [], accessors: [], bufferViews: [], buffers: [], textures: [], images: [], samplers: [], animations: [], extensionsUsed: [] };
  const chunks = []; let byteLen = 0;
  const addView = (buf, target) => { while (byteLen % 4) { chunks.push(Buffer.alloc(1)); byteLen++; } const v = { buffer: 0, byteOffset: byteLen, byteLength: buf.length }; if (target) v.target = target; chunks.push(buf); byteLen += buf.length; gltf.bufferViews.push(v); return gltf.bufferViews.length - 1; };
  function addAccessor(arr, type, compType, target, minmax) {
    const view = addView(Buffer.from(arr.buffer, arr.byteOffset, arr.byteLength), target);
    const n = { SCALAR: 1, VEC2: 2, VEC3: 3, VEC4: 4 }[type]; const a = { bufferView: view, componentType: compType, count: arr.length / n, type };
    if (minmax) { const mn = Array(n).fill(Infinity), mx = Array(n).fill(-Infinity); for (let i = 0; i < arr.length; i++) { const k = i % n; mn[k] = Math.min(mn[k], arr[i]); mx[k] = Math.max(mx[k], arr[i]); } a.min = mn; a.max = mx; }
    gltf.accessors.push(a); return gltf.accessors.length - 1;
  }
  const texIdx = new Map();
  function texture(rgba) {
    if (texIdx.has(rgba)) return texIdx.get(rgba);
    if (!gltf.samplers.length) gltf.samplers.push({ magFilter: 9729, minFilter: 9729, wrapS: 10497, wrapT: 10497 });
    gltf.images.push({ bufferView: addView(png(rgba.width, rgba.height, rgba.data)), mimeType: 'image/png' });
    gltf.textures.push({ source: gltf.images.length - 1, sampler: rgba.nearest ? (gltf.samplers.push({ magFilter: 9728, minFilter: 9729, wrapS: 10497, wrapT: 10497 }), gltf.samplers.length - 1) : 0 });
    texIdx.set(rgba, gltf.textures.length - 1); return gltf.textures.length - 1;
  }
  const matIdx = new Map();
  function material(m) {
    if (matIdx.has(m)) return matIdx.get(m);
    const c = m.color; const g = { name: m.name || m.type, pbrMetallicRoughness: { baseColorFactor: [c.r, c.g, c.b, m.transparent ? m.opacity : 1], metallicFactor: m.metalness ?? 0, roughnessFactor: m.roughness ?? 1 }, doubleSided: m.side === THREE.DoubleSide };
    if (m.isMeshBasicMaterial) { g.extensions = { KHR_materials_unlit: {} }; if (!gltf.extensionsUsed.includes('KHR_materials_unlit')) gltf.extensionsUsed.push('KHR_materials_unlit'); }
    if (m.emissive && (m.emissive.r || m.emissive.g || m.emissive.b)) g.emissiveFactor = [m.emissive.r * (m.emissiveIntensity ?? 1), m.emissive.g * (m.emissiveIntensity ?? 1), m.emissive.b * (m.emissiveIntensity ?? 1)].map(v => Math.min(1, v));
    if (m.userData.rgba) g.pbrMetallicRoughness.baseColorTexture = { index: texture(m.userData.rgba) };
    if (m.alphaTest > 0 && m.userData.rgba) { g.alphaMode = 'MASK'; g.alphaCutoff = m.alphaTest; }
    else if (m.transparent) g.alphaMode = 'BLEND';
    gltf.materials.push(g); matIdx.set(m, gltf.materials.length - 1); return gltf.materials.length - 1;
  }
  const geoIdx = new Map();
  function geometry(geo) {
    if (geoIdx.has(geo)) return geoIdx.get(geo);
    if (!geo.attributes.normal) geo.computeVertexNormals();
    const pos = geo.attributes.position.array, nor = geo.attributes.normal.array, uv = geo.attributes.uv && geo.attributes.uv.array;
    const attrs = { POSITION: addAccessor(new Float32Array(pos), 'VEC3', 5126, 34962, true), NORMAL: addAccessor(new Float32Array(nor), 'VEC3', 5126, 34962) };
    if (uv) { const u = new Float32Array(uv); for (let i = 1; i < u.length; i += 2) u[i] = 1 - u[i]; attrs.TEXCOORD_0 = addAccessor(u, 'VEC2', 5126, 34962); }
    const n = pos.length / 3, idx = geo.index ? geo.index.array : Uint32Array.from({ length: n }, (_, i) => i);
    const r = { attrs, indices: addAccessor(n < 65536 ? new Uint16Array(idx) : new Uint32Array(idx), 'SCALAR', n < 65536 ? 5123 : 5125, 34963) };
    geoIdx.set(geo, r); return r;
  }
  const nodeOf = new Map(); const skip = opts.skip || (() => false); const hidden = opts.hidden || new Set();
  function node(o) {
    if (skip(o)) return null;
    const n = {}; if (o.name) n.name = o.name;
    const t = o.position, q = o.quaternion, s = o.scale;
    if (t.x || t.y || t.z) n.translation = [t.x, t.y, t.z];
    if (q.x || q.y || q.z || q.w !== 1) n.rotation = [q.x, q.y, q.z, q.w];
    if (hidden.has(o)) n.scale = [1e-4, 1e-4, 1e-4];
    else if (s.x !== 1 || s.y !== 1 || s.z !== 1) n.scale = [s.x, s.y, s.z];
    if (o.isMesh) { const g = geometry(o.geometry); gltf.meshes.push({ primitives: [{ attributes: g.attrs, indices: g.indices, material: material(o.material) }] }); n.mesh = gltf.meshes.length - 1; }
    gltf.nodes.push(n); const i = gltf.nodes.length - 1; nodeOf.set(o, i);
    const kids = o.children.map(node).filter(k => k !== null); if (kids.length) gltf.nodes[i].children = kids;
    return i;
  }
  gltf.scenes[0].nodes.push(node(root));
  for (const an of opts.animations || []) {
    const N = an.frames, times = Float32Array.from({ length: N }, (_, i) => an.duration * i / (N - 1));
    const input = addAccessor(times, 'SCALAR', 5126, undefined, true);
    const a = { name: an.name, samplers: [], channels: [] };
    for (const tr of an.tracks) {
      const ni = nodeOf.get(tr.object); if (ni === undefined) continue;
      const k = tr.path === 'rotation' ? 4 : 3, out = new Float32Array(N * k);
      times.forEach((t, j) => { const v = tr.fn(2 * Math.PI * t / an.duration); for (let c = 0; c < k; c++) out[j * k + c] = v[c]; });
      a.samplers.push({ input, output: addAccessor(out, k === 4 ? 'VEC4' : 'VEC3', 5126), interpolation: 'LINEAR' });
      a.channels.push({ sampler: a.samplers.length - 1, target: { node: ni, path: tr.path } });
    }
    gltf.animations.push(a);
  }
  for (const k of ['textures', 'images', 'samplers', 'animations', 'extensionsUsed']) if (!gltf[k].length) delete gltf[k];
  while (byteLen % 4) { chunks.push(Buffer.alloc(1)); byteLen++; }
  const bin = Buffer.concat(chunks); gltf.buffers.push({ byteLength: bin.length });
  let json = Buffer.from(JSON.stringify(gltf)); json = Buffer.concat([json, Buffer.alloc((4 - json.length % 4) % 4, 0x20)]);
  const head = Buffer.alloc(12); head.writeUInt32LE(0x46546c67, 0); head.writeUInt32LE(2, 4); head.writeUInt32LE(12 + 8 + json.length + 8 + bin.length, 8);
  const ch = (len, type) => { const b = Buffer.alloc(8); b.writeUInt32LE(len, 0); b.writeUInt32LE(type, 4); return b; };
  return { glb: Buffer.concat([head, ch(json.length, 0x4e4f534a), json, ch(bin.length, 0x004e4942), bin]), gltf };
}

/** Loads three.js r128 (UMD build) into a sandbox with a fake canvas, so scene code that paints textures runs in Node. */
function loadThree(threePath) {
  const fs = require('fs'), vm = require('vm');
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
  return sandbox;
}

module.exports = { writeGLB, loadThree, png };
