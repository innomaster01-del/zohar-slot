// build entry for build_model.py → the AR app's cabinet GLB (screen shows a settled win; the app replaces it live)
import { ZOHAR, VideoSlotEngine, seededRng } from './video-slot-engine.js';
import { createReelScreen } from './reels-screen.js';
import { buildRealCabinet } from './real-cabinet.js';

export async function build(THREE, kit) {
  const engine = new VideoSlotEngine(ZOHAR, { credits: 2000, rng: seededRng(9) });
  const screen = createReelScreen({ cfg: ZOHAR, width: 720, height: 1200 });
  let r; for (let i = 0; i < 400; i++) { r = engine.spin(); if (r.lineWins.length >= 2 && r.mode === 'base' && !r.fsStarted) break; }
  screen.setHud({ credits: engine.credits, bet: engine.totalBetAmount });
  screen.startSpin(r); while (screen.busy) screen.update(1 / 30, { draw: false }); for (let k = 0; k < 20; k++) screen.update(1 / 30, { draw: false }); screen.update(0.01);
  const { root, animations } = buildRealCabinet(THREE, kit, { screenCanvas: screen.canvas });
  return { scene: root, animations };
}
