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
import io.github.sceneview.rememberARView
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.utils.worldToScreen
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
internal val FX_SECONDS = mapOf(Fx.LIGHTNING to 1.9, Fx.COINS to 3.1, Fx.BIG_WIN to 4.1, Fx.FREE_SPINS to 4.0)

@Composable
fun ZoharAr(smoke: Boolean = false, onNoAr: () -> Unit = {}) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val view = rememberARView(engine)

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
    val leverDeg = remember { Holder(MachineController.LEVER_REST) }

    val lastFrame = remember { Holder<Frame?>(null) }
    val lastTs = remember { Holder(0L) }
    val reticleRef = remember { Holder<ModelNodeImpl?>(null) }
    val candidate = remember { Holder<Pair<Pose, Plane>?>(null) }
    val needUpload = remember { Holder(true) }
    val knob = remember { Holder<Triple<Float, Float, Float>?>(null) }   // screen x, y, grab radius
    val throwPx = remember { Holder(200f) }
    val downAt = remember { Holder<Offset?>(null) }
    val perf = remember { Holder(longArrayOf(0, 0)) }
    val smokeStart = remember { Holder(0L) }

    fun pullSpin() { sounds.play("lever"); game.spin() }

    val hands = remember {
        HandTracker(context) { hand ->
            val now = SystemClock.uptimeMillis()
            val k = knob.value
            if (hand == null || k == null || viewW == 0) { handPt = null; gesture.onLost(now); return@HandTracker }
            val x = hand.index[0] * viewW; val y = hand.index[1] * viewH
            handPt = Offset(x, y)
            if (gesture.onPointer(x, y, k.first, k.second, k.third, throwPx.value, now)) pullSpin()
        }
    }
    DisposableEffect(Unit) { onDispose { hands.close(); sounds.close() } }

    // project a world point (x,y,z) to screen pixels
    fun screenOf(p: FloatArray?): Float2? = p?.let { view.worldToScreen(Position(it[0], it[1], it[2])) }

    fun place() {
        val c = candidate.value ?: return
        anchor?.detach()
        anchor = runCatching { c.second.createAnchor(c.first) }.getOrNull() ?: return
        anchorPlane = c.second; needUpload.value = true; sounds.play("click"); hint = true
    }

    fun handleTap(x: Float, y: Float) {
        if (anchor == null) { place(); return }
        val m = machine ?: return
        val parts = listOf("SpinButton", "BetDownButton", "BetUpButton", "MaxBetButton", "AutoButton", "LeverKnob")
        var best: String? = null; var bestD = Float.MAX_VALUE
        for (name in parts) {
            val s = screenOf(m.worldPos(name)) ?: continue
            val d = hypot(s.x - x, s.y - y)
            val lim = if (name == "LeverKnob") (knob.value?.third ?: 80f) else 70f
            if (d < lim && d < bestD) { best = name; bestD = d }
        }
        if (best == null) {   // the screen: anywhere inside its projected rectangle
            val tl = screenOf(m.worldPoint("Screen", -0.3f, 1.92f, 0f)); val br = screenOf(m.worldPoint("Screen", 0.3f, 0.93f, 0.15f))
            if (tl != null && br != null && x in minOf(tl.x, br.x)..maxOf(tl.x, br.x) && y in minOf(tl.y, br.y)..maxOf(tl.y, br.y)) best = "Screen"
        }
        when (best) {
            "SpinButton", "Screen" -> game.spin()
            "BetDownButton" -> game.bet(-1)
            "BetUpButton" -> game.bet(+1)
            "MaxBetButton" -> game.bet(99)
            "AutoButton" -> game.toggleAuto()
            "LeverKnob" -> { gesture.autoPull(SystemClock.uptimeMillis()); pullSpin() }
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
            sceneUnderstanding = SceneUnderstanding(occlusion = true, lighting = true, physics = false, planeVisualization = anchor == null),
            onSessionUpdated = { session, frame ->
                lastFrame.value = frame
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
                    val hit = if (viewW > 0) frame.hitTest(viewW / 2f, viewH * 0.62f).firstOrNull { h ->
                        val p = h.trackable as? Plane
                        p != null && p.type == Plane.Type.HORIZONTAL_UPWARD_FACING && p.isPoseInPolygon(h.hitPose) && p.trackingState == TrackingState.TRACKING
                    } else null
                    if (hit != null) {
                        val hp = hit.hitPose; val cam = frame.camera.pose
                        val yaw = atan2((cam.tx() - hp.tx()).toDouble(), (cam.tz() - hp.tz()).toDouble())   // +Z toward the camera
                        val q = floatArrayOf(0f, sin(yaw / 2).toFloat(), 0f, cos(yaw / 2).toFloat())
                        val pose = Pose(floatArrayOf(hp.tx(), hp.ty(), hp.tz()), q)
                        candidate.value = pose to (hit.trackable as Plane)
                        reticleRef.value?.let { r ->
                            r.worldPosition = Position(hp.tx(), hp.ty(), hp.tz())
                            r.worldQuaternion = dev.romainguy.kotlin.math.Quaternion(q[0], q[1], q[2], q[3])
                        }
                        canPlace = true
                    } else { candidate.value = null; canPlace = false }
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
                            knob.value = Triple(kc.x, kc.y, maxOf(55f, r * 2.6f)); knobPt = Offset(kc.x, kc.y)
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
                        if (d != null && !wasGrab && hypot(e.x - d.x, e.y - d.y) < 24f) handleTap(e.x, e.y)
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
                    cabinet?.let { ModelNode(modelInstance = it, autoAnimate = false) }
                    for (fx in Fx.entries) {
                        val tok = fxTokens[fx] ?: 0
                        if (tok > 0 && fxActive[fx] == true) key(fx, tok) {
                            rememberModelInstance(modelLoader, FX_FILES.getValue(fx))?.let { inst ->
                                ModelNode(modelInstance = inst, autoAnimate = true, animationLoop = fx == Fx.FREE_SPINS)
                            }
                        }
                    }
                }
            }
        }

        // hand / knob feedback
        Canvas(Modifier.fillMaxSize()) {
            if (anchor != null) knobPt?.let { k ->
                val r = knob.value?.third ?: 60f
                drawCircle(if (grabbed) Color(0xFF3DFF8A) else Color(0x66FFFFFF), radius = r, center = k, style = Stroke(width = if (grabbed) 6f else 3f))
            }
            handPt?.let { drawCircle(Color(0xFF7FE3FF), radius = 18f, center = it); drawCircle(Color.White, radius = 18f, center = it, style = Stroke(3f)) }
        }

        // top: status + credits
        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val nf = remember { NumberFormat.getIntegerInstance(Locale.US) }
            if (anchor != null) Pill("יתרה ${nf.format(game.credits)} · הימור ${game.bet}" + (if (game.inFree) " · חינם ${game.freeLeft}" else "") + (if (game.autoLeft > 0) " · אוטו ${game.autoLeft}" else ""))
            val msg = when {
                anchor == null && !tracking -> "הזיזו את הטלפון לאט לאורך הרצפה"
                anchor == null && !canPlace -> "כוונו לרצפה פנויה · המסגרת מראה את גודל המכונה"
                anchor == null -> "לחצו על המסך כדי להציב את המכונה כאן"
                hint -> "תפסו את הכדור האדום בראש הידית ומשכו למטה · ביד מול המצלמה או באצבע על המסך"
                else -> null
            }
            msg?.let { Pill(it, Modifier.padding(top = 8.dp)) }
        }

        // bottom controls (two rows so they fit a phone)
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 18.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (anchor == null) {
                Ctl(if (canPlace) "הצב כאן" else "מחפש רצפה…", enabled = canPlace, primary = true, wide = true) { place() }
                Ctl("תצוגה ללא AR", small = true) { onNoAr() }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Ctl("−", enabled = !game.busy && !game.inFree) { game.bet(-1) }
                    Ctl(if (game.busy) "עצור" else if (game.inFree) "חינם" else "סובב", primary = true, wide = true) { game.spin() }
                    Ctl("+", enabled = !game.busy && !game.inFree) { game.bet(+1) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Ctl(if (game.autoLeft > 0) "עצור אוטו" else "אוטו", small = true) { game.toggleAuto() }
                    Ctl(if (muted) "🔇" else "🔊", small = true) { muted = !muted; sounds.muted = muted }
                    Ctl(if (handsOn) "יד: פועל" else "יד: כבוי", small = true) { handsOn = !handsOn; if (!handsOn) handPt = null }
                    Ctl("הזז", small = true) { anchor?.detach(); anchor = null; anchorPlane = null }
                }
            }
        }
        Text("כסף משחק בלבד · אין הימור אמיתי, אין קנייה ואין משיכה", Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 2.dp),
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
