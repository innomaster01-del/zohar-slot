// AR scratch card: 21 × 30 cm ticket lying on the table (front edge toward the player, +Z), gold rim, live face texture.
// Named: CardFace (material CardImage, replaced at runtime by the scratch renderer), CardRim, CardGlow.
export async function build(THREE, kit) {
  const g = new THREE.Group(); g.name = 'ScratchCard';
  const W = 0.21, H = 0.30;
  const rim = new THREE.MeshPhysicalMaterial({ name: 'CardRim', color: 0xf2c44e, metalness: 1, roughness: 0.22, clearcoat: 1, clearcoatRoughness: 0.05 });
  const back = new THREE.MeshStandardMaterial({ name: 'CardBack', color: 0x2a0b4a, roughness: 0.6 });
  const body = kit.roundedBox(W + 0.008, 0.004, H + 0.008, 0.006, rim, { name: 'CardRim', pos: [0, 0.002, 0] });
  g.add(body);
  g.add(kit.box(W - 0.002, 0.0006, H - 0.002, back, { name: 'CardBack', pos: [0, 0.0003, 0] }));
  // face: unlit image, placeholder art until the app uploads the real card
  const tex = kit.canvasTexture(512, 732, (c, w, h) => {
    const l = c.createLinearGradient(0, 0, 0, h); l.addColorStop(0, '#3a0a66'); l.addColorStop(1, '#12031f'); c.fillStyle = l; c.fillRect(0, 0, w, h);
    c.fillStyle = '#c9ccd6'; c.fillRect(40, 190, w - 80, 380);
    c.fillStyle = '#ffd34d'; c.font = '900 64px Georgia, serif'; c.textAlign = 'center'; c.fillText('ZOHAR', w / 2, 110); c.font = '700 30px Arial'; c.fillText('SCRATCH', w / 2, 150);
  });
  const face = new THREE.Mesh(new THREE.PlaneGeometry(W - 0.004, H - 0.004), new THREE.MeshBasicMaterial({ name: 'CardImage', map: tex, toneMapped: false }));
  face.rotation.x = -Math.PI / 2; face.position.y = 0.0042; face.name = 'CardFace'; g.add(face);
  // soft glow on the table around the card
  const glowTex = kit.canvasTexture(256, 256, (c, w, h) => { const r = c.createRadialGradient(w / 2, h / 2, w * 0.2, w / 2, h / 2, w / 2); r.addColorStop(0, 'rgba(255,200,80,0.45)'); r.addColorStop(1, 'rgba(255,170,40,0)'); c.fillStyle = r; c.fillRect(0, 0, w, h); });
  const glow = new THREE.Mesh(new THREE.PlaneGeometry(W * 2.1, H * 1.8), new THREE.MeshBasicMaterial({ name: 'CardGlow', map: glowTex, transparent: true, depthWrite: false, toneMapped: false }));
  glow.rotation.x = -Math.PI / 2; glow.position.y = 0.0005; glow.name = 'CardGlow'; g.add(glow);
  g.traverse((o) => { if (o.isMesh) { o.castShadow = o.name === 'CardRim'; o.receiveShadow = false; } });
  return g;
}
