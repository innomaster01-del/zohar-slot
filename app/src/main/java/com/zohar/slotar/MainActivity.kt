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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
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
import io.github.sceneview.utils.worldToScreen
import io.github.sceneview.utils.screenToRay
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
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean { TouchBus.dispatch(ev); return super.dispatchTouchEvent(ev) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val smoke = intent?.getBooleanExtra("smoke", false) == true   // CI smoke test on the ARCore emulator
        val preview = intent?.getBooleanExtra("preview", false) == true
        val scratchTest = intent?.getBooleanExtra("scratchtest", false) == true
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                // two separate apps from one code base: the "slot" build and the "scratch" build
                if (BuildConfig.GAME == "blackjack") {
                    val bjTest = intent?.getBooleanExtra("bjtest", false) == true
                    var noAr by remember { mutableStateOf(bjTest) }
                    if (noAr) com.zohar.slotar.blackjack.BlackjackPreview(smoke = bjTest, onAr = if (bjTest) null else ({ noAr = false }), parts = intent?.getStringExtra("bjparts")?.split(',')?.toSet() ?: setOf("table", "dealer"))
                    else com.zohar.slotar.blackjack.BlackjackAr(smoke = smoke, onNoAr = { noAr = true })
                } else if (BuildConfig.GAME == "moneywheel") {
                    var noAr by remember { mutableStateOf(false) }
                    if (noAr) com.zohar.slotar.moneywheel.MoneyWheelPreview(smoke = false, onAr = { noAr = false })
                    else com.zohar.slotar.moneywheel.MoneyWheelAr(smoke = smoke, onNoAr = { noAr = true })
                } else if (BuildConfig.GAME == "roulette") {
                    var noAr by remember { mutableStateOf(false) }
                    if (noAr) com.zohar.slotar.roulette.RoulettePreview(smoke = false, onAr = { noAr = false })
                    else com.zohar.slotar.roulette.RouletteAr(smoke = smoke, onNoAr = { noAr = true })
                } else if (BuildConfig.GAME == "baccarat") {
                    var noAr by remember { mutableStateOf(false) }
                    if (noAr) com.zohar.slotar.baccarat.BaccaratPreview(smoke = false, onAr = { noAr = false })
                    else com.zohar.slotar.baccarat.BaccaratAr(smoke = smoke, onNoAr = { noAr = true })
                } else if (BuildConfig.GAME == "holdem") {
                    var noAr by remember { mutableStateOf(false) }
                    if (noAr) com.zohar.slotar.holdem.HoldemPreview(smoke = false, onAr = { noAr = false })
                    else com.zohar.slotar.holdem.HoldemAr(smoke = smoke, onNoAr = { noAr = true })
                } else if (BuildConfig.GAME == "scratch") {
                    var noAr by remember { mutableStateOf(scratchTest) }
                    if (noAr) com.zohar.slotar.scratch.ScratchPreview(smoke = scratchTest, onBack = if (scratchTest) null else ({ noAr = false }))
                    else com.zohar.slotar.scratch.ScratchAr(smoke = smoke, onNoAr = { noAr = true })
                } else {
                    // the slot app: a lobby of machines, each its own cabinet and game
                    var machineId by remember { mutableStateOf(intent?.getStringExtra("machine") ?: if (smoke || preview) "zohar" else null) }
                    val id = machineId
                    if (id == null) MachineLobby { machineId = it }
                    else key(id) {
                        var noAr by remember { mutableStateOf(preview) }
                        if (noAr) PreviewScreen(smoke, onAr = if (preview) null else ({ noAr = false }), machineId = id, onLobby = if (preview) null else ({ machineId = null }))
                        else ZoharAr(smoke, onNoAr = { noAr = true }, machineId = id, onLobby = { machineId = null })
                    }
                }
            }
        }
    }
}

private class Holder<T>(var value: T)


internal val FX_FILES = mapOf(Fx.LIGHTNING to "models/fx_lightning.glb", Fx.COINS to "models/fx_coins.glb", Fx.BIG_WIN to "models/fx_bigwin.glb", Fx.FREE_SPINS to "models/fx_freespins.glb")
internal val PART_LABELS = mapOf("SpinButton" to "SPIN", "Screen" to "SPIN", "BetDownButton" to "BET −", "BetUpButton" to "BET +", "MaxBetButton" to "MAX BET", "AutoButton" to "AUTO", "LeverKnob" to "LEVER", "LeverShaft" to "LEVER")
/** The Filament controller for a machine's cabinet: screen size, optional topper, light palette. */
internal fun controllerFor(engine: com.google.android.filament.Engine, inst: com.google.android.filament.gltfio.FilamentInstance, game: SlotMachine) = when (game.id) {
    "pharaoh" -> MachineController(engine, inst, com.zohar.slotar.screen.PharaohRenderer.W, com.zohar.slotar.screen.PharaohRenderer.H, palette = "pharaoh")
    "dragon" -> MachineController(engine, inst, com.zohar.slotar.screen.DragonRenderer.W, com.zohar.slotar.screen.DragonRenderer.H, game.topper?.width ?: 0, game.topper?.height ?: 0, palette = "dragon")
    else -> MachineController(engine, inst)
}

internal val FX_SECONDS = mapOf(Fx.LIGHTNING to 1.9, Fx.COINS to 3.1, Fx.BIG_WIN to 4.1, Fx.FREE_SPINS to 4.0)

@Composable
fun ZoharAr(smoke: Boolean = false, onNoAr: () -> Unit = {}, machineId: String = "zohar", onLobby: (() -> Unit)? = null) {
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
    val game = remember { Machines.create(machineId, sounds) { fx -> fxTokens[fx] = (fxTokens[fx] ?: 0) + 1 } }
    val gesture = remember { LeverGesture() }

    val cabinet = rememberModelInstance(modelLoader, game.cabinetFile)
    val reticleModel = rememberModelInstance(modelLoader, "models/reticle.glb")
    val machine = remember(cabinet) { cabinet?.let { controllerFor(engine, it, game) } }
    val machineRef = remember { Holder<MachineController?>(null) }
    machineRef.value = machine
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
    val placedRoot = remember { Holder<io.github.sceneview.node.Node?>(null) }
    val collisionSystem = io.github.sceneview.rememberCollisionSystem(view)
    val handLever = remember { com.zohar.slotar.ar.HandLever(gesture) }
    val vhand = remember { com.zohar.slotar.ar.VirtualHand(scale = 0.85f) }
    val camAxes = remember { Holder<Array<FloatArray>?>(null) }        // camera position, right, up, back (world)
    val jointNodes = remember { arrayOfNulls<io.github.sceneview.node.Node>(21) }
    val boneNodes = remember { arrayOfNulls<io.github.sceneview.node.Node>(21) }
    var debugOn by remember { mutableStateOf(false) }
    var handScreen by remember { mutableStateOf<FloatArray?>(null) }
    var debugText by remember { mutableStateOf("") }
    var planeCount by remember { mutableIntStateOf(0) }
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
    fun spinWithLever() { val r = game.spin(); if (r == "spin") { if (game.hasLever) { gesture.autoPull(SystemClock.uptimeMillis()); sounds.play("lever", 0.6f) }; buzz(25) } }

    val handSmooth = remember { Holder<FloatArray?>(null) }
    val hands = remember {
        HandTracker(context) { hand ->
            val now = SystemClock.uptimeMillis()
            val k = knob.value
            if (hand == null || viewW == 0) { handPt = null; handSmooth.value = null; handStatus = "Looking for your hand…"; handLever.onLost(now); return@HandTracker }
            val pts = hand.points
            var minX = 1f; var maxX = 0f; var minY = 1f; var maxY = 0f
            for (i in 0 until 21) { minX = minOf(minX, pts[2 * i]); maxX = maxOf(maxX, pts[2 * i]); minY = minOf(minY, pts[2 * i + 1]); maxY = maxOf(maxY, pts[2 * i + 1]) }
            val tooClose = maxX - minX > 0.6f
            val palm = handSmooth.value?.let { o -> floatArrayOf(o[0] + (pts[18] - o[0]) * 0.6f, o[1] + (pts[19] - o[1]) * 0.6f) } ?: floatArrayOf(pts[18], pts[19])
            handSmooth.value = palm
            handPt = Offset(palm[0] * viewW, palm[1] * viewH)
            handStatus = when {
                gesture.state == LeverGesture.State.GRABBED -> "Got the lever · move your hand down"
                tooClose -> "Hand very close · that's OK, cover the red ball and move down"
                else -> "Hand found · cover the red ball with your hand, then move down"
            }
            if (k == null) return@HandTracker
            val m = machineRef.value; val cam = camAxes.value
            if (m == null || cam == null) {
                var nearest = Float.MAX_VALUE
                for (idx in intArrayOf(4, 8, 12, 16, 20, 9)) nearest = minOf(nearest, hypot(pts[2 * idx] * viewW - k.first, pts[2 * idx + 1] * viewH - k.second))
                val box = floatArrayOf(minX * viewW, minY * viewH, maxX * viewW, maxY * viewH)
                if (handLever.onHand(box, palm[1] * viewH, nearest, k.first, k.second, k.third, throwPx.value, viewH.toFloat(), now)) { pullSpin(); buzz(40) }
                return@HandTracker
            }
            // 3D shape: MediaPipe world landmarks, or (if a phone does not give them) a flat hand built from the 2D points
            val world = if (hand.world.size >= 63) hand.world else FloatArray(63).also { w ->
                val sx = pts[18] - pts[0]; val sy = pts[19] - pts[1]
                val palmLenPx = kotlin.math.sqrt((sx * viewW) * (sx * viewW) + (sy * viewH) * (sy * viewH)).coerceAtLeast(1f)
                val mPerPx = 0.09f / palmLenPx                                   // wrist → middle knuckle ≈ 9 cm
                for (i in 0 until 21) { w[3 * i] = (pts[2 * i] - pts[18]) * viewW * mPerPx; w[3 * i + 1] = (pts[2 * i + 1] - pts[19]) * viewH * mPerPx; w[3 * i + 2] = 0f }
            }
            val sxp = pts[18] * viewW; val syp = pts[19] * viewH
            val ray = view.screenToRay(sxp, syp)?.let { r -> floatArrayOf(r.origin.x, r.origin.y, r.origin.z) to floatArrayOf(r.direction.x, r.direction.y, r.direction.z).let { d -> val l = kotlin.math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]); floatArrayOf(d[0] / l, d[1] / l, d[2] / l) } }
                ?: projector.ray(sxp, syp) ?: return@HandTracker
            // put the palm on the camera ray at the depth of the lever knob → the virtual hand lives at the machine
            val fwd = floatArrayOf(-cam[3][0], -cam[3][1], -cam[3][2])
            val knobW = m.worldPos("LeverKnob")
            var depth = knobW?.let { (it[0] - cam[0][0]) * fwd[0] + (it[1] - cam[0][1]) * fwd[1] + (it[2] - cam[0][2]) * fwd[2] } ?: 1.2f
            if (depth < 0.25f || depth > 6f) depth = 1.2f
            val cosA = (ray.second[0] * fwd[0] + ray.second[1] * fwd[1] + ray.second[2] * fwd[2]).coerceAtLeast(0.2f)
            val t = depth / cosA
            val palmW = floatArrayOf(ray.first[0] + ray.second[0] * t, ray.first[1] + ray.second[1] * t, ray.first[2] + ray.second[2] * t)
            vhand.onDetection(world, palmW, cam[1], cam[2], cam[3], now)
            handStatus = when {
                vhand.grabbing -> "Got the lever · move your hand down"
                vhand.closed -> "Fist · bring the gold hand to the red ball"
                else -> "Gold hand ready · fist on the red ball and pull down, or poke a button"
            }
            if (debugOn) debugText = "hand world=${hand.world.size} depth=${"%.2f".format(depth)} palm=${palmW.map { "%.2f".format(it) }} closed=${vhand.closed}"
        }
    }
    DisposableEffect(Unit) { onDispose { hands.close(); sounds.close() } }

    // project a world point (x,y,z) to screen pixels
    fun screenOf(p: FloatArray?): Float2? = p?.let { view.worldToScreen(Position(it[0], it[1], it[2])) } ?: projector.toScreen(p)?.let { Float2(it[0], it[1]) }

    fun place() {
        val c = candidate.value ?: return
        anchor?.detach()
        anchor = runCatching { c.second.createAnchor(c.first) }.getOrNull() ?: return
        anchorPlane = c.second as? Plane; needUpload.value = true; sounds.play("click"); hint = true
    }

    val handleTapRef = remember { Holder<((Float, Float) -> Unit)?>(null) }
    fun handleTap(x: Float, y: Float) {
        if (anchor == null) { place(); return }
        val m = machine ?: return
        val sceneHits = runCatching { collisionSystem.hitTest(x, y) }.getOrDefault(emptyList())
        val viaScene = sceneHits.firstNotNullOfOrNull { m.partOf(it.nodeOrNull) }
        // the renderer's own camera ray first (verified on CI: every button resolves), ARCore's matrices as a fallback
        val viaBoxes = view.screenToRay(x, y)?.let { r -> m.pick(floatArrayOf(r.origin.x, r.origin.y, r.origin.z), floatArrayOf(r.direction.x, r.direction.y, r.direction.z)) }
            ?: projector.ray(x, y)?.let { m.pick(it.first, it.second) }
        val best = (viaScene ?: viaBoxes)?.let { if (it == "ScreenGlass") "Screen" else it }
        debugText = "tap ${x.toInt()},${y.toInt()} scene=${viaScene ?: "-"} (${sceneHits.size} hits) box=${viaBoxes ?: "-"}"
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

    DisposableEffect(Unit) {
        TouchBus.handler = { e ->
            val now = SystemClock.uptimeMillis()
            if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_UP) Log.i("ZoharTouch", "${e.actionMasked} ${e.x.toInt()},${e.y.toInt()}")
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downAt.value = Offset(e.x, e.y); knob.value?.let { k -> gesture.onPointer(e.x, e.y, k.first, k.second, k.third, throwPx.value, now) } }
                MotionEvent.ACTION_MOVE -> knob.value?.let { k -> if (gesture.onPointer(e.x, e.y, k.first, k.second, k.third, throwPx.value, now)) pullSpin() }
                MotionEvent.ACTION_UP -> {
                    val d = downAt.value; val wasGrab = gesture.state == LeverGesture.State.GRABBED
                    gesture.release()
                    if (d != null && !wasGrab && hypot(e.x - d.x, e.y - d.y) < slop) handleTapRef.value?.invoke(e.x, e.y)
                }
            }
        }
        onDispose { TouchBus.handler = null; TouchBus.blocked.clear() }
    }
    handleTapRef.value = ::handleTap
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
            Log.i("ZoharSmoke", "spin ${i + 1} shown==engine: ${game.shownMatchesEngine()} busy=${game.busy}")
        }
        for (fx in listOf(Fx.LIGHTNING, Fx.COINS, Fx.BIG_WIN, Fx.FREE_SPINS)) { fxTokens[fx] = (fxTokens[fx] ?: 0) + 1; Log.i("ZoharSmoke", "fx $fx"); delay(1200) }
        gesture.autoPull(SystemClock.uptimeMillis()); Log.i("ZoharSmoke", "lever pulled")
        delay(600); Log.i("ZoharSmoke", "done")
    }

    Box(Modifier.fillMaxSize().onSizeChanged { viewW = it.width; viewH = it.height }) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine, modelLoader = modelLoader, materialLoader = materialLoader, view = view, collisionSystem = collisionSystem,
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL,
            depthMode = Config.DepthMode.AUTOMATIC,
            instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP,
            sceneUnderstanding = SceneUnderstanding(occlusion = occlusionOn, lighting = true, physics = false, planeVisualization = anchor == null),
            onSessionUpdated = { session, frame ->
                lastFrame.value = frame
                projector.update(frame, viewW, viewH)
                if (debugOn) planeCount = session.getAllTrackables(Plane::class.java).count { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
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
                    anchor?.takeIf { it.trackingState == TrackingState.TRACKING }?.pose?.let { p ->
                        placedRoot.value?.let { r ->
                            r.worldPosition = Position(p.tx(), p.ty(), p.tz())
                            r.worldQuaternion = dev.romainguy.kotlin.math.Quaternion(p.qx(), p.qy(), p.qz(), p.qw())
                        }
                    }
                    val m = machine
                    if (game.update(dt)) needUpload.value = true
                    if (m != null) {
                        if (needUpload.value && m.uploadScreen(game.screen)) needUpload.value = false
                        if (game.topperChanged()) game.topper?.let { m.uploadTopper(it) }
                        gesture.relax(dt.toFloat())
                        leverDeg.value = MachineController.LEVER_REST + maxOf(gesture.pull, vhand.pull) * (MachineController.LEVER_PULLED - MachineController.LEVER_REST)
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
                        frame.camera.displayOrientedPose.let { cp -> camAxes.value = arrayOf(floatArrayOf(cp.tx(), cp.ty(), cp.tz()), cp.xAxis, cp.yAxis, cp.zAxis) }
                        val nowMs = SystemClock.uptimeMillis()
                        vhand.step(dt, nowMs)
                        if (vhand.visible && handsOn) {
                            m.worldPos("LeverKnob")?.let { kw -> if (vhand.updateLever(kw, nowMs)) { pullSpin(); buzz(45) } }
                            for (b in MachineController.BUTTONS) {
                                val bw = m.worldPos(b) ?: continue
                                if (vhand.poke(b, bw, nowMs)) {
                                    m.press(b); buzz(20); tapLabel = PART_LABELS[b] ?: ""
                                    when (b) { "SpinButton" -> spinWithLever(); "BetDownButton" -> game.bet(-1); "BetUpButton" -> game.bet(+1); "MaxBetButton" -> game.bet(99); "AutoButton" -> game.toggleAuto() }
                                }
                            }
                        }
                        val showHand = vhand.visible && handsOn
                        handScreen = if (showHand) FloatArray(44).also { o ->
                            var ok = true
                            for (i in 0 until 21) { val j = vhand.joints[i]; val p = screenOf(j); if (p == null) { ok = false; break }; o[2 * i] = p.x; o[2 * i + 1] = p.y }
                            // pixels per centimetre at the hand, for line widths
                            val j9 = vhand.joints[9]; val a = screenOf(j9); val b = screenOf(floatArrayOf(j9[0] + 0.01f * (camAxes.value?.get(1)?.get(0) ?: 1f), j9[1] + 0.01f * (camAxes.value?.get(1)?.get(1) ?: 0f), j9[2] + 0.01f * (camAxes.value?.get(1)?.get(2) ?: 0f)))
                            o[42] = if (a != null && b != null) hypot(b.x - a.x, b.y - a.y) else 6f; o[43] = if (vhand.grabbing) 1f else 0f
                            if (!ok) o[42] = -1f
                        }.takeIf { it[42] > 0 } else null
                        val show3d = false     // the 2D overlay is the visible hand (guaranteed on every phone)
                        for (i in 0 until 21) jointNodes[i]?.let { n -> n.isVisible = show3d; if (show3d) { val j = vhand.joints[i]; n.worldPosition = Position(j[0], j[1], j[2]) } }
                        for (b in 0 until 21) boneNodes[b]?.let { n ->
                            n.isVisible = show3d
                            if (show3d) {
                                val a = vhand.joints[com.zohar.slotar.ar.VirtualHand.BONES[2 * b]]; val c = vhand.joints[com.zohar.slotar.ar.VirtualHand.BONES[2 * b + 1]]
                                val dx = c[0] - a[0]; val dy = c[1] - a[1]; val dz = c[2] - a[2]; val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
                                val ux = dx / len; val uy = dy / len; val uz = dz / len
                                // rotation taking +Y onto the bone direction
                                var qx = uz; var qy = 0f; var qz = -ux; var qw = 1f + uy
                                val qn = kotlin.math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
                                if (qn < 1e-4f) { qx = 1f; qy = 0f; qz = 0f; qw = 0f } else { qx /= qn; qy /= qn; qz /= qn; qw /= qn }
                                n.worldPosition = Position((a[0] + c[0]) / 2, (a[1] + c[1]) / 2, (a[2] + c[2]) / 2)
                                n.worldQuaternion = dev.romainguy.kotlin.math.Quaternion(qx, qy, qz, qw)
                                n.scale = io.github.sceneview.math.Scale(1f, len, 1f)
                            }
                        }
                    }
                }
            },
            onARCoreAvailability = { av -> if (av != null) Log.w("ZoharAr", "ARCore unavailable: $av") },
        ) {
            // nodes stay composed for the whole session (re-creating them after "Move" broke the model): the root just
            // follows the anchor's pose every frame and hides while placing
            reticleModel?.let { ModelNode(modelInstance = it, autoAnimate = false, isVisible = anchor == null && canPlace, apply = { reticleRef.value = this }) }
            // the virtual hand: gold joints + bones, moved every frame from the tracked real hand
            // drawn on top of everything: the hand sits at the lever's depth, so over the cabinet it would be hidden inside it
            val glove = remember(materialLoader) {
                materialLoader.createColorInstance(androidx.compose.ui.graphics.Color(0xFFFFC94D), metallic = 0.55f, roughness = 0.32f).apply { setDepthCulling(false); setDepthWrite(false) }
            }
            for (i in 0 until 21) SphereNode(radius = if (i == 4 || i == 8 || i == 12 || i == 16 || i == 20) 0.0115f else 0.0105f, stacks = 12, slices = 12, materialInstance = glove, apply = { jointNodes[i] = this; isVisible = false; setPriority(7) })
            for (b in 0 until 21) CylinderNode(radius = 0.0088f, height = 1f, sideCount = 10, materialInstance = glove, apply = { boneNodes[b] = this; isVisible = false; setPriority(7) })
            anchorPlane?.let { ShadowReceiverPlane(plane = it) }
            Node(apply = { placedRoot.value = this }) {
                cabinet?.let { ModelNode(modelInstance = it, autoAnimate = false, isVisible = anchor != null, rotation = io.github.sceneview.math.Rotation(y = yawOffset), scale = io.github.sceneview.math.Scale(if (tabletop) 0.4f else 1f),
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
            if (debugOn && anchor != null) machine?.let { m ->
                for (b in MachineController.TAPPABLE) {
                    projector.toScreen(m.worldPos(b))?.let { drawCircle(Color(0xFF00FF66), radius = 9f, center = Offset(it[0], it[1])) }
                    m.worldPos(b)?.let { w -> view.worldToScreen(Position(w[0], w[1], w[2]))?.let { drawCircle(Color(0xFFFF00FF), radius = 5f, center = Offset(it.x, it.y)) } }
                }
            }
            // the gold virtual hand (projected from its 3D joints at the machine)
            handScreen?.let { h ->
                val pxcm = h[42]; val grab = h[43] > 0.5f
                fun pt(k: Int) = Offset(h[2 * k], h[2 * k + 1])
                val main = if (grab) Color(0xFF5DFF9E) else Color(0xFFFFC94D); val dark = if (grab) Color(0xFF1E8C4E) else Color(0xFF9A6A12)
                val palm = androidx.compose.ui.graphics.Path().apply { moveTo(pt(0).x, pt(0).y); for (k in intArrayOf(1, 5, 9, 13, 17)) lineTo(pt(k).x, pt(k).y); close() }
                drawPath(palm, main.copy(alpha = 0.85f))
                val bones = com.zohar.slotar.ar.VirtualHand.BONES
                for (i in bones.indices step 2) {
                    val w = pxcm * (if (bones[i] == 0 || bones[i + 1] == 0) 1.6f else 1.25f)
                    drawLine(dark, pt(bones[i]), pt(bones[i + 1]), strokeWidth = w + 3f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(main, pt(bones[i]), pt(bones[i + 1]), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
                for (k in intArrayOf(4, 8, 12, 16, 20)) drawCircle(Color.White.copy(alpha = 0.7f), radius = pxcm * 0.35f, center = Offset(pt(k).x - pxcm * 0.2f, pt(k).y - pxcm * 0.2f))
            }
            tapFx?.let { (x, y, hit) -> drawCircle(if (hit) Color(0x993DFF8A) else Color(0x66FFFFFF), radius = 46f, center = Offset(x, y), style = Stroke(5f)) }
        }

        // top: status + credits
        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val nf = remember { NumberFormat.getIntegerInstance(Locale.US) }
            if (anchor != null) Pill("${game.title} · CREDIT ${nf.format(game.credits)} · BET ${game.bet}" + (if (game.inFree) (if (game.id == "dragon") " · HOLD & WIN ${game.freeLeft}" else " · FREE SPINS ${game.freeLeft}") else "") + (if (game.autoLeft > 0) " · AUTO ${game.autoLeft}" else "") + (if (tapLabel.isNotEmpty()) " · $tapLabel" else ""))
            val msg = when {
                anchor == null && !tracking -> "Move the phone slowly over the floor"
                anchor == null && !canPlace -> "Aim at free floor space · the frame shows the machine's real size"
                anchor == null && instantMode -> "No exact floor yet · you can place anyway, it settles as you move"
                anchor == null -> "Tap the screen to place the machine here"
                hint && game.hasLever -> "Pull the lever: touch the red ball and drag down · with your hand in front of the camera or a finger on the screen"
                hint -> "${game.title}: tap SPIN on the machine's deck or on its screen · BET − / BET + / MAX BET / AUTO are real buttons too"
                else -> null
            }
            msg?.let { Pill(it, Modifier.padding(top = 8.dp)) }
            if (debugOn) Pill("DEBUG · tracking=$tracking planes=$planeCount · hand runs=${hands.runs} found=${hands.detections} ${hands.lastError ?: ""} · $debugText", Modifier.padding(top = 8.dp))
            if (anchor != null && handsOn) Pill(if (!hands.available) "Hand tracking is not available on this phone" else handStatus.ifEmpty { "Looking for your hand…" }, Modifier.padding(top = 8.dp))
        }

        // bottom controls (two rows so they fit a phone)
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 18.dp).fillMaxWidth()
            .onGloballyPositioned { c -> c.boundsInWindow().let { b -> TouchBus.blocked["controls"] = android.graphics.RectF(b.left, b.top, b.right, b.bottom) } }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (anchor == null) {
                Ctl(if (canPlace) "Place here" else "Finding the floor…", enabled = canPlace, primary = true, wide = true) { place() }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Ctl("View without AR", small = true) { onNoAr() }
                    onLobby?.let { Ctl("Machines", small = true) { it() } }
                }
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
                    Ctl(if (debugOn) "Debug: on" else "Debug", small = true) { debugOn = !debugOn }
                    Ctl("Move", small = true) { anchor?.detach(); anchor = null; anchorPlane = null; scanSecs = 0f }
                    onLobby?.let { Ctl("Machines", small = true) { it() } }
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
