package com.zohar.slotar.plinko

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.RowScope
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
import com.zohar.slotar.BetSlider
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


private fun oddsText(risk: PlinkoRisk) = "Play money only · nothing to buy, nothing to cash out.\n" +
    "Plinko, 12 rows of pegs, 13 pockets. The ball's whole way down is drawn before it is let go: one fair left-or-right at each row. The ball on the board follows exactly that way.\n" +
    "The pocket pays the bet times its multiplier. There are 4096 ways down; the middle pocket takes 924 of them, each edge pocket only 1.\n" +
    "${risk.label} board, edge to middle: " + (0..6).joinToString(" · ") { "×${Plinko.label(Plinko.fifths(risk, it))} (${Plinko.ways(it)}${if (it < 6) " ×2" else ""})" } + ".\n" +
    "Return to player: Low ${"%.2f".format(Locale.US, Plinko.rtp(PlinkoRisk.LOW) * 100)} % · Medium ${"%.2f".format(Locale.US, Plinko.rtp(PlinkoRisk.MEDIUM) * 100)} % · High ${"%.2f".format(Locale.US, Plinko.rtp(PlinkoRisk.HIGH) * 100)} %. The boards differ in how the prizes are spread, not in what comes back.\n" +
    "Limits: bet 5–500, in steps of 5."

/** Plinko in AR: the neon board stands on your floor, about two metres tall. */
@Composable
fun PlinkoAr(smoke: Boolean = false, onNoAr: () -> Unit) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val view = rememberARView(engine) {
        createARView(engine).apply {
            multiSampleAntiAliasingOptions = multiSampleAntiAliasingOptions.apply { enabled = true; sampleCount = 4 }
            // the board is made of light: a stronger bloom than on the card tables gives the tubes and pegs their halo
            bloomOptions = bloomOptions.apply { enabled = true; strength = 0.22f; threshold = true }
        }
    }
    val scope = rememberCoroutineScope()
    val sounds = remember { SlotSounds(context) }
    val fxTokens = remember { mutableStateMapOf<Fx, Int>() }
    val fxActive = remember { mutableStateMapOf<Fx, Boolean>() }
    val table = remember { PlinkoTable(context, modelLoader, sounds, scope, { fx -> fxTokens[fx] = (fxTokens[fx] ?: 0) + 1 }) }
    DisposableEffect(Unit) { onDispose { table.close(); sounds.close() } }
    val boardModel = rememberModelInstance(modelLoader, "models/plinko_board.glb")
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
    val reticleRef = remember { Ref<io.github.sceneview.node.Node?>(null) }
    val t0 = remember { SystemClock.uptimeMillis() }

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
                if (smoke && anchor == null && tracking && scanSecs > 3f) {
                    val cam = frame.camera.pose; val fz = cam.zAxis; val hl = kotlin.math.sqrt(fz[0] * fz[0] + fz[2] * fz[2]).coerceAtLeast(1e-3f)
                    val px = cam.tx() - fz[0] / hl * 2.0f; val pz = cam.tz() - fz[2] / hl * 2.0f; val py = cam.ty() - 1.2f
                    val yaw = atan2((cam.tx() - px).toDouble(), (cam.tz() - pz).toDouble())
                    anchor = runCatching { arSession.createAnchor(Pose(floatArrayOf(px, py, pz), floatArrayOf(0f, sin(yaw / 2).toFloat(), 0f, cos(yaw / 2).toFloat()))) }.getOrNull()
                    Log.i("ZoharSmoke", "plinko AR placed=${anchor != null}")
                }
                if (anchor == null) {
                    if (tracking) scanSecs += dt
                    val ax = viewW / 2f; val ay = viewH * 0.62f
                    val hits = if (viewW > 0) frame.hitTest(ax, ay) else emptyList()
                    fun ok(h: com.google.ar.core.HitResult, inside: Boolean): Boolean {
                        val p = h.trackable as? Plane ?: return false
                        return p.type == Plane.Type.HORIZONTAL_UPWARD_FACING && p.trackingState == TrackingState.TRACKING && h.distance < 6f && (!inside || p.isPoseInPolygon(h.hitPose)) && p.subsumedBy == null
                    }
                    var hit = hits.firstOrNull { ok(it, true) } ?: hits.firstOrNull { ok(it, false) }
                    if (hit == null && scanSecs > 6f && viewW > 0) hit = frame.hitTestInstantPlacement(ax, ay, 2.0f).firstOrNull()
                    if (hit != null) {
                        val raw = hit.hitPose; val cam = frame.camera.pose
                        val sm = smooth.v?.let { o -> floatArrayOf(o[0] + (raw.tx() - o[0]) * 0.3f, o[1] + (raw.ty() - o[1]) * 0.3f, o[2] + (raw.tz() - o[2]) * 0.3f) } ?: floatArrayOf(raw.tx(), raw.ty(), raw.tz())
                        smooth.v = sm
                        // the board turns its face to you
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
                    frameRef.v?.let { table.attach(it) }
                    table.update((SystemClock.uptimeMillis() - t0) / 1000.0)
                }
            },
        ) {
            reticleModel?.let { ModelNode(modelInstance = it, autoAnimate = true, animationLoop = true, isVisible = anchor == null && canPlace, scale = Scale(0.6f), apply = { reticleRef.v = this }) }
            anchorPlane?.let { ShadowReceiverPlane(plane = it) }
            Node(apply = { placedRoot.v = this }) {
                // the board stands on the spot you aimed at; the frame stays visible (late-added children of a hidden parent stay hidden)
                Node(scale = Scale(s), apply = { frameRef.v = this }) {
                    boardModel?.let { ModelNode(modelInstance = it, autoAnimate = false, isVisible = anchor != null) }
                    for (fx in Fx.entries) {
                        val tok = fxTokens[fx] ?: 0
                        if (tok > 0 && fxActive[fx] == true) key(fx, tok) {
                            rememberModelInstance(modelLoader, FX_FILES.getValue(fx))?.let { ModelNode(modelInstance = it, autoAnimate = true, animationLoop = false, position = Position(0f, 0.55f, 0.25f), scale = Scale(0.45f)) }
                        }
                    }
                }
            }
        }
        val msg = when {
            anchor == null && !tracking -> "Move the phone slowly to scan the floor"
            anchor == null && !canPlace -> "Aim at the floor two steps in front of you"
            anchor == null -> "The board will stand where the ring is · tap Place board"
            else -> null
        }
        PlinkoHud(table, placed = anchor != null, msg = msg, canPlace = canPlace, onPlace = { place() }, mini = mini, onMini = { mini = !mini },
            onMove = { anchor?.detach(); anchor = null; anchorPlane = null; scanSecs = 0f }, onNoAr = onNoAr,
            muted = muted, onMute = { muted = !muted; sounds.muted = muted }, showOdds = showOdds, onOdds = { showOdds = !showOdds })
    }
}

/** The same board without AR (phones without ARCore). */
@Composable
fun PlinkoPreview(onAr: (() -> Unit)? = null) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val view = rememberView(engine)
    val scope = rememberCoroutineScope()
    val sounds = remember { SlotSounds(context) }
    val table = remember { PlinkoTable(context, modelLoader, sounds, scope, {}) }
    DisposableEffect(Unit) { onDispose { table.close(); sounds.close() } }
    val boardModel = rememberModelInstance(modelLoader, "models/plinko_board.glb")
    val frameRef = remember { Ref<io.github.sceneview.node.Node?>(null) }
    val t0 = remember { SystemClock.uptimeMillis() }
    var showOdds by remember { mutableStateOf(false) }
    val eye = table.seatEye
    val cameraNode = rememberCameraNode(engine) { position = Position(eye[0], eye[1], eye[2]); lookAt(Position(0f, 1.05f, 0f)) }

    Box(Modifier.fillMaxSize()) {
        SceneView(
            modifier = Modifier.fillMaxSize(), engine = engine, modelLoader = modelLoader, view = view,
            frameRatePolicy = FrameRatePolicy.Continuous(), autoCenterContent = false, cameraNode = cameraNode,
            cameraManipulator = rememberCameraManipulator(orbitHomePosition = Position(eye[0], eye[1], eye[2]), targetPosition = Position(0f, 1.05f, 0f)),
            onFrame = { _ ->
                frameRef.v?.let { table.attach(it) }
                table.update((SystemClock.uptimeMillis() - t0) / 1000.0)
            },
        ) {
            Node(apply = { frameRef.v = this }) {
                boardModel?.let { ModelNode(modelInstance = it, autoAnimate = false) }
            }
        }
        PlinkoHud(table, placed = true, msg = null, canPlace = false, onPlace = {}, mini = false, onMini = null, onMove = null, onNoAr = onAr,
            muted = false, onMute = null, showOdds = showOdds, onOdds = { showOdds = !showOdds }, noArLabel = "AR")
    }
}

@Composable
private fun PlinkoHud(
    t: PlinkoTable, placed: Boolean, msg: String?, canPlace: Boolean, onPlace: () -> Unit, mini: Boolean, onMini: (() -> Unit)?,
    onMove: (() -> Unit)?, onNoAr: (() -> Unit)?, muted: Boolean, onMute: (() -> Unit)?, showOdds: Boolean, onOdds: () -> Unit, noArLabel: String = "No AR",
) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("CREDIT ${nf.format(t.credits)} · BET ${nf.format(t.bet)} · PLAY MONEY")
            if (msg != null) Pill(msg)
            if (placed) {
                t.lastDrop?.let { d ->
                    Row(Modifier.background(Color(0xCC12141C), RoundedCornerShape(16.dp)).border(2.dp, pocketColor(d.pocket), RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("×${Plinko.label(d.fifths)}", color = pocketColor(d.pocket), fontSize = 34.sp, fontWeight = FontWeight.Black)
                    }
                }
                if (t.outcome.isNotEmpty() && t.ui == PUi.BETTING) Text(t.outcome, color = if (t.outcome.startsWith("YOU WIN")) Color(0xFF5DFF9E) else Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                if (t.history.size > 1) Row(Modifier.background(Color(0xB0000000), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (f in t.history) Text("×${Plinko.label(f)}", color = if (f >= 50) Color(0xFFFF6FB5) else if (f >= 10) Color(0xFFFFE14A) else if (f >= 5) Color.White else Color.White.copy(alpha = 0.55f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                if (t.message.isNotEmpty()) Pill(t.message)
            }
            if (showOdds) Text(oddsText(t.risk), Modifier.widthIn(max = 360.dp).background(Color(0xE612141C), RoundedCornerShape(12.dp)).padding(12.dp), color = Color.White, fontSize = 13.sp)
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 10.dp, end = 10.dp, bottom = 14.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!placed) {
                Ctl(if (canPlace) "Place board" else "Finding the floor…", enabled = canPlace, primary = true, wide = true) { onPlace() }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onMini?.let { Ctl(if (mini) "Size: tabletop" else "Size: real", small = true) { it() } }
                    onNoAr?.let { Ctl("View without AR", small = true) { it() } }
                }
            } else {
                val busy = t.ui != PUi.BETTING
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("RISK", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    for (r in PlinkoRisk.entries) Ctl(r.label, small = true, primary = t.risk == r, enabled = !busy) { t.chooseRisk(r) }
                }
                BetSlider(t.chipValue, enabled = !busy) { t.chipValue = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Ctl("Remove bet", small = true, enabled = !busy && t.bet > 0) { t.clearBet() }
                    Ctl("Place bet", small = true, enabled = !busy && t.bet + t.chipValue <= t.credits) { t.addChip(t.chipValue) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (t.canRefill()) Ctl("Free refill", primary = true, wide = true) { t.refill() }
                    else {
                        Ctl(if (busy) "…" else "DROP", primary = true, wide = true, enabled = t.canDrop()) { t.drop() }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

/** The pocket colours of the board, centre to edge. */
private fun pocketColor(k: Int) = listOf(Color(0xFF2F7BFF), Color(0xFF22B8FF), Color(0xFF19E0C0), Color(0xFF5DFF7A), Color(0xFFFFE14A), Color(0xFFFF9A2E), Color(0xFFFF3D9A))[kotlin.math.abs(k - Plinko.ROWS / 2)]

@Composable
private fun Chip(v: Long, c: Color, enabled: Boolean, selected: Boolean = false, onClick: () -> Unit) {
    // the same chip art as on the table (rendered from the 3D chip's face)
    val ctx = LocalContext.current
    val img = remember(v) { runCatching { ctx.assets.open("cards/chip_$v.png").use { android.graphics.BitmapFactory.decodeStream(it) }.asImageBitmap() }.getOrNull() }
    Box(Modifier.size(if (selected) 68.dp else 60.dp).then(if (selected) Modifier.border(3.dp, Color(0xFFFFD86B), CircleShape) else Modifier).clip(CircleShape).clickable(enabled = enabled) { onClick() }, contentAlignment = Alignment.Center) {
        if (img != null) androidx.compose.foundation.Image(img, contentDescription = "Chip $v", modifier = Modifier.fillMaxSize(), alpha = if (enabled) 1f else 0.35f)
        else Box(Modifier.fillMaxSize().background(c.copy(alpha = if (enabled) 1f else 0.35f), CircleShape).border(4.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
            Text(nf.format(v), color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp)
        }
    }
}
