package com.zohar.slotar.moneywheel

import com.zohar.slotar.blackjack.Wallet
import com.zohar.slotar.blackjack.SeededRng
import com.zohar.slotar.blackjack.SecureRng

import android.os.SystemClock
import android.view.MotionEvent
import io.github.sceneview.utils.screenToRay
import io.github.sceneview.utils.worldToScreen
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import com.zohar.slotar.Ctl
import com.zohar.slotar.FX_FILES
import com.zohar.slotar.FX_SECONDS
import com.zohar.slotar.Fx
import com.zohar.slotar.Pill
import com.zohar.slotar.audio.SlotSounds
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.SceneView
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.scene.SceneUnderstanding
import io.github.sceneview.createARView
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.rememberARView
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberView
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private class Ref<T>(var v: T)
private val nf: NumberFormat = NumberFormat.getIntegerInstance(Locale.US)

private val ODDS_TEXT = "Play money only · nothing to buy, nothing to cash out.\n" +
    "The wheel has 54 segments: 1 ×23 · 2 ×15 · 5 ×7 · 10 ×4 · 20 ×2 · 40 ×1 · 2x ×1 · 7x ×1. Every spin is drawn by the app's random generator (each segment exactly 1 in 54) before the host pulls the wheel.\n" +
    "A bet on a number wins when the wheel stops on it and pays that number to 1. On 2x or 7x the wheel spins again and the next win is multiplied (multipliers in a row multiply together).\n" +
    "Exact return over all spins and multipliers: " + MoneyWheel.NUMBERS.joinToString(" · ") { "$it → ${"%.2f".format(MoneyWheel.rtp(it) * 100)} %" } + ".\n" +
    "Limits: 5 minimum a spin · up to 500 on a number · 2,000 a spin."

/** A tap on the screen → the felt under it (table space) → place the chip in hand if it is a betting spot. */
private fun feltTap(view: com.google.android.filament.View, table: MoneyWheelTable, x: Float, y: Float) {
    val r = view.screenToRay(x, y) ?: return; val f = table.frame ?: return
    val inv = runCatching { dev.romainguy.kotlin.math.inverse(f.worldTransform) }.getOrNull() ?: return
    val o = (inv * dev.romainguy.kotlin.math.Float4(r.origin.x, r.origin.y, r.origin.z, 1f)).xyz
    val d = (inv * dev.romainguy.kotlin.math.Float4(r.direction.x, r.direction.y, r.direction.z, 0f)).xyz
    if (kotlin.math.abs(d.y) < 1e-6f) return
    val k = (table.tapY - o.y) / d.y; if (k < 0) return
    table.tapFelt(o.x + d.x * k, o.z + d.z * k)
}
/** Taps (not drags) on the 3D view go to the felt; the HUD areas are excluded through TouchBus.blocked. */
@Composable
private fun FeltTaps(view: com.google.android.filament.View, table: MoneyWheelTable, enabled: () -> Boolean) {
    DisposableEffect(table) {
        var dx = 0f; var dy = 0f
        com.zohar.slotar.TouchBus.handler = { e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.x; dy = e.y }
                MotionEvent.ACTION_UP -> if (enabled() && kotlin.math.hypot(e.x - dx, e.y - dy) < 28f) feltTap(view, table, e.x, e.y)
            }
        }
        onDispose { com.zohar.slotar.TouchBus.handler = null; com.zohar.slotar.TouchBus.blocked.clear() }
    }
}
/** Where the dealer's head is on screen (for the speech bubble). */
private fun headOnScreen(view: com.google.android.filament.View, table: MoneyWheelTable): androidx.compose.ui.geometry.Offset? =
    table.dealer?.headWorld()?.let { h -> view.worldToScreen(Position(h[0], h[1], h[2])) }?.let { p -> androidx.compose.ui.geometry.Offset(p.x, p.y) }

/** Casino Hold'em in AR: a real-size table on your floor, the dealer behind it, you in the player's seat. */
@Composable
fun MoneyWheelAr(smoke: Boolean = false, onNoAr: () -> Unit) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val view = rememberARView(engine) {
        createARView(engine).apply {
            multiSampleAntiAliasingOptions = multiSampleAntiAliasingOptions.apply { enabled = true; sampleCount = 4 }
            bloomOptions = bloomOptions.apply { enabled = true; strength = 0.08f; threshold = true }
            ambientOcclusionOptions = ambientOcclusionOptions.apply { enabled = true }
        }
    }
    val scope = rememberCoroutineScope()
    val sounds = remember { SlotSounds(context) }
    val fxTokens = remember { mutableStateMapOf<Fx, Int>() }
    val fxActive = remember { mutableStateMapOf<Fx, Boolean>() }
    val table = remember { MoneyWheelTable(context, engine, modelLoader, sounds, scope, { fx -> fxTokens[fx] = (fxTokens[fx] ?: 0) + 1 }) }
    DisposableEffect(Unit) { onDispose { table.close(); sounds.close() } }
    val tableModel = rememberModelInstance(modelLoader, "models/mw_stage.glb")
    val dealerModel = rememberModelInstance(modelLoader, "models/host.glb")
    val reticleModel = rememberModelInstance(modelLoader, "models/reticle.glb")

    var anchor by remember { mutableStateOf<Anchor?>(null) }
    var anchorPlane by remember { mutableStateOf<Plane?>(null) }
    var canPlace by remember { mutableStateOf(false) }
    var tracking by remember { mutableStateOf(false) }
    var viewW by remember { mutableIntStateOf(0) }
    var viewH by remember { mutableIntStateOf(0) }
    var scanSecs by remember { mutableFloatStateOf(0f) }
    var mini by remember { mutableStateOf(false) }
    var showOdds by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    val candidate = remember { Ref<Pair<Pose, com.google.ar.core.Trackable>?>(null) }
    val smooth = remember { Ref<FloatArray?>(null) }
    val lastTs = remember { Ref(0L) }
    val placedRoot = remember { Ref<io.github.sceneview.node.Node?>(null) }
    val frameRef = remember { Ref<io.github.sceneview.node.Node?>(null) }
    val dealerRef = remember { Ref<io.github.sceneview.node.ModelNode?>(null) }
    val tableRef = remember { Ref<io.github.sceneview.node.ModelNode?>(null) }
    val reticleRef = remember { Ref<io.github.sceneview.node.Node?>(null) }
    val t0 = remember { SystemClock.uptimeMillis() }
    val camPose = remember { Ref<Pose?>(null) }
    var bubble by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    FeltTaps(view, table) { anchor != null }
    var seated by remember { mutableStateOf(false) }
    var seat by remember { mutableStateOf(floatArrayOf(0f, 0f, 0f)) }      // x, lift, shift toward the player (anchor space)
    // Seated view: bring the table up and in, so its rail is at the chest of a seated player (eye ≈ 42 cm above the felt)
    fun computeSeat(sc: Float) {
        val a = anchor ?: return; val c = camPose.v ?: return
        val l = a.pose.inverse().transformPoint(floatArrayOf(c.tx(), c.ty(), c.tz()))
        val lift = (l[1] - 0.42f * sc - table.tapY * sc).coerceIn(0f, 1.0f)
        val shift = (l[2] - 0.30f * sc).coerceIn(-1.5f, 1.5f)
        seat = floatArrayOf(l[0].coerceIn(-1f, 1f), lift, shift)
    }

    fun place() {
        val c = candidate.v ?: return
        anchor?.detach()
        anchor = runCatching { c.second.createAnchor(c.first) }.getOrNull() ?: return
        anchorPlane = c.second as? Plane; sounds.play("click")
    }
    for (fx in Fx.entries) {
        val tok = fxTokens[fx] ?: 0
        LaunchedEffect(fx, tok) { if (tok == 0) return@LaunchedEffect; fxActive[fx] = true; delay((FX_SECONDS.getValue(fx) * 1000).toLong()); fxActive[fx] = false }
    }
    val s = if (mini) 0.3f else 1f

    Box(Modifier.fillMaxSize().onSizeChanged { viewW = it.width; viewH = it.height }) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine, modelLoader = modelLoader, materialLoader = materialLoader, view = view,
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL,
            depthMode = Config.DepthMode.AUTOMATIC,
            instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP,
            sceneUnderstanding = SceneUnderstanding(occlusion = false, lighting = true, physics = false, planeVisualization = anchor == null),
            onSessionUpdated = { arSession, frame ->
                val ts = frame.timestamp; val dt = if (lastTs.v == 0L) 1f / 30 else ((ts - lastTs.v) / 1e9f).coerceIn(0f, 0.1f); lastTs.v = ts
                tracking = frame.camera.trackingState == TrackingState.TRACKING
                camPose.v = frame.camera.displayOrientedPose
                if (smoke && anchor == null && tracking && scanSecs > 3f) {
                    // CI: no planes in the virtual room needed — put the table 1.4 m ahead on the floor below the camera
                    val cam = frame.camera.pose; val fz = cam.zAxis; val hl = kotlin.math.sqrt(fz[0] * fz[0] + fz[2] * fz[2]).coerceAtLeast(1e-3f)
                    val px = cam.tx() - fz[0] / hl * 0.6f; val pz = cam.tz() - fz[2] / hl * 0.6f; val py = cam.ty() - 1.2f
                    val yaw = atan2((cam.tx() - px).toDouble(), (cam.tz() - pz).toDouble())
                    anchor = runCatching { arSession.createAnchor(Pose(floatArrayOf(px, py, pz), floatArrayOf(0f, sin(yaw / 2).toFloat(), 0f, cos(yaw / 2).toFloat()))) }.getOrNull()
                    Log.i("ZoharSmoke", "bj AR placed=${anchor != null}")
                }
                if (anchor == null) {
                    if (tracking) scanSecs += dt
                    val ax = viewW / 2f; val ay = viewH * 0.62f
                    val hits = if (viewW > 0) frame.hitTest(ax, ay) else emptyList()
                    fun ok(h: com.google.ar.core.HitResult, inside: Boolean): Boolean {
                        val p = h.trackable as? Plane ?: return false
                        return p.type == Plane.Type.HORIZONTAL_UPWARD_FACING && p.trackingState == TrackingState.TRACKING && h.distance < 5f && (!inside || p.isPoseInPolygon(h.hitPose)) && p.subsumedBy == null
                    }
                    var hit = hits.firstOrNull { ok(it, true) } ?: hits.firstOrNull { ok(it, false) }
                    if (hit == null && scanSecs > 6f && viewW > 0) hit = frame.hitTestInstantPlacement(ax, ay, 1.2f).firstOrNull()
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
                    val f = frameRef.v; val d = dealerRef.v
                    val sc = if (mini) 0.3f else 1f
                    f?.position = if (seated) Position(seat[0], seat[1], -table.M.front * sc + seat[2]) else Position(0f, 0f, -table.M.front * sc)
                    if (f != null && d != null) table.attach(f, d)
                    frame.camera.displayOrientedPose.let { cp -> table.cameraWorld = floatArrayOf(cp.tx(), cp.ty(), cp.tz()) }
                    table.update((SystemClock.uptimeMillis() - t0) / 1000.0, dt)
                    headOnScreen(view, table).let { h -> if (h == null || bubble == null || (h - bubble!!).getDistance() > 3f) bubble = h }
                }
            },
        ) {
            reticleModel?.let { ModelNode(modelInstance = it, autoAnimate = false, isVisible = anchor == null && canPlace, scale = Scale(0.6f), apply = { reticleRef.v = this }) }
            anchorPlane?.let { ShadowReceiverPlane(plane = it) }
            Node(apply = { placedRoot.v = this }) {
                // the rail edge in front of you sits where you aimed; the table and the dealer are behind it
                // the frame stays visible (late-added children of a hidden parent stay hidden); the models hide while placing
                val fp = if (seated) Position(seat[0], seat[1], -table.M.front * s + seat[2]) else Position(0f, 0f, -table.M.front * s)
                Node(position = fp, scale = Scale(s), apply = { frameRef.v = this; position = fp }) {
                    tableModel?.let { ModelNode(modelInstance = it, autoAnimate = false, isVisible = anchor != null, apply = { tableRef.v = this }) }
                    dealerModel?.let { ModelNode(modelInstance = it, autoAnimate = false, isVisible = anchor != null, position = Position(table.M.host.x, table.M.host.y, table.M.host.z), apply = { dealerRef.v = this; rotation = io.github.sceneview.math.Rotation(0f, table.M.hostYaw, 0f) }) }
                    for (fx in Fx.entries) {
                        val tok = fxTokens[fx] ?: 0
                        if (tok > 0 && fxActive[fx] == true) key(fx, tok) {
                            rememberModelInstance(modelLoader, FX_FILES.getValue(fx))?.let { ModelNode(modelInstance = it, autoAnimate = true, animationLoop = false, position = Position(0f, table.tapY - 0.1f, 0.5f), scale = Scale(0.45f)) }
                        }
                    }
                }
            }
        }
        val msg = when {
            anchor == null && !tracking -> "Move the phone slowly to scan the floor"
            anchor == null && !canPlace -> "Aim at the floor about half a metre in front of you"
            anchor == null -> "The table edge goes where the ring is · tap Place table"
            else -> null
        }
        MoneyWheelHud(table, placed = anchor != null, msg = msg, canPlace = canPlace, onPlace = { place() }, mini = mini, onMini = { mini = !mini; seated = false },
            onMove = { anchor?.detach(); anchor = null; anchorPlane = null; scanSecs = 0f; seated = false }, onNoAr = onNoAr,
            muted = muted, onMute = { muted = !muted; sounds.muted = muted; table.voice.muted = muted }, showOdds = showOdds, onOdds = { showOdds = !showOdds },
            seated = seated, onSeated = { if (!seated) computeSeat(s); seated = !seated }, bubble = if (anchor != null) bubble else null)
    }
}

/** Same table and dealer without AR (phones without ARCore, and the CI self-test that plays real rounds). */
@Composable
fun MoneyWheelPreview(smoke: Boolean = false, onAr: (() -> Unit)? = null, parts: Set<String> = setOf("table", "dealer")) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val view = rememberView(engine)
    val scope = rememberCoroutineScope()
    val sounds = remember { SlotSounds(context) }
    val table = remember { MoneyWheelTable(context, engine, modelLoader, sounds, scope, {}, rng = if (smoke) SeededRng(7) else SecureRng, wallet = if (smoke) object : Wallet { override var credits = 1000L } else null, speech = !smoke) }
    DisposableEffect(Unit) { onDispose { table.close(); sounds.close() } }
    val tableModel = rememberModelInstance(modelLoader, "models/mw_stage.glb")
    // the emulator's SwiftShader GL counts the skinning uniform block against its 256 vertex vectors and fails to link,
    // so the CI self-test uses the same dealer rig without skin/morphs (rigid mesh; debug builds only ship that file)
    val dealerAsset = "models/host.glb"
    LaunchedEffect(dealerAsset) { Log.i("ZoharSmoke", "bj dealer asset=$dealerAsset") }
    val dealerModel = if ("dealer" in parts) rememberModelInstance(modelLoader, dealerAsset) else null
    
    val frameRef = remember { Ref<io.github.sceneview.node.Node?>(null) }
    val dealerRef = remember { Ref<io.github.sceneview.node.ModelNode?>(null) }
    val tableRef = remember { Ref<io.github.sceneview.node.ModelNode?>(null) }
    val t0 = remember { SystemClock.uptimeMillis() }
    val last = remember { Ref(0L) }
    var showOdds by remember { mutableStateOf(false) }
    val eye = floatArrayOf(0f, 1.32f, 2.6f)
    val cameraNode = rememberCameraNode(engine) { position = Position(eye[0], eye[1], eye[2]); lookAt(Position(0.15f, 1.25f, 0.2f)) }
    var seatedView by remember { mutableStateOf(true) }
    var bubble by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    FeltTaps(view, table) { true }
    LaunchedEffect(seatedView) {
        if (seatedView) { cameraNode.position = Position(0f, 1.32f, 2.6f); cameraNode.lookAt(Position(0.15f, 1.25f, 0.2f)) }
        else { cameraNode.position = Position(0f, 1.7f, 3.0f); cameraNode.lookAt(Position(0.1f, 1.15f, 0.4f)) }
    }

    Box(Modifier.fillMaxSize()) {
        SceneView(
            modifier = Modifier.fillMaxSize(), engine = engine, modelLoader = modelLoader, view = view,
            frameRatePolicy = FrameRatePolicy.Continuous(), autoCenterContent = false, cameraNode = cameraNode,
            cameraManipulator = if (smoke) null else rememberCameraManipulator(orbitHomePosition = Position(eye[0], eye[1], eye[2]), targetPosition = Position(0.15f, 1.25f, 0.2f)),
            onFrame = { ns ->
                val dt = if (last.v == 0L) 1f / 60 else ((ns - last.v) / 1e9f).coerceIn(0f, 0.1f); last.v = ns
                val f = frameRef.v; val d = dealerRef.v
                if (f != null && (d != null || "dealer" !in parts)) table.attach(f, d)
                table.cameraWorld = floatArrayOf(cameraNode.worldPosition.x, cameraNode.worldPosition.y, cameraNode.worldPosition.z)
                table.update((SystemClock.uptimeMillis() - t0) / 1000.0, dt)
                headOnScreen(view, table).let { h -> if (h == null || bubble == null || (h - bubble!!).getDistance() > 3f) bubble = h }
            },
        ) {
            Node(apply = { frameRef.v = this }) {
                tableModel?.let { ModelNode(modelInstance = it, autoAnimate = false, apply = { tableRef.v = this }) }
                dealerModel?.let { ModelNode(modelInstance = it, autoAnimate = false, position = Position(table.M.host.x, table.M.host.y, table.M.host.z), apply = { dealerRef.v = this; rotation = io.github.sceneview.math.Rotation(0f, table.M.hostYaw, 0f) }) }
            }
        }
        MoneyWheelHud(table, placed = true, msg = null, canPlace = false, onPlace = {}, mini = false, onMini = null, onMove = null, onNoAr = onAr,
            muted = false, onMute = null, showOdds = showOdds, onOdds = { showOdds = !showOdds }, noArLabel = "AR",
            seated = seatedView, onSeated = { seatedView = !seatedView }, bubble = bubble)
    }
}

@Composable
private fun MoneyWheelHud(
    t: MoneyWheelTable, placed: Boolean, msg: String?, canPlace: Boolean, onPlace: () -> Unit, mini: Boolean, onMini: (() -> Unit)?,
    onMove: (() -> Unit)?, onNoAr: (() -> Unit)?, muted: Boolean, onMute: (() -> Unit)?, showOdds: Boolean, onOdds: () -> Unit, noArLabel: String = "No AR",
    seated: Boolean = false, onSeated: (() -> Unit)? = null, bubble: androidx.compose.ui.geometry.Offset? = null,
) {
    val ctx = LocalContext.current
    // the dealer speaks through the media volume; if it is all the way down, say so once
    val mediaSilent = remember { runCatching { (ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager).getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == 0 }.getOrDefault(false) }
    // speech bubble over the dealer's head while he talks (fades after 6 s)
    var nowMs by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(t.lineAt) { nowMs = SystemClock.uptimeMillis(); while (SystemClock.uptimeMillis() - t.lineAt < 6500) { delay(250); nowMs = SystemClock.uptimeMillis() } ; nowMs = SystemClock.uptimeMillis() }
    val bubbleOn = placed && t.line.isNotEmpty() && nowMs - t.lineAt < 6000
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(Modifier.fillMaxSize()) {
        if (bubbleOn && bubble != null) {
            var bubbleSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
            Box(Modifier.offset { androidx.compose.ui.unit.IntOffset((bubble.x - bubbleSize.width / 2f).toInt(), (bubble.y - bubbleSize.height).toInt()) }.onSizeChanged { bubbleSize = it }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t.line, Modifier.widthIn(max = 260.dp).background(Color(0xF2FFFFFF), RoundedCornerShape(16.dp)).border(2.dp, Color(0xFFE9C46A), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                        color = Color(0xFF1A1410), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    androidx.compose.foundation.Canvas(Modifier.size(18.dp, 10.dp)) {
                        val path = androidx.compose.ui.graphics.Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height); close() }
                        drawPath(path, Color(0xF2FFFFFF))
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(10.dp).onGloballyPositioned { c -> c.boundsInWindow().let { b -> com.zohar.slotar.TouchBus.blocked["bjTop"] = android.graphics.RectF(b.left, b.top, b.right, b.bottom) } },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("CREDIT ${nf.format(t.credits)}" + (if (t.staked > 0) " · BET ${nf.format(t.staked)}" else "") + " · PLAY MONEY")
            if (placed && t.history.isNotEmpty()) Road(t.history)
            if (placed && t.multiplier > 1) Text("×${t.multiplier} MULTIPLIER", Modifier.background(Brush.horizontalGradient(listOf(Color(0xFFFFE9A8), Color(0xFFC7962C))), RoundedCornerShape(16.dp)).border(2.dp, Color.White, RoundedCornerShape(16.dp)).padding(horizontal = 18.dp, vertical = 6.dp), color = Color(0xFF3A2400), fontSize = 26.sp, fontWeight = FontWeight.Black)
            if (placed && t.landed != null && t.ui == MUi.BUSY) t.landed?.let { seg -> SegBadge(seg, big = true) }
            if (msg != null) Pill(msg)
            if (placed) {
                if (mediaSilent) Pill("🔈 Turn up the media volume to hear the dealer")
                if (t.line.isNotEmpty() && bubble == null) Text("“${t.line}”", Modifier.widthIn(max = 360.dp).background(Color(0xB0000000), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp), color = Color(0xFFFFE6A8), fontSize = 15.sp, textAlign = TextAlign.Center)
                
                if (t.outcome.isNotEmpty() && t.ui == MUi.BETTING) Text(t.outcome, color = if (t.outcome.startsWith("YOU WIN")) Color(0xFF5DFF9E) else Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
            }
            if (showOdds) Text(ODDS_TEXT, Modifier.widthIn(max = 360.dp).background(Color(0xE612141C), RoundedCornerShape(12.dp)).padding(12.dp), color = Color.White, fontSize = 13.sp)
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 10.dp, end = 10.dp, bottom = 14.dp).fillMaxWidth()
            .onGloballyPositioned { c -> c.boundsInWindow().let { b -> com.zohar.slotar.TouchBus.blocked["bjBottom"] = android.graphics.RectF(b.left, b.top, b.right, b.bottom) } },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!placed) {
                Ctl(if (canPlace) "Place table" else "Finding the floor…", enabled = canPlace, primary = true, wide = true) { onPlace() }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onMini?.let { Ctl(if (mini) "Size: tabletop" else "Size: real", small = true) { it() } }
                    onNoAr?.let { Ctl("View without AR", small = true) { it() } }
                }
            } else {
                when (t.ui) {
                    MUi.BETTING -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (n in MoneyWheel.NUMBERS) {
                                val st = SEG.getValue(n.toString()); val amt = t.bets[n] ?: 0L
                                Box(Modifier.size(width = 52.dp, height = 58.dp).clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(listOf(st.first, st.second))).border(if (amt > 0) 3.dp else 1.dp, if (amt > 0) Color.White else Color(0x66FFFFFF), RoundedCornerShape(12.dp)).clickable { t.bet(n) }, contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(n.toString(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                                        Text(if (amt > 0) nf.format(amt) else "$n:1", color = Color.White.copy(alpha = 0.9f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            for ((v, c) in listOf(5L to Color(0xFFC8262E), 25L to Color(0xFF1E8A3E), 100L to Color(0xFF151515), 500L to Color(0xFF5B2A86)))
                                Chip(v, c, enabled = v <= t.credits, selected = t.chipValue == v) { t.chipValue = v; t.touched() }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (t.canRefill()) Ctl("Free refill", primary = true, wide = true) { t.refill() }
                            else {
                                Ctl("Undo", small = true, enabled = t.staked > 0) { t.undo() }
                                if (t.canRebet()) Ctl("Rebet", small = true) { t.rebet() } else Ctl("Clear", small = true, enabled = t.staked > 0) { t.clearBets() }
                                Ctl("SPIN", primary = true, wide = true, enabled = t.canSpin()) { t.spin() }
                            }
                        }
                    }
                    MUi.BUSY -> Text("…", color = Color.White, fontSize = 22.sp)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onSeated?.let { Ctl(if (seated) "View: seated" else "View: standing", small = true) { it() } }
                    Ctl(if (showOdds) "Odds ✕" else "Odds & rules", small = true) { onOdds() }
                    onMute?.let { Ctl(if (muted) "Sound: off" else "Sound: on", small = true) { it() } }
                    onMini?.let { Ctl(if (mini) "Size: tabletop" else "Size: real", small = true) { it() } }
                    onMove?.let { Ctl("Move", small = true) { it() } }
                    onNoAr?.let { Ctl(noArLabel, small = true) { it() } }
                }
            }
        }
    }
}

private val SEG = mapOf(
    "1" to (Color(0xFFFFD84A) to Color(0xFFD99A0A)), "2" to (Color(0xFF4AA3FF) to Color(0xFF1B58C9)), "5" to (Color(0xFFC169F0) to Color(0xFF6E1FA8)),
    "10" to (Color(0xFF3FE08A) to Color(0xFF0F8F4A)), "20" to (Color(0xFFFF9F3A) to Color(0xFFC4580A)), "40" to (Color(0xFFFF4B55) to Color(0xFFA8101A)),
    "2x" to (Color(0xFFF2F4F8) to Color(0xFF8E96A3)), "7x" to (Color(0xFFFFE9A8) to Color(0xFFC7962C)),
)
@Composable
private fun SegBadge(seg: String, big: Boolean = false) {
    val (a, b) = SEG.getValue(seg); val dark = seg.endsWith("x")
    Box(Modifier.size(if (big) 64.dp else 24.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(a, b))).border(if (big) 3.dp else 1.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
        Text(seg, color = if (dark) Color(0xFF2A1A00) else Color.White, fontSize = if (big) 24.sp else 10.sp, fontWeight = FontWeight.Black)
    }
}
/** The last results, newest first. */
@Composable
private fun Road(h: List<String>) {
    Row(Modifier.background(Color(0xB0000000), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        h.take(12).forEach { SegBadge(it) }
    }
}

@Composable
private fun Chip(v: Long, c: Color, enabled: Boolean, selected: Boolean = false, onClick: () -> Unit) {
    // the same chip art as on the table (rendered from the 3D chip's face)
    val ctx = LocalContext.current
    val img = remember(v) { runCatching { ctx.assets.open("cards/chip_$v.png").use { android.graphics.BitmapFactory.decodeStream(it) }.asImageBitmap() }.getOrNull() }
    Box(Modifier.size(if (selected) 58.dp else 50.dp).then(if (selected) Modifier.border(3.dp, Color(0xFFFFD86B), CircleShape) else Modifier).clip(CircleShape).clickable(enabled = enabled) { onClick() }, contentAlignment = Alignment.Center) {
        if (img != null) androidx.compose.foundation.Image(img, contentDescription = "Chip $v", modifier = Modifier.fillMaxSize(), alpha = if (enabled) 1f else 0.35f)
        else Box(Modifier.fillMaxSize().background(c.copy(alpha = if (enabled) 1f else 0.35f), CircleShape).border(4.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
            Text(nf.format(v), color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp)
        }
    }
}
