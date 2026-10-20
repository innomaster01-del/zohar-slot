// video-slot.js — the playable machine: engine (decides results) + reel screen (shows them) + cabinet (3D) + sounds.
// Plugs into the webxr-ar-core / ar-ios-quicklook templates through the same hooks:
//   const slot = createVideoSlot(THREE, kit);  buildModel → slot.root;  app.onModelTap = slot.handleTap;  app.onFrame = slot.update;
// PLAY MONEY ONLY: credits are free and refill when empty; nothing can be bought or cashed out.
import { ZOHAR, VideoSlotEngine, cryptoRng } from './video-slot-engine.js';
import { createReelScreen } from './reels-screen.js';
import { buildCabinet } from './modern-cabinet.js';
import { createSlotSounds } from './slot-sounds.js';

export function createVideoSlot(THREE, kit, { cfg = ZOHAR, credits = 2000, rng = cryptoRng(), sounds = createSlotSounds(), onState } = {}) {
  const engine = new VideoSlotEngine(cfg, { credits, rng });
  let auto = 0, turbo = false, pending = null, mood = 'idle', moodT = 0;
  const hud = (extra = {}) => screen.setHud({ credits: engine.credits, bet: engine.totalBetAmount, inFree: engine.inFreeSpins, freeLeft: engine.freeLeft, freeTotal: engine.freeWon, ...extra });
  const screen = createReelScreen({ cfg, events: {
    spinStart: () => sounds.play('spinStart'),
    reelStop: (i, sc) => { sounds.play('reelStop', i, sc); if (i === 4) sounds.stopLoop(); },
    result: (r, kind) => {
      if (kind === 'fsIntro') { sounds.play('freeSpins'); setMood('free', 3); }
      else if (kind === 'big') { sounds.play('bigWin'); setMood('big', 4); }
      else if (r.totalWin > 0) { sounds.play('winSmall'); setMood('win', 1.5); }
    },
    fsOutro: () => { sounds.play('bigWin'); setMood('big', 3); },
    done: () => { hud(); onState?.(state()); if (auto > 0 || engine.inFreeSpins) pending = 0.45; },
  } });
  const cab = buildCabinet(THREE, kit, { screenCanvas: screen.canvas });
  hud();
  function setMood(m, secs) { mood = m; moodT = secs; }
  function spin() {
    if (screen.busy) { screen.slamStop(); return 'slam'; }
    if (!engine.canSpin()) { engine.refill(); hud({ message: 'קיבלת 2,000 קרדיטים חינם' }); sounds.play('noCredits'); auto = 0; return 'refill'; }
    const before = engine.credits;
    const r = engine.spin(); if (!r.ok) return 'no';
    if (auto > 0 && r.mode === 'base') auto--;
    hud({ credits: before - r.cost, message: '' });           // win is counted up on screen, credits settle on done
    screen.startSpin(r, { turbo }); cab.press('SpinButton'); onState?.(state()); return 'spin';
  }
  function bet(step) {
    if (screen.busy || engine.inFreeSpins) return false;
    engine.setBetIndex(step === 'max' ? cfg.lineBets.length - 1 : engine.betIndex + step); sounds.play('click'); hud(); onState?.(state()); return true;
  }
  function toggleAuto() { auto = auto > 0 ? 0 : 25; sounds.play('click'); if (auto && !screen.busy) spin(); onState?.(state()); return auto; }
  function handleTap(hit) {
    sounds.unlock();
    let o = hit.object, name = null;
    for (; o; o = o.parent) if (cab.buttons[o.name] || o.name === 'Screen' || o.name === 'ScreenGlass') { name = o.name === 'ScreenGlass' ? 'Screen' : o.name; break; }
    if (!name) return null;
    if (name !== 'Screen') cab.press(name);
    if (name === 'SpinButton' || name === 'Screen') return spin();
    if (name === 'BetDownButton') return bet(-1);
    if (name === 'BetUpButton') return bet(+1);
    if (name === 'MaxBetButton') return bet('max');
    if (name === 'AutoButton') return toggleAuto();
    return null;
  }
  function update(dt) {
    if (pending !== null && (pending -= dt) <= 0) { pending = null; if (!screen.busy && (auto > 0 || engine.inFreeSpins)) spin(); }
    if (screen.update(dt)) cab.screenTexture.needsUpdate = true;
    if ((moodT -= dt) <= 0) mood = screen.phase === 'spin' ? 'spin' : engine.inFreeSpins ? 'free' : 'idle';
    cab.update(dt, mood);
  }
  const state = () => ({ credits: engine.credits, bet: engine.totalBetAmount, inFree: engine.inFreeSpins, freeLeft: engine.freeLeft, auto, busy: screen.busy, turbo });
  return { root: cab.root, group: cab.root, engine, screen, cabinet: cab, sounds, handleTap, update, spin, bet, toggleAuto, state,
    set turbo(v) { turbo = !!v; }, get turbo() { return turbo; }, visibleGrid: () => screen.visibleGrid(), get busy() { return screen.busy; } };
}
