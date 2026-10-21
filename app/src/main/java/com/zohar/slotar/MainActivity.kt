package com.zohar.slotar

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import com.zohar.slotar.ar.HandTracker
import com.zohar.slotar.ar.LeverGesture
import com.zohar.slotar.ar.MachineController
import com.zohar.slotar.audio.SlotSounds
import dev.romainguy.kotlin.math.Float2
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.scene.SceneUnderstanding
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode as ModelNodeImpl
import io.github.sceneview.createARView
import io.github.sceneview.rememberARView
import com.zohar.slotar.ar.ArProjector
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val smoke = intent?.getBooleanExtra("smoke", false) == true   // CI smoke test on the ARCore emulator
        val preview = intent?.getBooleanExtra("preview", false) == true
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var noAr by remember { mutableStateOf(preview) }
                if (noAr) PreviewScreen(smoke, onAr = if (preview) null else ({ noAr = false })) else ZoharAr(smoke, onNoAr = { noAr = true })
            }
        }
    }
}

private class Holder<T>(var value: T)

internal val FX_FILES = mapOf(Fx.LIGHTNING to "models/fx_lightning.glb", Fx.COINS to "models/fx_coins.glb", Fx.BIG_WIN to "models/fx_bigwin.glb", Fx.FREE_SPINS to "models/fx_freespins.glb")
internal val PART_LABELS = mapOf("SpinButton" to "SPIN", "Screen" to "SPIN", "BetDownButton" to "BET −", "BetUpButton" to "BET +", "MaxBetButton" to "MAX BET", "AutoButton" to "AUTO", "LeverKnob" to "LEVER", "LeverShaft" to "LEVER")
internal val FX_SECONDS = mapOf(Fx.LIGHTNING to 1.9, Fx.COINS to 3.1, Fx.BIG_WIN to 4.1, Fx.FREE_SPINS to 4.0)

@Composable
fun ZoharAr(smoke: Boolean = false, onNoAr: () -> Unit = {}) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    // AR view tuned for a glossy, lit cabinet: 4× MSAA (no jagged edges), soft bloom on LEDs/screen, ambient occlusion
    val view = rememberARView(engine) {
        createARView(engine).apply {
            multiSampleAntiAliasingOptions = multiSampleAntiAliasingOptions.apply { enabled = true; sampleCount = 4 }
            bloomOptions = bloomOptions.apply { enabled = true; strength = 0.12f; threshold = true }
            ambientOcclusionOptions = ambientOcclusionOptions.apply { enabled = true; radius = 0.25f; intensity = 0.8f }
        }
    }
    val projector = remember { ArProjector() }

    val sounds = remember { SlotSounds(context) }
    val fxTokens = remember { mutableStateMapOf<Fx, Int>() }
    val game = remember { SlotGame(sounds) { fx -> fxTokens[fx] = (fxTokens[fx] ?: 0) + 1 } }
    val gesture = remember { LeverGesture() }

    val cabinet = rememberModelInstance(modelLoader, "models/cabinet.glb")
    val reticleModel = rememberModelInstance(modelLoader, "models/reticle.glb")
    val machine = remember(cabinet) { cabinet?.let { MachineController(engine, it) } }
    DisposableEffect(machine) { onDispose { machine?.destroy() } }

    var anchor by remember { mutableStateOf<Anchor?>(null) }
    var anchorPlane by remember { mutableStateOf<Plane?>(null) }
    var canPlace by remember { mutableStateOf(false) }
    var tracking by remember { mutableStateOf(false) }
    var viewW by remember { mutableIntStateOf(0) }
    var viewH by remember { mutableIntStateOf(0) }
    var handPt by remember { mutableStateOf<Offset?>(null) }
    var knobPt by remember { mutableStateOf<Offset?>(null) }
    var grabbed by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf(true) }
    var muted by remember { mutableStateOf(false) }
    var handsOn by remember { mutableStateOf(true) }
    var tapFx by remember { mutableStateOf<Triple<Float, Float, Boolean>?>(null) }
    var tapLabel by remember { mutableStateOf("") }
    var tabletop by remember { mutableStateOf(false) }
    var yawOffset by remember { mutableFloatStateOf(0f) }
    val slop = remember { android.view.ViewConfiguration.get(context).scaledTouchSlop * 2f }
    val cabinetNode = remember { Holder<ModelNodeImpl?>(null) }
    var occlusionOn by remember { mutableStateOf(false) }      // depth occlusion cuts big objects on many phones → off by default
        var handStatus by remember { mutableStateOf("") }
    var scanSecs by remember { mutableFloatStateOf(0f) }
    var instantMode by remember { mutableStateOf(false) }
    val leverDeg = remember { Holder(MachineController.LEVER_REST) }

    val lastFrame = remember { Holder<Frame?>(null) }
    val lastTs = remember { Holder(0L) }
    val reticleRef = remember { Holder<ModelNodeImpl?>(null) }
    val candidate = remember { Holder<Pair<Pose, com.google.ar.core.Trackable>?>(null) }
    val smooth = remember { Holder<FloatArray?>(null) }
    val needUpload = remember { Holder(true) }
    val knob = remember { Holder<Triple<Float, Float, Float>?>(null) }   // screen x, y, grab radius
    val throwPx = remember { Holder(200f) }
    val downAt = remember { Holder<Offset?>(null) }
    val perf = remember { Holder(longArrayOf(0, 0)) }
    val smokeStart = remember { Holder(0L) }

    val vibrator = remember { context.getSystemService(android.os.Vibrator::class.java) }
    fun buzz(ms: Long) { runCatching { vibrator?.vibrate(android.os.VibrationEffect.createOneShot(ms, 120)) } }
    fun pullSpin() { sounds.play("lever"); game.spin() }
    /** Any spin from a button also throws the lever, like a real machine. */
    fun spinWithLever() { val r = game.spin(); if (r == "spin") { gesture.autoPull(SystemClock.uptimeMillis()); sounds.play("lever", 0.6f); buzz(25) } }

    val handSmooth = remember { Holder<FloatArray?>(null) }
    val hands = remember {
        HandTracker(context) { hand ->
            val now = SystemClock.uptimeMillis()
            val k = knob.value
            if (hand == null || viewW == 0) { handPt = null; handSmooth.value = null; handStatus = "Looking for your hand…"; gesture.onLost(now); return@HandTracker }
            val pts = hand.points
            var minX = 1f; var maxX = 0f; for (i in 0 until 21) { minX = minOf(minX, pts[2 * i]); maxX = maxOf(maxX, pts[2 * i]) }
            val tooClose = maxX - minX > 0.55f
            // the point of the hand nearest the knob does the grabbing (works for a finger, a pinch or the whole hand)
            var bx = pts[16]; var by = pts[17]
            if (k != null) {
                var bd = Float.MAX_VALUE
                for (idx in intArrayOf(4, 8, 12, 9)) { val px = pts[2 * idx] * viewW; val py = pts[2 * idx + 1] * viewH; val d = hypot(px - k.first, py - k.second); if (d < bd) { bd = d; bx = pts[2 * idx]; by = pts[2 * idx + 1] } }
            }
            val sm = handSmooth.value?.let { o -> floatArrayOf(o[0] + (bx - o[0]) * 0.6f, o[1] + (by - o[1]) * 0.6f) } ?: floatArrayOf(bx, by)
            handSmooth.value = sm
            val x = sm[0] * viewW; val y = sm[1] * viewH
            handPt = Offset(x, y)
            handStatus = when {
                gesture.state == LeverGesture.State.GRABBED -> "Got the lever · pull down"
                tooClose -> "Hand too close · hold it a little further from the phone"
                else -> "Hand found · touch the red ball on the lever"
            }
            if (k == null) return@HandTracker
            if (gesture.onPointer(x, y, k.first, k.second, k.third * 1.3f, throwPx.value, now)) { pullSpin(); buzz(40) }
        }
    }
    DisposableEffect(Unit) { onDispose { hands.close(); sounds.close() } }

    // project a world point (x,y,z) to screen pixels
    fun screenOf(p: FloatArray?): Float2? = projector.toScreen(p)?.let { Float2(it[0], it[1]) }

    fun place() {
        val c = candidate.value ?: return
        anchor?.detach()
        anchor = runCatching { c.second.createAnchor(c.first) }.getOrNull() ?: return
        anchorPlane = c.second as? Plane; needUpload.value = true; sounds.play("click"); hint = true
    }

    fun handleTap(x: Float, y: Float) {
        if (anchor == null) { place(); return }
        val m = machine ?: return
        val ray = projector.ray(x, y) ?: return
        val best = m.pick(ray.first, ray.second)
        Log.i("ZoharTap", "tap ($x,$y) → $best")
        tapFx = Triple(x, y, best != null); tapLabel = best?.let { PART_LABELS[it] } ?: ""
        best?.let { if (it in MachineController.BUTTONS) m.press(it) }
        when (best) {
            "SpinButton", "Screen" -> spinWithLever()
            "BetDownButton" -> game.bet(-1)
            "BetUpButton" -> game.bet(+1)
            "MaxBetButton" -> game.bet(99)
            "AutoButton" -> game.toggleAuto()
            "LeverKnob", "LeverShaft" -> { gesture.autoPull(SystemClock.uptimeMillis()); pullSpin() }
        }
    }

    // fx lifetimes (free spins loop while the feature runs)
    val fxActive = remember { mutableStateMapOf<Fx, Boolean>() }
    for (fx in Fx.entries) {
        val tok = fxTokens[fx] ?: 0
        LaunchedEffect(fx, tok, game.inFree) {
            if (tok == 0) return@LaunchedEffect
            fxActive[fx] = true
            if (fx == Fx.FREE_SPINS && game.inFree) return@LaunchedEffect
            delay((FX_SECONDS.getValue(fx) * 1000).toLong()); fxActive[fx] = false
        }
    }
    LaunchedEffect(anchor) { if (anchor != null) { delay(9000); hint = false } }
    if (smoke) LaunchedEffect(anchor) {
        if (anchor == null) return@LaunchedEffect
        delay(2500)
        repeat(5) { i ->
            val r = game.spin(); Log.i("ZoharSmoke", "spin ${i + 1}: $r credits=${game.credits}")
            delay(4500)
            val res = game.engine.lastResult
            Log.i("ZoharSmoke", "spin ${i + 1} shown==engine: ${res != null && game.animator.visibleGrid() == res.grid} busy=${game.busy}")
        }
        for (fx in listOf(Fx.LIGHTNING, Fx.COINS, Fx.BIG_WIN, Fx.FREE_SPINS)) { fxTokens[fx] = (fxTokens[fx] ?: 0) + 1; Log.i("ZoharSmoke", "fx $fx"); delay(1200) }
        gesture.autoPull(SystemClock.uptimeMillis()); Log.i("ZoharSmoke", "lever pulled")
        delay(600); Log.i("ZoharSmoke", "done")
    }

    Box(Modifier.fillMaxSize().onSizeChanged { viewW = it.width; viewH = it.height }) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine, modelLoader = modelLoader, materialLoader = materialLoader, view = view,
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL,
            depthMode = Config.DepthMode.AUTOMATIC,
            instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP,
            sceneUnderstanding = SceneUnderstanding(occlusion = occlusionOn, lighting = true, physics = false, planeVisualization = anchor == null),
            onSessionUpdated = { session, frame ->
                lastFrame.value = frame
                projector.update(frame, viewW, viewH)
                val ts = frame.timestamp; val dt = if (lastTs.value == 0L) 1.0 / 30 else ((ts - lastTs.value) / 1e9).coerceIn(0.0, 0.1); lastTs.value = ts
                tracking = frame.camera.trackingState == TrackingState.TRACKING
                perf.value.let { pf -> pf[0]++; if (ts - pf[1] > 5_000_000_000L) { if (pf[1] > 0) Log.i("ZoharPerf", "fps=${pf[0] / ((ts - pf[1]) / 1e9)} tracking=$tracking placed=${anchor != null}"); pf[0] = 0; pf[1] = ts } }
                if (smoke && anchor == null && tracking && smokeStart.value.let { if (it == 0L) { smokeStart.value = ts; false } else ts - it > 3_000_000_000L }) {
                    // no plane needed: 1.8 m in front of the camera, 1.3 m below it, facing the camera
                    val cam = frame.camera.pose; val fz = cam.zAxis
                    val px = cam.tx() - fz[0] * 1.8f; val pz = cam.tz() - fz[2] * 1.8f; val py = cam.ty() - 1.3f
                    val yaw = atan2((cam.tx() - px).toDouble(), (cam.tz() - pz).toDouble())
                    anchor = runCatching { session.createAnchor(Pose(floatArrayOf(px, py, pz), floatArrayOf(0f, sin(yaw / 2).toFloat(), 0f, cos(yaw / 2).toFloat()))) }
                        .onFailure { Log.e("ZoharSmoke", "anchor failed", it) }.getOrNull()
                    Log.i("ZoharSmoke", "placed=${anchor != null} machineOk=${machine?.ok}")
                }
                if (anchor == null) {
                    // centre-screen floor hit → reticle showing the machine's real footprint, facing the player
                    // 1) floor plane under the aim point (inside its outline)  2) the same plane extended beyond the outline
                    // 3) after 6 s of searching: ARCore's instant placement (refines itself as tracking improves)
                    if (tracking) scanSecs += dt.toFloat()
                    val ax = viewW / 2f; val ay = viewH * 0.62f
                    val hits = if (viewW > 0) frame.hitTest(ax, ay) else emptyList()
                    fun floor(h: com.google.ar.core.HitResult, inside: Boolean): Boolean {
                        val p = h.trackable as? Plane ?: return false
                        return p.type == Plane.Type.HORIZONTAL_UPWARD_FACING && p.trackingState == TrackingState.TRACKING && h.distance < 6f &&
                            (!inside || p.isPoseInPolygon(h.hitPose)) && p.subsumedBy == null
                    }
                    var hit = hits.firstOrNull { floor(it, true) } ?: hits.firstOrNull { floor(it, false) }
                    instantMode = false
                    if (hit == null && scanSecs > 6f && viewW > 0) {
                        hit = frame.hitTestInstantPlacement(ax, ay, 1.8f).firstOrNull(); instantMode = hit != null
                    }
                    if (hit != null) {
                        val raw = hit.hitPose; val cam = frame.camera.pose
                        // smooth the reticle so it glides instead of jittering
                        val sm = smooth.value?.let { o -> floatArrayOf(o[0] + (raw.tx() - o[0]) * 0.3f, o[1] + (raw.ty() - o[1]) * 0.3f, o[2] + (raw.tz() - o[2]) * 0.3f) }
                            ?: floatArrayOf(raw.tx(), raw.ty(), raw.tz())
                        smooth.value = sm
                        val hp = Pose(sm, floatArrayOf(0f, 0f, 0f, 1f))
                        val yaw = atan2((cam.tx() - hp.tx()).toDouble(), (cam.tz() - hp.tz()).toDouble())   // +Z toward the camera
                        val q = floatArrayOf(0f, sin(yaw / 2).toFloat(), 0f, cos(yaw / 2).toFloat())
                        val pose = Pose(floatArrayOf(hp.tx(), hp.ty(), hp.tz()), q)
                        candidate.value = Pose(floatArrayOf(raw.tx(), raw.ty(), raw.tz()), q) to hit.trackable
                        reticleRef.value?.let { r ->
                            r.worldPosition = Position(hp.tx(), hp.ty(), hp.tz())
                            r.worldQuaternion = dev.romainguy.kotlin.math.Quaternion(q[0], q[1], q[2], q[3])
                        }
                        canPlace = true
                    } else { candidate.value = null; canPlace = false; smooth.value = null }
                } else {
                    val m = machine
                    if (game.update(dt)) needUpload.value = true
                    if (m != null) {
                        if (needUpload.value && m.uploadScreen(game.renderer.bitmap)) needUpload.value = false
                        gesture.relax(dt.toFloat())
                        leverDeg.value = MachineController.LEVER_REST + gesture.pull * (MachineController.LEVER_PULLED - MachineController.LEVER_REST)
                        val glow = if (!game.busy) (0.5 + 0.5 * sin(ts / 2.5e8)).toFloat() else 0.2f
                        m.update(dt, game.mood, leverDeg.value, glow)
                        // where the knob is on screen (for finger / hand grabbing)
                        val kc = screenOf(m.worldPos("LeverKnob")); val ke = screenOf(m.worldPoint("LeverKnob", 0f, 0.046f, 0f))
                        val pv = screenOf(m.worldPos("LeverPivot"))
                        if (kc != null && ke != null) {
                            val r = hypot(kc.x - ke.x, kc.y - ke.y)
                            knob.value = Triple(kc.x, kc.y, maxOf(70f, r * 3.2f)); knobPt = Offset(kc.x, kc.y)
                            if (pv != null) throwPx.value = maxOf(80f, hypot(kc.x - pv.x, kc.y - pv.y))
                        } else { knob.value = null; knobPt = null }
                        grabbed = gesture.state == LeverGesture.State.GRABBED
                        if (handsOn) hands.onFrame(frame, viewW, viewH)
                    }
                }
            },
            onARCoreAvailability = { av -> if (av != null) Log.w("ZoharAr", "ARCore unavailable: $av") },
            onTouchEvent = { e, _ ->
                val now = SystemClock.uptimeMillis()
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downAt.value = Offset(e.x, e.y); knob.value?.let { k -> gesture.onPointer(e.x, e.y, k.first, k.second, k.third, throwPx.value, now) } }
                    MotionEvent.ACTION_MOVE -> knob.value?.let { k -> if (gesture.onPointer(e.x, e.y, k.first, k.second, k.third, throwPx.value, now)) pullSpin() }
                    MotionEvent.ACTION_UP -> {
                        val d = downAt.value; val wasGrab = gesture.state == LeverGesture.State.GRABBED
                        gesture.release()
                        if (d != null && !wasGrab && hypot(e.x - d.x, e.y - d.y) < slop) handleTap(e.x, e.y)
                    }
                }
                true
            },
        ) {
            if (anchor == null) {
                reticleModel?.let { ModelNode(modelInstance = it, autoAnimate = false, isVisible = canPlace, apply = { reticleRef.value = this }) }
            }
            anchor?.let { a ->
                anchorPlane?.let { ShadowReceiverPlane(plane = it) }
                AnchorNode(anchor = a) {
                    cabinet?.let { ModelNode(modelInstance = it, autoAnimate = false, rotation = io.github.sceneview.math.Rotation(y = yawOffset), scale = io.github.sceneview.math.Scale(if (tabletop) 0.4f else 1f),
                        apply = { cabinetNode.value = this; machine?.attach(this) }) }
                    for (fx in Fx.entries) {
                        val tok = fxTokens[fx] ?: 0
                        if (tok > 0 && fxActive[fx] == true) key(fx, tok) {
                            rememberModelInstance(modelLoader, FX_FILES.getValue(fx))?.let { inst ->
                                ModelNode(modelInstance = inst, autoAnimate = true, animationLoop = fx == Fx.FREE_SPINS, rotation = io.github.sceneview.math.Rotation(y = yawOffset), scale = io.github.sceneview.math.Scale(if (tabletop) 0.4f else 1f))
                            }
                        }
                    }
                }
            }
        }

        // hand / knob feedback
        LaunchedEffect(tapFx) { if (tapFx != null) { delay(450); tapFx = null; delay(900); tapLabel = "" } }
        Canvas(Modifier.fillMaxSize()) {
            // hand tracking runs behind the scenes: only a soft glow on the knob when the hand is on it
            if (anchor != null) knobPt?.let { k ->
                val r = knob.value?.third ?: 60f
                val h = handPt
                val near = h != null && hypot(h.x - k.x, h.y - k.y) < r * 1.3f
                if (grabbed) drawCircle(Color(0xAA3DFF8A), radius = r * 0.7f, center = k, style = Stroke(width = 8f))
                else if (near) drawCircle(Color(0x66FFFFFF), radius = r * 0.7f, center = k, style = Stroke(width = 4f))
            }
            tapFx?.let { (x, y, hit) -> drawCircle(if (hit) Color(0x993DFF8A) else Color(0x66FFFFFF), radius = 46f, center = Offset(x, y), style = Stroke(5f)) }
        }

        // top: status + credits
        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val nf = remember { NumberFormat.getIntegerInstance(Locale.US) }
            if (anchor != null) Pill("CREDIT ${nf.format(game.credits)} · BET ${game.bet}" + (if (game.inFree) " · FREE SPINS ${game.freeLeft}" else "") + (if (game.autoLeft > 0) " · AUTO ${game.autoLeft}" else "") + (if (tapLabel.isNotEmpty()) " · $tapLabel" else ""))
            val msg = when {
                anchor == null && !tracking -> "Move the phone slowly over the floor"
                anchor == null && !canPlace -> "Aim at free floor space · the frame shows the machine's real size"
                anchor == null && instantMode -> "No exact floor yet · you can place anyway, it settles as you move"
                anchor == null -> "Tap the screen to place the machine here"
                hint -> "Pull the lever: touch the red ball and drag down · with your hand in front of the camera or a finger on the screen"
                else -> null
            }
            msg?.let { Pill(it, Modifier.padding(top = 8.dp)) }
            if (anchor != null && handsOn) Pill(if (!hands.available) "Hand tracking is not available on this phone" else handStatus.ifEmpty { "Looking for your hand…" }, Modifier.padding(top = 8.dp))
        }

        // bottom controls (two rows so they fit a phone)
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 18.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (anchor == null) {
                Ctl(if (canPlace) "Place here" else "Finding the floor…", enabled = canPlace, primary = true, wide = true) { place() }
                Ctl("View without AR", small = true) { onNoAr() }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Ctl("−", enabled = !game.busy && !game.inFree) { game.bet(-1) }
                    Ctl(if (game.busy) "STOP" else if (game.inFree) "FREE" else "SPIN", primary = true, wide = true) { spinWithLever() }
                    Ctl("+", enabled = !game.busy && !game.inFree) { game.bet(+1) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Ctl(if (game.autoLeft > 0) "Stop auto" else "Auto", small = true) { game.toggleAuto() }
                    Ctl(if (muted) "🔇" else "🔊", small = true) { muted = !muted; sounds.muted = muted }
                    Ctl(if (handsOn) "Hand: on" else "Hand: off", small = true) { handsOn = !handsOn; if (!handsOn) handPt = null }
                    Ctl(if (occlusionOn) "Occlusion: on" else "Occlusion: off", small = true) { occlusionOn = !occlusionOn }
                    Ctl(if (tabletop) "Size: table" else "Size: real", small = true) { tabletop = !tabletop }
                    Ctl("Rotate", small = true) { yawOffset = (yawOffset + 30f) % 360f }
                    Ctl("Move", small = true) { anchor?.detach(); anchor = null; anchorPlane = null; scanSecs = 0f }
                }
            }
        }
        Text("Play money only · no real gambling, no purchases, no cash-out", Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 2.dp),
            color = Color(0xCCFFFFFF), fontSize = 10.sp)
    }
}

@Composable
internal fun Pill(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.widthIn(max = 360.dp).background(Color(0xCC12141C), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
}

@Composable
internal fun Ctl(label: String, enabled: Boolean = true, primary: Boolean = false, wide: Boolean = false, small: Boolean = false, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.height(if (small) 42.dp else 54.dp).then(if (wide) Modifier.widthIn(min = 110.dp) else Modifier),
        shape = RoundedCornerShape(26.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (primary) Color(0xFF18C95A) else Color(0xCC12141C), contentColor = if (primary) Color(0xFF031A0B) else Color.White),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = if (wide) 18.dp else 12.dp)) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = if (wide) 19.sp else if (small) 13.sp else 18.sp)
    }
}
