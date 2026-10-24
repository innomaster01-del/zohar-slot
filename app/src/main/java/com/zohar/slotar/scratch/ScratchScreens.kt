package com.zohar.slotar.scratch

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import com.zohar.slotar.FX_FILES
import com.zohar.slotar.FX_SECONDS
import com.zohar.slotar.Fx
import com.zohar.slotar.TouchBus
import com.zohar.slotar.Ctl
import com.zohar.slotar.Pill
import com.zohar.slotar.ar.HandTracker
import com.zohar.slotar.ar.VirtualHand
import com.zohar.slotar.audio.SlotSounds
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.SceneView
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.scene.SceneUnderstanding
import io.github.sceneview.createARView
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.rememberARView
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberView
import io.github.sceneview.utils.screenToRay
import io.github.sceneview.utils.worldToScreen
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private class Ref<T>(var v: T)

/** Effects around a 30 cm card are the slot's effects at ~1/3 size. */
private const val FX_SCALE = 0.32f

private fun View3dRay(view: com.google.android.filament.View, x: Float, y: Float): Pair<FloatArray, FloatArray>? =
    view.screenToRay(x, y)?.let { r ->
        val d = floatArrayOf(r.direction.x, r.direction.y, r.direction.z); val l = kotlin.math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
        floatArrayOf(r.origin.x, r.origin.y, r.origin.z) to floatArrayOf(d[0] / l, d[1] / l, d[2] / l)
    }

/** AR Scratch Cards: place a 21 × 30 cm card on any table or floor, scratch it with a finger or the gold virtual hand. */
@Composable
fun ScratchAr(smoke: Boolean = false, onNoAr: (() -> Unit)? = null, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val view = rememberARView(engine) {
        createARView(engine).apply {
            multiSampleAntiAliasingOptions = multiSampleAntiAliasingOptions.apply { enabled = true; sampleCount = 4 }
            bloomOptions = bloomOptions.apply { enabled = true; strength = 0.12f; threshold = true }
        }
    }
    val sounds = remember { SlotSounds(context) }
    val fxTokens = remember { mutableStateMapOf<Fx, Int>() }
    val fxActive = remember { mutableStateMapOf<Fx, Boolean>() }
    val session = remember { ScratchSession(sounds) { fx -> fxTokens[fx] = (fxTokens[fx] ?: 0) + 1 } }
    val prefs = remember { context.getSharedPreferences("zohar", Context.MODE_PRIVATE) }
    val cardModel = rememberModelInstance(modelLoader, "models/scratch_card.glb")
    val reticleModel = rememberModelInstance(modelLoader, "models/reticle.glb")
    val card = remember(cardModel) { cardModel?.let { CardController(engine, it) } }
    val cardRef = remember { Ref<CardController?>(null) }; cardRef.v = card
    DisposableEffect(card) { onDispose { card?.destroy() } }

    var anchor by remember { mutableStateOf<Anchor?>(null) }
    var anchorPlane by remember { mutableStateOf<Plane?>(null) }
    var canPlace by remember { mutableStateOf(false) }
    var tracking by remember { mutableStateOf(false) }
    var viewW by remember { mutableIntStateOf(0) }
    var viewH by remember { mutableIntStateOf(0) }
    var scanSecs by remember { mutableFloatStateOf(0f) }
    var handsOn by remember { mutableStateOf(true) }
    var handScreen by remember { mutableStateOf<FloatArray?>(null) }
    var handStatus by remember { mutableStateOf("") }
    val candidate = remember { Ref<Pair<Pose, com.google.ar.core.Trackable>?>(null) }
    val smooth = remember { Ref<FloatArray?>(null) }
    val lastTs = remember { Ref(0L) }
    val needUpload = remember { Ref(true) }
    val placedRoot = remember { Ref<io.github.sceneview.node.Node?>(null) }
    val reticleRef = remember { Ref<io.github.sceneview.node.Node?>(null) }
    val camAxes = remember { Ref<Array<FloatArray>?>(null) }
    val vhand = remember { VirtualHand(scale = 0.7f) }
    val tipPrev = remember { Ref<FloatArray?>(null) }

    val hands = remember {
        HandTracker(context) { hand ->
            val now = SystemClock.uptimeMillis()
            val c = cardRef.v; val cam = camAxes.v
            if (hand == null || c == null || cam == null || viewW == 0) { handStatus = "Looking for your hand…"; return@HandTracker }
            val pts = hand.points
            val world = if (hand.world.size >= 63) hand.world else FloatArray(63).also { w ->
                val sx = (pts[18] - pts[0]) * viewW; val sy = (pts[19] - pts[1]) * viewH; val mPerPx = 0.09f / kotlin.math.sqrt(sx * sx + sy * sy).coerceAtLeast(1f)
                for (i in 0 until 21) { w[3 * i] = (pts[2 * i] - pts[18]) * viewW * mPerPx; w[3 * i + 1] = (pts[2 * i + 1] - pts[19]) * viewH * mPerPx }
            }
            // the gold hand hovers over the card: its fingertip is put on the camera ray at the card's depth
            val tipX = pts[16] * viewW; val tipY = pts[17] * viewH
            val ray = View3dRay(view, tipX, tipY) ?: return@HandTracker
            // the fingertip rides on the card's surface plane (falls back to the card depth when the ray misses it)
            val center = c.center() ?: return@HandTracker
            val fwd = floatArrayOf(-cam[3][0], -cam[3][1], -cam[3][2])
            val onPlane = c.rayToWorld(ray.first, ray.second)?.takeIf { p -> val dx = p[0] - center[0]; val dy = p[1] - center[1]; val dz = p[2] - center[2]; dx * dx + dy * dy + dz * dz < 0.25f }
            val tipW = onPlane ?: run {
                var depth = (center[0] - cam[0][0]) * fwd[0] + (center[1] - cam[0][1]) * fwd[1] + (center[2] - cam[0][2]) * fwd[2]
                if (depth < 0.15f || depth > 5f) depth = 0.6f
                val cosA = (ray.second[0] * fwd[0] + ray.second[1] * fwd[1] + ray.second[2] * fwd[2]).coerceAtLeast(0.2f)
                floatArrayOf(ray.first[0] + ray.second[0] * depth / cosA, ray.first[1] + ray.second[1] * depth / cosA, ray.first[2] + ray.second[2] * depth / cosA)
            }
            // VirtualHand anchors on the palm: shift so that the index tip lands on tipW
            val s = 0.7f; val dxm = (world[24] - world[27]) * s; val dym = -(world[25] - world[28]) * s; val dzm = -(world[26] - world[29]) * s
            val palmW = FloatArray(3) { k -> tipW[k] - (cam[1][k] * dxm + cam[2][k] * dym + cam[3][k] * dzm) }
            vhand.onDetection(world, palmW, cam[1], cam[2], cam[3], now)
            handStatus = "Gold hand ready · touch the silver with its fingertip to scratch"
        }
    }
    DisposableEffect(Unit) { onDispose { hands.close(); sounds.close() } }

    fun place() {
        val c = candidate.v ?: return
        anchor?.detach()
        anchor = runCatching { c.second.createAnchor(c.first) }.getOrNull() ?: return
        anchorPlane = c.second as? Plane; needUpload.v = true; sounds.play("click")
        if (!session.hasCard) session.buy()
    }

    DisposableEffect(Unit) {
        TouchBus.handler = { e ->
            if (anchor == null) { if (e.actionMasked == MotionEvent.ACTION_UP) place() }
            else when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val r = View3dRay(view, e.x, e.y); val px = r?.let { cardRef.v?.rayToPixel(it.first, it.second) }
                    session.scratchAt(px)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> session.scratchAt(null)
            }
        }
        onDispose { TouchBus.handler = null; TouchBus.blocked.clear() }
    }

    if (smoke) LaunchedEffect(anchor) {
        if (anchor == null) return@LaunchedEffect
        delay(2500)
        val c = cardRef.v; Log.i("ZoharSmoke", "scratch AR card=${c?.ok} hasCard=${session.hasCard} credits=${session.credits}")
        if (c == null) return@LaunchedEffect
        var onScreen = 0; var mapped = 0; var tries = 0
        for (r in ScratchLayout.regions) {
            var down = true; var y = r.t + 12
            while (y < r.b) {
                var x = r.l + 8
                while (x < r.r) {
                    tries++
                    val w = c.pixelToWorld(x, y); val sp = w?.let { view.worldToScreen(Position(it[0], it[1], it[2])) }
                    if (sp != null && sp.x in 0f..viewW.toFloat() && sp.y in 0f..viewH.toFloat()) {
                        onScreen++
                        val back = View3dRay(view, sp.x, sp.y)?.let { c.rayToPixel(it.first, it.second) }
                        if (back != null && hypot(back[0] - x, back[1] - y) < 14f) mapped++
                        val now = SystemClock.uptimeMillis()
                        val ev = MotionEvent.obtain(now, now, if (down) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE, sp.x, sp.y, 0)
                        TouchBus.dispatch(ev); ev.recycle(); down = false
                    }
                    x += 40f
                }
                y += 26f
            }
            val now = SystemClock.uptimeMillis(); val up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 0f, 0f, 0); TouchBus.dispatch(up); up.recycle()
            delay(40)
        }
        delay(2500)
        Log.i("ZoharSmoke", "scratch AR touches onScreen=$onScreen/$tries mapped=$mapped finished=${session.finished} lastWin=${session.lastWin} credits=${session.credits}")
        if (session.finished) { session.buy(); delay(800); Log.i("ZoharSmoke", "scratch AR next card bought hasCard=${session.hasCard} finished=${session.finished} credits=${session.credits}") }
        Log.i("ZoharSmoke", "scratch AR done")
    }

    for (fx in Fx.entries) {
        val tok = fxTokens[fx] ?: 0
        LaunchedEffect(fx, tok) {
            if (tok == 0) return@LaunchedEffect
            fxActive[fx] = true; delay((FX_SECONDS.getValue(fx) * 1000).toLong()); fxActive[fx] = false
        }
    }

    Box(Modifier.fillMaxSize().onSizeChanged { viewW = it.width; viewH = it.height }) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine, modelLoader = modelLoader, materialLoader = materialLoader, view = view,
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL,
            depthMode = Config.DepthMode.AUTOMATIC,
            instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP,
            sceneUnderstanding = SceneUnderstanding(occlusion = false, lighting = true, physics = false, planeVisualization = anchor == null),
            onSessionUpdated = { arSession, frame ->
                val ts = frame.timestamp; val dt = if (lastTs.v == 0L) 1.0 / 30 else ((ts - lastTs.v) / 1e9).coerceIn(0.0, 0.1); lastTs.v = ts
                tracking = frame.camera.trackingState == TrackingState.TRACKING
                frame.camera.displayOrientedPose.let { cp -> camAxes.v = arrayOf(floatArrayOf(cp.tx(), cp.ty(), cp.tz()), cp.xAxis, cp.yAxis, cp.zAxis) }
                if (smoke && anchor == null && tracking && scanSecs > 3f) {
                    val cam = frame.camera.pose; val fz = cam.zAxis; val hl = kotlin.math.sqrt(fz[0] * fz[0] + fz[2] * fz[2]).coerceAtLeast(1e-3f)
                    val px = cam.tx() - fz[0] / hl * 0.45f; val pz = cam.tz() - fz[2] / hl * 0.45f; val py = cam.ty() - 0.22f
                    val yaw = atan2((cam.tx() - px).toDouble(), (cam.tz() - pz).toDouble())
                    anchor = runCatching { arSession.createAnchor(Pose(floatArrayOf(px, py, pz), floatArrayOf(0f, sin(yaw / 2).toFloat(), 0f, cos(yaw / 2).toFloat()))) }
                        .onFailure { Log.e("ZoharSmoke", "scratch anchor failed", it) }.getOrNull()
                    Log.i("ZoharSmoke", "scratch AR placed=${anchor != null}"); if (anchor != null && !session.hasCard) session.buy()
                }
                if (anchor == null) {
                    if (tracking) scanSecs += dt.toFloat()
                    val ax = viewW / 2f; val ay = viewH * 0.6f
                    val hits = if (viewW > 0) frame.hitTest(ax, ay) else emptyList()
                    fun surface(h: com.google.ar.core.HitResult, inside: Boolean): Boolean {
                        val p = h.trackable as? Plane ?: return false
                        return p.type == Plane.Type.HORIZONTAL_UPWARD_FACING && p.trackingState == TrackingState.TRACKING && h.distance < 4f && (!inside || p.isPoseInPolygon(h.hitPose)) && p.subsumedBy == null
                    }
                    var hit = hits.firstOrNull { surface(it, true) } ?: hits.firstOrNull { surface(it, false) }
                    if (hit == null && scanSecs > 5f && viewW > 0) hit = frame.hitTestInstantPlacement(ax, ay, 0.8f).firstOrNull()
                    if (hit != null) {
                        val raw = hit.hitPose; val cam = frame.camera.pose
                        val sm = smooth.v?.let { o -> floatArrayOf(o[0] + (raw.tx() - o[0]) * 0.3f, o[1] + (raw.ty() - o[1]) * 0.3f, o[2] + (raw.tz() - o[2]) * 0.3f) } ?: floatArrayOf(raw.tx(), raw.ty(), raw.tz())
                        smooth.v = sm
                        val yaw = atan2((cam.tx() - sm[0]).toDouble(), (cam.tz() - sm[2]).toDouble())
                        val q = floatArrayOf(0f, sin(yaw / 2).toFloat(), 0f, cos(yaw / 2).toFloat())
                        candidate.v = Pose(floatArrayOf(raw.tx(), raw.ty(), raw.tz()), q) to hit.trackable
                        reticleRef.v?.let { r -> r.worldPosition = Position(sm[0], sm[1], sm[2]); r.worldQuaternion = dev.romainguy.kotlin.math.Quaternion(q[0], q[1], q[2], q[3]) }
                        canPlace = true
                    } else { candidate.v = null; canPlace = false; smooth.v = null }
                } else {
                    anchor?.takeIf { it.trackingState == TrackingState.TRACKING }?.pose?.let { p ->
                        placedRoot.v?.let { r -> r.worldPosition = Position(p.tx(), p.ty(), p.tz()); r.worldQuaternion = dev.romainguy.kotlin.math.Quaternion(p.qx(), p.qy(), p.qz(), p.qw()) }
                    }
                    if (session.update(dt.toFloat())) needUpload.v = true
                    val c = cardRef.v
                    if (c != null) {
                        if (needUpload.v && c.upload(session.renderer.bitmap)) needUpload.v = false
                        c.rimGlow(if (session.finished && session.lastWin > 0) (1.5f + sin(ts / 1.2e8).toFloat()) else 0f)
                        if (handsOn) hands.onFrame(frame, viewW, viewH)
                        val now = SystemClock.uptimeMillis()
                        vhand.step(dt, now)
                        if (vhand.visible && handsOn) {
                            // fingertip on the card surface scratches
                            val px = c.pointToPixel(vhand.joints[8], 0.035f)
                            if (px != null) { session.scratchAt(px); tipPrev.v = px } else if (tipPrev.v != null) { session.scratchAt(null); tipPrev.v = null }
                        }
                        handScreen = if (vhand.visible && handsOn) FloatArray(43).also { o ->
                            var ok = true
                            for (i in 0 until 21) { val j = vhand.joints[i]; val p = view.worldToScreen(Position(j[0], j[1], j[2])); if (p == null) { ok = false; break }; o[2 * i] = p.x; o[2 * i + 1] = p.y }
                            val a = view.worldToScreen(Position(vhand.joints[9][0], vhand.joints[9][1], vhand.joints[9][2])); val r = camAxes.v?.get(1)
                            val b = r?.let { view.worldToScreen(Position(vhand.joints[9][0] + 0.01f * it[0], vhand.joints[9][1] + 0.01f * it[1], vhand.joints[9][2] + 0.01f * it[2])) }
                            o[42] = if (ok && a != null && b != null) hypot(b.x - a.x, b.y - a.y) else -1f
                        }.takeIf { it[42] > 0 } else null
                    }
                }
            },
        ) {
            reticleModel?.let { ModelNode(modelInstance = it, autoAnimate = false, isVisible = anchor == null && canPlace, scale = Scale(0.35f), apply = { reticleRef.v = this }) }
            anchorPlane?.let { ShadowReceiverPlane(plane = it) }
            Node(apply = { placedRoot.v = this }) {
                cardModel?.let { ModelNode(modelInstance = it, autoAnimate = false, isVisible = anchor != null) }
                for (fx in Fx.entries) {
                    val tok = fxTokens[fx] ?: 0
                    if (tok > 0 && fxActive[fx] == true) key(fx, tok) {
                        rememberModelInstance(modelLoader, FX_FILES.getValue(fx))?.let { ModelNode(modelInstance = it, autoAnimate = true, animationLoop = false, scale = Scale(FX_SCALE)) }
                    }
                }
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            handScreen?.let { h -> drawGoldHand(h) }
        }

        val nf = remember { NumberFormat.getIntegerInstance(Locale.US) }
        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("CREDIT ${nf.format(session.credits)} · ${session.tier.label} ${session.tier.price}" + (if (session.freeCards > 0) " · FREE CARDS ${session.freeCards}" else ""))
            val msg = when {
                anchor == null && !tracking -> "Move the phone slowly over a table or the floor"
                anchor == null && !canPlace -> "Aim at a flat surface"
                anchor == null -> "Tap the screen to put the card here"
                else -> session.message
            }
            if (msg.isNotEmpty()) Pill(msg)
            if (anchor != null && handsOn && handStatus.isNotEmpty()) Pill(handStatus)
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 18.dp).fillMaxWidth()
            .onGloballyPositioned { c -> c.boundsInWindow().let { b -> TouchBus.blocked["controls"] = android.graphics.RectF(b.left, b.top, b.right, b.bottom) } },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (anchor == null) {
                Ctl(if (canPlace) "Place card here" else "Finding a surface…", enabled = canPlace, primary = true, wide = true) { place() }
                onNoAr?.let { Ctl("View without AR", small = true) { it() } }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (t in Tier.entries) Ctl("${t.label} ${t.price}", small = true, primary = session.tier == t, enabled = !session.hasCard || session.finished) { session.selectTier(t) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!session.hasCard || session.finished) Ctl(if (session.freeCards > 0) "FREE CARD" else "BUY CARD", primary = true, wide = true) { if (session.buy()) needUpload.v = true }
                    else Ctl("REVEAL ALL", primary = true, wide = true) { session.revealAll() }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Ctl("Daily card", small = true) { session.claimDaily(prefs) }
                    Ctl(if (handsOn) "Hand: on" else "Hand: off", small = true) { handsOn = !handsOn; if (!handsOn) handScreen = null }
                    Ctl("Move", small = true) { anchor?.detach(); anchor = null; anchorPlane = null; scanSecs = 0f }
                    onNoAr?.let { Ctl("No AR", small = true) { it() } }
                }
            }
        }
    }
}

/** The gold virtual hand overlay (joints projected from the 3D hand). h = 21 × (x,y) + px-per-cm. */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGoldHand(h: FloatArray, grab: Boolean = false) {
    val pxcm = h[42]
    fun pt(k: Int) = Offset(h[2 * k], h[2 * k + 1])
    val main = if (grab) Color(0xFF5DFF9E) else Color(0xFFFFC94D); val dark = if (grab) Color(0xFF1E8C4E) else Color(0xFF9A6A12)
    val palm = androidx.compose.ui.graphics.Path().apply { moveTo(pt(0).x, pt(0).y); for (k in intArrayOf(1, 5, 9, 13, 17)) lineTo(pt(k).x, pt(k).y); close() }
    drawPath(palm, main.copy(alpha = 0.85f))
    val bones = VirtualHand.BONES
    for (i in bones.indices step 2) {
        val w = pxcm * (if (bones[i] == 0 || bones[i + 1] == 0) 1.6f else 1.25f)
        drawLine(dark, pt(bones[i]), pt(bones[i + 1]), strokeWidth = w + 3f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(main, pt(bones[i]), pt(bones[i + 1]), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
    for (k in intArrayOf(4, 8, 12, 16, 20)) drawCircle(Color.White.copy(alpha = 0.7f), radius = pxcm * 0.35f, center = Offset(pt(k).x - pxcm * 0.2f, pt(k).y - pxcm * 0.2f))
}

/** The same card without AR (CI self-test and phones without ARCore): card on a virtual table, scratch with a finger. */
@Composable
fun ScratchPreview(smoke: Boolean = false, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val view = rememberView(engine)
    val sounds = remember { SlotSounds(context) }
    val session = remember { ScratchSession(sounds) {} }
    val cardModel = rememberModelInstance(modelLoader, "models/scratch_card.glb")
    val card = remember(cardModel) { cardModel?.let { CardController(engine, it) } }
    val cardRef = remember { Ref<CardController?>(null) }; cardRef.v = card
    val needUpload = remember { Ref(true) }
    val last = remember { Ref(0L) }
    DisposableEffect(Unit) {
        TouchBus.handler = { e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> session.scratchAt(View3dRay(view, e.x, e.y)?.let { cardRef.v?.rayToPixel(it.first, it.second) })
                MotionEvent.ACTION_UP -> session.scratchAt(null)
            }
        }
        onDispose { TouchBus.handler = null; TouchBus.blocked.clear(); sounds.close() }
    }
    if (smoke) LaunchedEffect(card) {
        val c = card ?: return@LaunchedEffect
        delay(1500)
        Log.i("ZoharSmoke", "scratch card ok=${c.ok}")
        session.selectTier(Tier.GOLD); session.buy()
        val cd = session.renderer.card!!
        Log.i("ZoharSmoke", "scratch bought ${cd.tier} prize=${cd.prizeMultiple} mult=${cd.multiplier} free=${cd.freeCard} win=${cd.totalWin}")
        fun dump(n: String) = runCatching {   // card face → logcat as base64 chunks (the CI pulls it with logcat -d)
            val small = android.graphics.Bitmap.createScaledBitmap(session.renderer.bitmap, 360, 514, true)
            val bos = java.io.ByteArrayOutputStream(); small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bos)
            val b64 = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
            b64.chunked(3000).forEachIndexed { k, c -> Log.i("ZoharImg", "$n $k $c") }; Log.i("ZoharImg", "$n end")
        }
        repeat(3) { session.update(0.05f) }; dump("card_new.png")
        // scratch every region through the real path: card pixel → world → screen → ray → card pixel
        var hitsOk = 0; var tries = 0
        for (r in ScratchLayout.regions) {
            var y = r.t + 12; while (y < r.b) {
                var x = r.l + 8; while (x < r.r) {
                    tries++
                    val world = c.pixelToWorld(x, y)
                    val sp = world?.let { view.worldToScreen(Position(it[0], it[1], it[2])) }
                    val px = sp?.let { s -> View3dRay(view, s.x, s.y)?.let { c.rayToPixel(it.first, it.second) } }
                    if (px != null && hypot(px[0] - x, px[1] - y) < 12f) hitsOk++
                    session.scratchAt(px); x += 40f
                }
                session.scratchAt(null); y += 26f
            }
        }
        repeat(30) { session.update(0.05f); delay(30) }
        dump("card_done.png")
        Log.i("ZoharSmoke", "scratch mapping $hitsOk/$tries finished=${session.finished} lastWin=${session.lastWin} credits=${session.credits} wallet=${PlayWallet.credits}")
            }
    Box(Modifier.fillMaxSize()) {
        SceneView(
            modifier = Modifier.fillMaxSize(), engine = engine, modelLoader = modelLoader, view = view,
            frameRatePolicy = if (smoke) FrameRatePolicy.OnDemand(maxFps = 6) else FrameRatePolicy.Continuous(),
            autoCenterContent = false, cameraManipulator = null,
            cameraNode = rememberCameraNode(engine) { position = Position(0f, 0.42f, 0.32f); lookAt(Position(0f, 0f, 0.02f)) },
            onFrame = { ns ->
                val dt = if (last.v == 0L) 1f / 60 else ((ns - last.v) / 1e9f).coerceIn(0f, 0.1f); last.v = ns
                if (session.update(dt)) needUpload.v = true
                card?.let { if (needUpload.v && it.upload(session.renderer.bitmap)) needUpload.v = false }
            },
        ) { cardModel?.let { ModelNode(modelInstance = it, autoAnimate = false) } }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp)
            .onGloballyPositioned { c -> c.boundsInWindow().let { b -> TouchBus.blocked["controls"] = android.graphics.RectF(b.left, b.top, b.right, b.bottom) } },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("NO-AR VIEW · CREDIT ${session.credits} · ${session.message}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!session.hasCard || session.finished) Ctl("BUY ${session.tier.label}", primary = true, wide = true) { session.buy() } else Ctl("REVEAL ALL", primary = true, wide = true) { session.revealAll() }
                onBack?.let { Ctl("AR", small = true) { it() } }
            }
        }
    }
}
