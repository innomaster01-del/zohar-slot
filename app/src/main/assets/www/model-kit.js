// model-kit.js — helpers for building good-looking 3D models in three.js code, ready for GLB export.
// Conventions (same as the AR skills): METERS, +Y up, front faces +Z, pivot at bottom centre, named parts.
//
//   import * as THREE from 'three';
//   import { createKit } from './model-kit.js';
//   const kit = await createKit(THREE);
//   const g = kit.group('Lamp');
//   g.add(kit.lathe([[0,0],[0.08,0],[0.08,0.01],[0.02,0.03],[0.015,0.3]], kit.mat.metal('#c9a227'), { name: 'Stand' }));
//   kit.finalize(g, { height: 0.45 });     // exact real-world height, pivot bottom-centre
//
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js';
import { mergeGeometries, mergeVertices } from 'three/addons/utils/BufferGeometryUtils.js';

export async function createKit(THREE) {
  const deg = THREE.MathUtils.degToRad;

  // ---------- materials: cached, so identical looks share one material (fewer draw calls / materials) ----------
  const cache = new Map();
  const cached = (key, make) => { if (!cache.has(key)) cache.set(key, make()); return cache.get(key); };
  const col = (c) => new THREE.Color(c);
  const mat = {
    plastic: (c = '#e8e8e8', rough = 0.45) => cached(`plastic${c}${rough}`, () => new THREE.MeshStandardMaterial({ color: col(c), roughness: rough, metalness: 0, name: `Plastic_${c}` })),
    matte:   (c = '#cccccc') => cached(`matte${c}`, () => new THREE.MeshStandardMaterial({ color: col(c), roughness: 0.9, metalness: 0, name: `Matte_${c}` })),
    metal:   (c = '#c0c4c8', rough = 0.3) => cached(`metal${c}${rough}`, () => new THREE.MeshStandardMaterial({ color: col(c), roughness: rough, metalness: 1, name: `Metal_${c}` })),
    rubber:  (c = '#202020') => cached(`rubber${c}`, () => new THREE.MeshStandardMaterial({ color: col(c), roughness: 0.95, metalness: 0, name: `Rubber_${c}` })),
    // glTF has transmission (KHR_materials_transmission); Quick Look/USDZ ignores it, so glass falls back to transparent
    glass:   (c = '#ffffff', opacity = 0.35) => cached(`glass${c}${opacity}`, () => new THREE.MeshPhysicalMaterial({ color: col(c), roughness: 0.05, metalness: 0, transparent: true, opacity, name: `Glass_${c}` })),
    emissive:(c = '#ffcc33', strength = 2) => cached(`emis${c}${strength}`, () => new THREE.MeshStandardMaterial({ color: col(c), emissive: col(c), emissiveIntensity: strength, name: `Light_${c}` })),
    wood:    (c = '#9b6a3c', grain = '#6e4524') => cached(`wood${c}${grain}`, () => new THREE.MeshStandardMaterial({ map: woodTexture(c, grain), roughness: 0.7, name: `Wood_${c}` })),
    // any canvas drawing as a texture (labels, screens, logos). draw(ctx, w, h)
    painted: (key, w, h, draw, opts = {}) => cached(`paint${key}`, () => { const t = canvasTexture(w, h, draw); return new THREE.MeshStandardMaterial({ map: t, roughness: opts.rough ?? 0.6, metalness: opts.metal ?? 0, name: `Painted_${key}` }); }),
  };
  function canvasTexture(w, h, draw) {
    const c = document.createElement('canvas'); c.width = w; c.height = h; draw(c.getContext('2d'), w, h);
    const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; t.anisotropy = 4; return t;
  }
  function woodTexture(base, grain) {
    const t = canvasTexture(512, 512, (x, w, h) => {
      x.fillStyle = base; x.fillRect(0, 0, w, h);
      for (let i = 0; i < 70; i++) { // deterministic grain: same model → same texture every build
        const y = (i * 7.31) % h, a = 0.08 + ((i * 13) % 10) / 60;
        x.strokeStyle = grain; x.globalAlpha = a; x.lineWidth = 1 + (i % 3);
        x.beginPath(); x.moveTo(0, y); for (let px = 0; px <= w; px += 16) x.lineTo(px, y + Math.sin(px / 60 + i) * 4); x.stroke();
      }
      x.globalAlpha = 1;
    });
    t.wrapS = t.wrapT = THREE.RepeatWrapping; return t;
  }

  // ---------- shapes (all sizes in meters) ----------
  const mesh = (geo, material, o = {}) => {
    const m = new THREE.Mesh(geo, material);
    if (o.name) m.name = o.name;
    if (o.pos) m.position.set(...o.pos);
    if (o.rot) m.rotation.set(...o.rot.map(deg));          // degrees, XYZ
    if (o.scale) Array.isArray(o.scale) ? m.scale.set(...o.scale) : m.scale.setScalar(o.scale);
    m.castShadow = true; m.receiveShadow = true;
    return m;
  };
  const shapes = {
    // Real objects have rounded edges; sharp 90° boxes are what makes code models look fake.
    roundedBox: (w, h, d, r, material, o) => mesh(new RoundedBoxGeometry(w, h, d, 4, Math.min(r, Math.min(w, h, d) / 2 - 1e-4)), material, o),
    box: (w, h, d, material, o) => mesh(new THREE.BoxGeometry(w, h, d), material, o),
    cylinder: (rTop, rBottom, h, material, o = {}) => mesh(new THREE.CylinderGeometry(rTop, rBottom, h, o.segments ?? 48), material, o),
    sphere: (r, material, o = {}) => mesh(new THREE.SphereGeometry(r, o.segments ?? 48, Math.round((o.segments ?? 48) * 0.66)), material, o),
    torus: (r, tube, material, o = {}) => mesh(new THREE.TorusGeometry(r, tube, 24, o.segments ?? 64, o.arc ?? Math.PI * 2), material, o),
    // Profile [[radius, y], ...] from bottom to top, revolved around Y: bottles, vases, lamps, chess pieces.
    lathe: (profile, material, o = {}) => mesh(new THREE.LatheGeometry(profile.map(([r, y]) => new THREE.Vector2(Math.max(r, 0), y)), o.segments ?? 64), material, o),
    // 2D outline [[x,y],...] (or a THREE.Shape) pushed into depth along +Z with a soft bevel: logos, signs, cut-outs.
    extrude: (outline, depth, material, o = {}) => {
      const shape = outline instanceof THREE.Shape ? outline : new THREE.Shape(outline.map(([x, y]) => new THREE.Vector2(x, y)));
      for (const hole of o.holes ?? []) shape.holes.push(new THREE.Path(hole.map(([x, y]) => new THREE.Vector2(x, y))));
      const bevel = o.bevel ?? Math.min(depth * 0.25, 0.004);
      const geo = new THREE.ExtrudeGeometry(shape, { depth, bevelEnabled: bevel > 0, bevelThickness: bevel, bevelSize: bevel, bevelSegments: 3, curveSegments: o.curveSegments ?? 24 });
      geo.translate(0, 0, -depth / 2); return mesh(geo, material, o);
    },
    // Smooth tube through 3D points: handles, cables, pipes, bent rods.
    tube: (points, radius, material, o = {}) => {
      const curve = new THREE.CatmullRomCurve3(points.map((p) => new THREE.Vector3(...p)), !!o.closed, 'centripetal');
      return mesh(new THREE.TubeGeometry(curve, o.segments ?? 64, radius, o.radialSegments ?? 16, !!o.closed), material, o);
    },
  };

  // ---------- assembly ----------
  const group = (name, o = {}) => { const g = new THREE.Group(); g.name = name; if (o.pos) g.position.set(...o.pos); if (o.rot) g.rotation.set(...o.rot.map(deg)); return g; };

  // Mirror a part across X (left/right symmetric things: wheels, legs, handles).
  const mirrorX = (obj, name) => { const c = obj.clone(); c.position.x *= -1; c.rotation.y *= -1; c.rotation.z *= -1; if (name) c.name = name; return c; };

  // Merge every mesh that shares a material into one, EXCEPT parts whose names are in `keep`
  // (interactive/animated parts must stay separate). Cuts draw calls a lot for many-piece models.
  function mergeByMaterial(root, keep = []) {
    root.updateMatrixWorld(true);
    const keepSet = new Set(keep); const buckets = new Map(); const remove = [];
    root.traverse((o) => {
      if (!o.isMesh || o === root) return;
      for (let p = o; p && p !== root; p = p.parent) if (keepSet.has(p.name)) return;
      const g = o.geometry.clone().applyMatrix4(new THREE.Matrix4().copy(root.matrixWorld).invert().multiply(o.matrixWorld));
      // merge needs identical attribute sets: drop extras, make every geometry non-indexed-compatible
      for (const k of Object.keys(g.attributes)) if (!['position', 'normal', 'uv'].includes(k)) g.deleteAttribute(k);
      if (!g.attributes.uv) g.setAttribute('uv', new THREE.Float32BufferAttribute(new Float32Array(g.attributes.position.count * 2), 2));
      const ng = g.index ? g.toNonIndexed() : g;
      if (!buckets.has(o.material)) buckets.set(o.material, []);
      buckets.get(o.material).push(ng); remove.push(o);
    });
    for (const o of remove) o.removeFromParent();
    let n = 0;
    for (const [material, geos] of buckets) {
      const merged = mergeVertices(mergeGeometries(geos, false), 1e-5);
      const m = new THREE.Mesh(merged, material); m.name = `Merged_${material.name || n}`; m.castShadow = m.receiveShadow = true;
      root.add(m); n++;
    }
    return { mergedMeshes: remove.length, into: n };
  }

  // Exact real-world size + AR-ready pivot (bottom centre, base on y=0). Apply this last.
  function finalize(root, o = {}) {
    root.updateMatrixWorld(true);
    let box = new THREE.Box3().setFromObject(root);
    const size = box.getSize(new THREE.Vector3());
    const s = o.height ? o.height / size.y : o.width ? o.width / size.x : 1;
    const wrap = new THREE.Group(); wrap.name = root.name || 'Model';
    root.name = root.name ? `${root.name}_Parts` : 'Parts';
    wrap.add(root); root.scale.multiplyScalar(s);
    wrap.updateMatrixWorld(true); box = new THREE.Box3().setFromObject(root);
    const c = box.getCenter(new THREE.Vector3());
    root.position.x -= c.x; root.position.z -= c.z; root.position.y -= box.min.y;
    wrap.updateMatrixWorld(true);
    return wrap;
  }

  // Hebrew/Arabic in 3D text: TextGeometry lays glyphs out left-to-right, so RTL text comes out reversed.
  // visualOrder() converts logical order to display order (RTL runs reversed, numbers/Latin kept LTR).
  // Simplified Unicode bidi for one RTL paragraph (enough for labels, signs, prices):
  // strong R (Hebrew/Arabic), strong L (Latin letters, digits), neutrals (spaces, punctuation, symbols) take the
  // direction of their neighbours when both agree, otherwise the paragraph direction (RTL). Brackets mirror in RTL.
  function visualOrder(str) {
    const chars = [...str];
    const isRTL = (ch) => /[֐-ࣿיִ-﷿ﹰ-﻿]/.test(ch);
    const isLTR = (ch) => /[A-Za-z0-9À-ɏ]/.test(ch);
    if (!chars.some(isRTL)) return str;
    const t = chars.map((c) => (isRTL(c) ? 'R' : isLTR(c) ? 'L' : 'N'));
    for (let i = 0; i < t.length; i++) {
      if (t[i] !== 'N') continue;
      let j = i; while (j < t.length && t[j] === 'N') j++;
      const prev = i > 0 ? t[i - 1] : 'R', next = j < t.length ? t[j] : 'R';
      const dir = prev === next ? prev : 'R';
      for (let k = i; k < j; k++) t[k] = dir;
      i = j - 1;
    }
    const mirror = { '(': ')', ')': '(', '[': ']', ']': '[', '{': '}', '}': '{', '<': '>', '>': '<' };
    const runs = [];
    chars.forEach((c, i) => { const last = runs[runs.length - 1]; if (last && last.type === t[i]) last.chars.push(c); else runs.push({ type: t[i], chars: [c] }); });
    return runs.reverse().map((r) => (r.type === 'R' ? r.chars.reverse().map((c) => mirror[c] ?? c).join('') : r.chars.join(''))).join('');
  }

  // 3D extruded text from any TTF/OTF (Hebrew needs a font WITH Hebrew glyphs, e.g. Heebo, Rubik, Assistant).
  // Applies visualOrder() so RTL reads correctly. Sizes in meters. TextGeometry's own default depth is 50 (!),
  // so depth is always set here. align: 'center' | 'left' | 'right' (x), base sits on y=0.
  const fonts = new Map();
  async function text3d(text, fontUrl, material, o = {}) {
    if (!fonts.has(fontUrl)) {
      const [{ TTFLoader }, { Font }] = await Promise.all([import('three/addons/loaders/TTFLoader.js'), import('three/addons/loaders/FontLoader.js')]);
      fonts.set(fontUrl, new Font(await new TTFLoader().loadAsync(fontUrl)));
    }
    const { TextGeometry } = await import('three/addons/geometries/TextGeometry.js');
    const size = o.size ?? 0.05, depth = o.depth ?? size * 0.2, bevel = o.bevel ?? size * 0.03;
    const geo = new TextGeometry(o.raw ? text : visualOrder(text), { font: fonts.get(fontUrl), size, depth, curveSegments: o.curveSegments ?? 8,
      bevelEnabled: bevel > 0, bevelThickness: bevel, bevelSize: bevel * 0.6, bevelSegments: 2 });
    geo.computeBoundingBox(); const bb = geo.boundingBox;
    const dx = o.align === 'left' ? -bb.min.x : o.align === 'right' ? -bb.max.x : -(bb.min.x + bb.max.x) / 2;
    geo.translate(dx, -bb.min.y, -depth / 2);
    return mesh(geo, material, o);
  }

  return { THREE, mat, ...shapes, mesh, group, mirrorX, mergeByMaterial, finalize, canvasTexture, visualOrder, text3d, deg };
}
