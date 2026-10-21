package com.zohar.slotar

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.unit.dp
import com.zohar.slotar.ar.LeverGesture
import com.zohar.slotar.ar.MachineController
import com.zohar.slotar.audio.SlotSounds
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.utils.screenToRay
import io.github.sceneview.applyRenderQuality
import io.github.sceneview.utils.worldToScreen
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.sin

/**
 * The same machine without AR (phones without ARCore, and the CI smoke test on an emulator):
 * orbit with a finger, play with the buttons. Effects play around the machine too.
 */
@Composable
fun PreviewScreen(smoke: Boolean = false, onAr: (() -> Unit)? = null) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val sounds = remember { SlotSounds(context) }
    val fxTokens = remember { mutableStateMapOf<Fx, Int>() }
    val fxActive = remember { mutableStateMapOf<Fx, Boolean>() }
    val game = remember { SlotGame(sounds) { fx -> fxTokens[fx] = (fxTokens[fx] ?: 0) + 1 } }
    val gesture = remember { LeverGesture() }
    val cabinet = rememberModelInstance(modelLoader, "models/cabinet.glb")
    val machine = remember(cabinet) { cabinet?.let { MachineController(engine, it) } }
    DisposableEffect(machine) { onDispose { machine?.destroy() } }
    DisposableEffect(Unit) { onDispose { sounds.close() } }
    val last = remember { longArrayOf(0L, 0L, 0L) }      // last frame ns, frames, perf window start
    val needUpload = remember { booleanArrayOf(true) }
    val view = io.github.sceneview.rememberView(engine).also { v -> if (smoke) remember(v) { v.applyRenderQuality(io.github.sceneview.RenderQuality.Performance); v.setShadowingEnabled(false); 0 } }
    val collisionSystem = io.github.sceneview.rememberCollisionSystem(view)
    val downAt = remember { floatArrayOf(-1f, -1f) }
    val tapRef = remember { arrayOfNulls<(Float, Float) -> Unit>(1) }
    fun tap(x: Float, y: Float): String? {
        val m = machine ?: return null
        val viaScene = runCatching { collisionSystem.hitTest(x, y) }.getOrDefault(emptyList()).firstNotNullOfOrNull { m.partOf(it.nodeOrNull) }
        val viaBoxes = view.screenToRay(x, y)?.let { r -> m.pick(floatArrayOf(r.origin.x, r.origin.y, r.origin.z), floatArrayOf(r.direction.x, r.direction.y, r.direction.z)) }
        val best = (viaScene ?: viaBoxes)?.let { if (it == "ScreenGlass") "Screen" else it }
        Log.i("ZoharTap", "preview tap (${x.toInt()},${y.toInt()}) scene=$viaScene boxes=$viaBoxes → $best")
        best?.let { if (it in MachineController.BUTTONS) m.press(it) }
        when (best) {
            "SpinButton", "Screen" -> { if (!game.busy) { gesture.autoPull(SystemClock.uptimeMillis()); sounds.play("lever", 0.6f) }; game.spin() }
            "BetDownButton" -> game.bet(-1); "BetUpButton" -> game.bet(+1); "MaxBetButton" -> game.bet(99); "AutoButton" -> game.toggleAuto()
            "LeverKnob", "LeverShaft" -> { gesture.autoPull(SystemClock.uptimeMillis()); sounds.play("lever"); game.spin() }
        }
        return best
    }
    DisposableEffect(Unit) {
        TouchBus.handler = { e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN || e.actionMasked == android.view.MotionEvent.ACTION_UP) Log.i("ZoharTouch", "preview ${e.actionMasked} ${e.x.toInt()},${e.y.toInt()}")
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downAt[0] = e.x; downAt[1] = e.y }
                android.view.MotionEvent.ACTION_UP -> if (kotlin.math.hypot(e.x - downAt[0], e.y - downAt[1]) < 40f) tapRef[0]?.invoke(e.x, e.y)
            }
        }
        onDispose { TouchBus.handler = null; TouchBus.blocked.clear() }
    }
    tapRef[0] = { x, y -> tap(x, y) }
    val cameraNode = rememberCameraNode(engine) { position = Position(1.15f, 1.5f, 3.4f); lookAt(Position(0f, 1.05f, 0f)) }

    for (fx in Fx.entries) {
        val tok = fxTokens[fx] ?: 0
        LaunchedEffect(fx, tok, game.inFree) {
            if (tok == 0) return@LaunchedEffect
            fxActive[fx] = true
            if (fx == Fx.FREE_SPINS && game.inFree) return@LaunchedEffect
            delay((FX_SECONDS.getValue(fx) * 1000).toLong()); fxActive[fx] = false
        }
    }
    if (smoke) LaunchedEffect(machine) {
        if (machine == null) return@LaunchedEffect
        Log.i("ZoharSmoke", "preview machineOk=${machine.ok}")
        delay(1500)
        for (b in MachineController.BUTTONS + listOf("LeverKnob")) machine.worldPos(b)?.let { w -> view.worldToScreen(Position(w[0], w[1], w[2]))?.let { p ->
            Log.i("ZoharSmoke", "btn $b ${p.x.toInt()} ${p.y.toInt()}")
            Log.i("ZoharSmoke", "selftap $b → ${tap(p.x, p.y)}")          // the same code path a finger tap takes
        } }
        // virtual-hand self-test on the real renderer: hand at the knob, projected to the screen, fist + pull down
        runCatching {
            val vh = com.zohar.slotar.ar.VirtualHand(0.85f)
            val cm = DoubleArray(16).also { view.camera!!.getModelMatrix(it) }
            val right = floatArrayOf(cm[0].toFloat(), cm[1].toFloat(), cm[2].toFloat()); val up = floatArrayOf(cm[4].toFloat(), cm[5].toFloat(), cm[6].toFloat()); val back = floatArrayOf(cm[8].toFloat(), cm[9].toFloat(), cm[10].toFloat())
            val knob = machine.worldPos("LeverKnob")!!
            var t = SystemClock.uptimeMillis(); var fired = false
            for (k in 0..40) {
                val palm = floatArrayOf(knob[0] + back[0] * 0.03f, knob[1] - k * 0.006f, knob[2] + back[2] * 0.03f)
                vh.onDetection(com.zohar.slotar.ar.VirtualHand.syntheticWorld(1f), palm, right, up, back, t); vh.step(1.0 / 30, t); t += 33
                if (vh.updateLever(knob, t)) fired = true
            }
            val onScreen = (0 until 21).count { i -> val j = vh.joints[i]; view.worldToScreen(Position(j[0], j[1], j[2]))?.let { p -> p.x in 0f..view.viewport.width.toFloat() && p.y in 0f..view.viewport.height.toFloat() } == true }
            Log.i("ZoharSmoke", "virtual hand: visible=${vh.visible} jointsOnScreen=$onScreen/21 closed=${vh.closed} leverFired=$fired")
        }.onFailure { Log.e("ZoharSmoke", "virtual hand self-test failed", it) }
        delay(2500)
        repeat(4) { i ->
            gesture.autoPull(SystemClock.uptimeMillis()); sounds.play("lever")
            val r = game.spin(); Log.i("ZoharSmoke", "preview spin ${i + 1}: $r")
            delay(4800)
            val res = game.engine.lastResult
            Log.i("ZoharSmoke", "preview spin ${i + 1} shown==engine: ${res != null && game.animator.visibleGrid() == res.grid} credits=${game.credits}")
        }
        for (fx in listOf(Fx.LIGHTNING, Fx.COINS, Fx.BIG_WIN, Fx.FREE_SPINS)) { fxTokens[fx] = (fxTokens[fx] ?: 0) + 1; Log.i("ZoharSmoke", "preview fx $fx"); delay(1500) }
        Log.i("ZoharSmoke", "preview done")
    }

    Box(Modifier.fillMaxSize()) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine, modelLoader = modelLoader, view = view, collisionSystem = collisionSystem,
            frameRatePolicy = if (smoke) FrameRatePolicy.OnDemand(maxFps = 6) else FrameRatePolicy.Continuous(),      // the screen texture / LEDs are raw Filament writes
            autoCenterContent = false,
            cameraNode = cameraNode,
            cameraManipulator = rememberCameraManipulator(orbitHomePosition = Position(1.15f, 1.5f, 3.4f), targetPosition = Position(0f, 1.05f, 0f)),
            onFrame = { ns ->
                val dt = if (last[0] == 0L) 1.0 / 60 else ((ns - last[0]) / 1e9).coerceIn(0.0, 0.1); last[0] = ns
                last[1]++; if (ns - last[2] > 5_000_000_000L) { if (last[2] > 0) Log.i("ZoharPerf", "preview fps=${last[1] / ((ns - last[2]) / 1e9)}"); last[1] = 0; last[2] = ns }
                if (game.update(dt)) needUpload[0] = true
                machine?.let { m ->
                    if (needUpload[0] && m.uploadScreen(game.renderer.bitmap)) needUpload[0] = false
                    gesture.relax(dt.toFloat())
                    val glow = if (!game.busy) (0.5 + 0.5 * sin(ns / 2.5e8)).toFloat() else 0.2f
                    m.update(dt, game.mood, MachineController.LEVER_REST + gesture.pull * (MachineController.LEVER_PULLED - MachineController.LEVER_REST), glow)
                }
            },
        ) {
            cabinet?.let { ModelNode(modelInstance = it, autoAnimate = false, apply = { machine?.attach(this) }) }
            for (fx in Fx.entries) {
                val tok = fxTokens[fx] ?: 0
                if (tok > 0 && fxActive[fx] == true) key(fx, tok) {
                    rememberModelInstance(modelLoader, FX_FILES.getValue(fx))?.let { ModelNode(modelInstance = it, autoAnimate = true, animationLoop = fx == Fx.FREE_SPINS) }
                }
            }
        }
        val nf = remember { NumberFormat.getIntegerInstance(Locale.US) }
        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Pill("NO-AR VIEW · CREDIT ${nf.format(game.credits)} · BET ${game.bet}" + (if (game.inFree) " · FREE SPINS ${game.freeLeft}" else ""))
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp)
            .onGloballyPositioned { c -> c.boundsInWindow().let { b -> TouchBus.blocked["controls"] = android.graphics.RectF(b.left, b.top, b.right, b.bottom) } }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Ctl("−", enabled = !game.busy && !game.inFree) { game.bet(-1) }
                Ctl(if (game.busy) "STOP" else "PULL LEVER", primary = true, wide = true) { if (!game.busy) { gesture.autoPull(SystemClock.uptimeMillis()); sounds.play("lever") }; game.spin() }
                Ctl("+", enabled = !game.busy && !game.inFree) { game.bet(+1) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Ctl(if (game.autoLeft > 0) "Stop auto" else "Auto", small = true) { game.toggleAuto() }
                onAr?.let { Ctl("Back to AR", small = true) { it() } }
            }
        }
    }
}
