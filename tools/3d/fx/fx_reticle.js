// placement reticle: the cabinet's real footprint (0.69 × 0.66 m), a ring and an arrow toward the player (+Z)
export async function build(THREE) {
  const g = new THREE.Group(); g.name = 'Reticle';
  const m = new THREE.MeshBasicMaterial({ name: 'Reticle', color: 0x9fe6ff, transparent: true, opacity: 0.9, depthWrite: false, toneMapped: false, side: THREE.DoubleSide });
  const soft = new THREE.MeshBasicMaterial({ name: 'ReticleFill', color: 0x6a3cff, transparent: true, opacity: 0.22, depthWrite: false, toneMapped: false, side: THREE.DoubleSide });
  const flat = (geo, mat, y = 0.004) => { const o = new THREE.Mesh(geo, mat); o.rotation.x = -Math.PI / 2; o.position.y = y; g.add(o); return o; };
  const w = 0.69, d = 0.66, t = 0.012, z0 = -0.02;
  const fp = new THREE.Shape([new THREE.Vector2(-w / 2, -d / 2), new THREE.Vector2(w / 2, -d / 2), new THREE.Vector2(w / 2, d / 2), new THREE.Vector2(-w / 2, d / 2)]);
  fp.holes.push(new THREE.Path([new THREE.Vector2(-w / 2 + t, -d / 2 + t), new THREE.Vector2(-w / 2 + t, d / 2 - t), new THREE.Vector2(w / 2 - t, d / 2 - t), new THREE.Vector2(w / 2 - t, -d / 2 + t)]));
  const o = flat(new THREE.ShapeGeometry(fp), m); o.position.z = z0;
  const f = flat(new THREE.PlaneGeometry(w - 2 * t, d - 2 * t), soft, 0.003); f.position.z = z0;
  flat(new THREE.RingGeometry(0.06, 0.075, 48), m, 0.005);
  const arrow = new THREE.Shape([new THREE.Vector2(-0.08, 0), new THREE.Vector2(0.08, 0), new THREE.Vector2(0, -0.11)]);
  const a = flat(new THREE.ShapeGeometry(arrow), m, 0.005); a.position.z = z0 + d / 2 + 0.16;
  return g;
}
