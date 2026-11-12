package com.zohar.slotar.crash

import android.os.SystemClock
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
import io.github.sceneview.utils.worldToScreen
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private class Ref<T>(var v: T)
private val nf: NumberFormat = NumberFormat.getIntegerInstance(Locale.US)
private val SKY = Color(0xFF4CC9FF); private val VIOLET = Color(0xFFB48CFF); private val GOLD = Color(0xFFFFC94A); private val RED = Color(0xFFFF4A5E); private val WIN = Color(0xFF3DFFA8)
private val GLASS = Color(0xD90A0E1C); private val LINE = Color(0x33A0C8FF)
private fun multColor(m: Long) = if (m < 200) SKY else if (m < 1000) VIOLET else GOLD

private fun oddsText() = "Play money only · nothing to buy, nothing to cash out.\n" +
    "SKY RUSH: the jet takes off and the multiplier climbs from 1.00×. The point it crashes at is drawn the moment it takes off; the flight only shows it.\n" +
    "Cash out a bet before the crash and it pays the bet times the multiplier shown. If it is still riding when the jet crashes, it is lost.\n" +
    "The chance the jet reaches a multiplier m is 97 % ÷ m: 2× in 48.5 % of rounds, 5× in 19.4 %, 10× in 9.7 %, 100× in 0.97 %. About 4 % of rounds crash at once, at 1.00×.\n" +
    "So every way of playing returns 97 % over time, whenever you cash out (less rounding a prize down to a whole credit). The top is 1,000×.\n" +
    "Two bets a round, each with its own cash-out by hand or AUTO at a multiplier you set. Bets 5–500, in steps of 5."

/** SKY RUSH in AR: the launch pad stands on your table, the jet flies through the room. */
@Composable
fun CrashAr(smoke: Boolean = false, onNoAr: () -> Unit) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val view = rememberARView(engine) {
        createARView(engine).apply {
            multiSampleAntiAliasingOptions = multiSampleAntiAliasingOptions.apply { enabled = true; sampleCount = 4 }
            // the runway lights, the trail and the fireball are made of light: bloom gives them their halo
            bloomOptions = bloomOptions.apply { enabled = true; strength = 0.24f; threshold = true }
        }
    }
    val sounds = remember { SlotSounds(context) }
    val fxTokens = remember { mutableStateMapOf<Fx, Int>() }
    val fxActive = remember { mutableStateMapOf<Fx, Boolean>() }
    val table = remember { CrashTable(modelLoader, sounds, { fx -> fxTokens[fx] = (fxTokens[fx] ?: 0) + 1 }, wallet = CrashWallet(context)) }
    DisposableEffect(Unit) { onDispose { table.close(); sounds.close() } }
    val padModel = rememberModelInstance(modelLoader, "models/crash_pad.glb")
    val reticleModel = rememberModelInstance(modelLoader, "models/reticle.glb")

    var anchor by remember { mutableStateOf<Anchor?>(null) }
    var anchorPlane by remember { mutableStateOf<Plane?>(null) }
    var canPlace by remember { mutableStateOf(false) }
    var tracking by remember { mutableStateOf(false) }
    var viewW by remember { mutableIntStateOf(0) }
    var viewH by remember { mutableIntStateOf(0) }
    var scanSecs by remember { mutableFloatStateOf(0f) }
    var big by remember { mutableStateOf(false) }
    var showOdds by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var jetAt by remember { mutableStateOf<Offset?>(null) }
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
    if (smoke) LaunchedEffect(anchor) {
        if (anchor == null) return@LaunchedEffect
        delay(2500); table.takeOff(); Log.i("ZoharSmoke", "crash AR take-off phase=${table.phase}")
        delay(4000); table.cashOut(0); Log.i("ZoharSmoke", "crash AR cash-out ${table.slots[0].cashedAt} credits=${table.credits}")
    }
    val s = if (big) 2.5f else 1f

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
                    val px = cam.tx() - fz[0] / hl * 1.0f; val pz = cam.tz() - fz[2] / hl * 1.0f; val py = cam.ty() - 0.6f
                    val yaw = atan2((cam.tx() - px).toDouble(), (cam.tz() - pz).toDouble())
                    anchor = runCatching { arSession.createAnchor(Pose(floatArrayOf(px, py, pz), floatArrayOf(0f, sin(yaw / 2).toFloat(), 0f, cos(yaw / 2).toFloat()))) }.getOrNull()
                    Log.i("ZoharSmoke", "crash AR placed=${anchor != null}")
                }
                if (anchor == null) {
                    if (tracking) scanSecs += dt
                    val ax = viewW / 2f; val ay = viewH * 0.6f
                    val hits = if (viewW > 0) frame.hitTest(ax, ay) else emptyList()
                    fun ok(h: com.google.ar.core.HitResult, inside: Boolean): Boolean {
                        val p = h.trackable as? Plane ?: return false
                        return p.type == Plane.Type.HORIZONTAL_UPWARD_FACING && p.trackingState == TrackingState.TRACKING && h.distance < 5f && (!inside || p.isPoseInPolygon(h.hitPose)) && p.subsumedBy == null
                    }
                    var hit = hits.firstOrNull { ok(it, true) } ?: hits.firstOrNull { ok(it, false) }
                    if (hit == null && scanSecs > 6f && viewW > 0) hit = frame.hitTestInstantPlacement(ax, ay, 1.0f).firstOrNull()
                    if (hit != null) {
                        val raw = hit.hitPose; val cam = frame.camera.pose
                        val sm = smooth.v?.let { o -> floatArrayOf(o[0] + (raw.tx() - o[0]) * 0.3f, o[1] + (raw.ty() - o[1]) * 0.3f, o[2] + (raw.tz() - o[2]) * 0.3f) } ?: floatArrayOf(raw.tx(), raw.ty(), raw.tz())
                        smooth.v = sm
                        // the runway lies across your view: you watch the jet take off from the side
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
                    jetAt = table.jetWorld()?.let { w -> view.worldToScreen(w) }?.let { p -> Offset(p.x, p.y) }
                }
            },
        ) {
            reticleModel?.let { ModelNode(modelInstance = it, autoAnimate = true, animationLoop = true, isVisible = anchor == null && canPlace, scale = Scale(0.5f), apply = { reticleRef.v = this }) }
            anchorPlane?.let { ShadowReceiverPlane(plane = it) }
            Node(apply = { placedRoot.v = this }) {
                Node(scale = Scale(s), apply = { frameRef.v = this }) {
                    padModel?.let { ModelNode(modelInstance = it, autoAnimate = false, isVisible = anchor != null) }
                    for (fx in Fx.entries) {
                        val tok = fxTokens[fx] ?: 0
                        if (tok > 0 && fxActive[fx] == true) key(fx, tok) {
                            rememberModelInstance(modelLoader, FX_FILES.getValue(fx))?.let { ModelNode(modelInstance = it, autoAnimate = true, animationLoop = false, position = Position(0f, 0.25f, 0.05f), scale = Scale(0.3f)) }
                        }
                    }
                }
            }
        }
        val msg = when {
            anchor == null && !tracking -> "Move the phone slowly to scan the table"
            anchor == null && !canPlace -> "Aim at a table or the floor in front of you"
            anchor == null -> "The runway goes where the ring is · tap Place runway"
            else -> null
        }
        CrashHud(table, placed = anchor != null, msg = msg, canPlace = canPlace, onPlace = { place() }, jetAt = jetAt, big = big, onBig = { big = !big },
            onMove = { anchor?.detach(); anchor = null; anchorPlane = null; scanSecs = 0f }, onNoAr = onNoAr,
            muted = muted, onMute = { muted = !muted; sounds.muted = muted }, showOdds = showOdds, onOdds = { showOdds = !showOdds })
    }
}

/** The same game without AR (phones without ARCore): the pad on a dark stage, a camera you can turn. */
@Composable
fun CrashPreview(onAr: (() -> Unit)? = null) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val view = rememberView(engine)
    val sounds = remember { SlotSounds(context) }
    val fxTokens = remember { mutableStateMapOf<Fx, Int>() }
    val table = remember { CrashTable(modelLoader, sounds, { fx -> fxTokens[fx] = (fxTokens[fx] ?: 0) + 1 }, wallet = CrashWallet(context)) }
    DisposableEffect(Unit) { onDispose { table.close(); sounds.close() } }
    val padModel = rememberModelInstance(modelLoader, "models/crash_pad.glb")
    val frameRef = remember { Ref<io.github.sceneview.node.Node?>(null) }
    val t0 = remember { SystemClock.uptimeMillis() }
    var showOdds by remember { mutableStateOf(false) }
    var jetAt by remember { mutableStateOf<Offset?>(null) }
    val eye = Position(0.25f, 1.05f, 2.1f); val look = Position(0.3f, 0.35f, -0.5f)
    val cameraNode = rememberCameraNode(engine) { position = eye; lookAt(look) }

    Box(Modifier.fillMaxSize().background(Color(0xFF070A14))) {
        SceneView(
            modifier = Modifier.fillMaxSize(), engine = engine, modelLoader = modelLoader, view = view,
            frameRatePolicy = FrameRatePolicy.Continuous(), autoCenterContent = false, cameraNode = cameraNode,
            cameraManipulator = rememberCameraManipulator(orbitHomePosition = eye, targetPosition = look),
            onFrame = { _ ->
                frameRef.v?.let { table.attach(it) }
                table.update((SystemClock.uptimeMillis() - t0) / 1000.0)
                jetAt = table.jetWorld()?.let { w -> view.worldToScreen(w) }?.let { p -> Offset(p.x, p.y) }
            },
        ) {
            Node(apply = { frameRef.v = this }) { padModel?.let { ModelNode(modelInstance = it, autoAnimate = false) } }
        }
        CrashHud(table, placed = true, msg = null, canPlace = false, onPlace = {}, jetAt = jetAt, big = false, onBig = null, onMove = null, onNoAr = onAr,
            muted = false, onMute = null, showOdds = showOdds, onOdds = { showOdds = !showOdds }, noArLabel = "AR")
    }
}

@Composable
private fun CrashHud(
    t: CrashTable, placed: Boolean, msg: String?, canPlace: Boolean, onPlace: () -> Unit, jetAt: Offset?, big: Boolean, onBig: (() -> Unit)?,
    onMove: (() -> Unit)?, onNoAr: (() -> Unit)?, muted: Boolean, onMute: (() -> Unit)?, showOdds: Boolean, onOdds: () -> Unit, noArLabel: String = "No AR",
) {
    Box(Modifier.fillMaxSize()) {
        // the number rides over the jet; when the jet is out of view it waits at the top of the screen
        // (after the crash the FLEW AWAY banner says the number)
        if (placed && t.phase == CrashPhase.FLYING) {
            val col = if (t.phase == CrashPhase.CRASHED) RED else multColor(t.mult)
            val p = jetAt
            Text(Crash.label(t.mult), Modifier.align(if (p == null) Alignment.TopCenter else Alignment.TopStart).padding(top = if (p == null) 150.dp else 0.dp)
                .graphicsLayer { if (p != null) { translationX = p.x - size.width / 2; translationY = p.y - size.height - 70f } },
                color = col, fontSize = 40.sp, fontWeight = FontWeight.Black, style = TextStyle(shadow = Shadow(color = col, blurRadius = 28f)))
        }
        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("SKY  RUSH", Modifier.background(GLASS, RoundedCornerShape(14.dp)).border(1.dp, LINE, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 7.dp), color = GOLD, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Pill("CREDIT ${nf.format(t.credits)} · PLAY MONEY")
            }
            if (msg != null) Pill(msg)
            if (placed && t.history.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (m in t.history) Text(Crash.label(m), Modifier.background(GLASS, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp), color = multColor(m), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            if (placed && t.phase == CrashPhase.CRASHED) {
                val won = t.slots.sumOf { it.won }
                Column(Modifier.padding(top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("FLEW AWAY", color = RED, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp, style = TextStyle(shadow = Shadow(color = RED, blurRadius = 24f)))
                    Text(if (won > 0) "You took ${nf.format(won)} this round" else "at ${Crash.label(t.crashedAt)}", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
                }
            }
            if (t.message.isNotEmpty() && t.phase == CrashPhase.BETTING) Pill(t.message)
            if (showOdds) Text(oddsText(), Modifier.widthIn(max = 380.dp).background(Color(0xEE0A0E1C), RoundedCornerShape(12.dp)).padding(12.dp), color = Color.White, fontSize = 13.sp)
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 10.dp, end = 10.dp, bottom = 12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!placed) {
                Ctl(if (canPlace) "Place runway" else "Finding a surface…", enabled = canPlace, primary = true, wide = true) { onPlace() }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onBig?.let { Ctl(if (big) "Size: big" else "Size: tabletop", small = true) { it() } }
                    onNoAr?.let { Ctl("View without AR", small = true) { it() } }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in 0..1) BetPanel(t, i, Modifier.weight(1f))
                }
                if (t.phase == CrashPhase.BETTING) {
                    BetSlider(t.chipValue) { t.chipValue = it }
                    if (t.canRefill()) Ctl("Free refill", primary = true, wide = true) { t.refill() }
                    else Box(Modifier.fillMaxWidth().widthIn(max = 480.dp).height(58.dp).clip(RoundedCornerShape(20.dp))
                        .background(if (t.canTakeOff()) Brush.horizontalGradient(listOf(GOLD, Color(0xFFFFE9A6))) else Brush.horizontalGradient(listOf(Color(0xFF2A3147), Color(0xFF2A3147))))
                        .clickable { t.takeOff() }, contentAlignment = Alignment.Center) {
                        Text("TAKE OFF  ·  ${nf.format(t.total())}", color = if (t.canTakeOff()) Color(0xFF08101E) else Color.White.copy(alpha = 0.5f), fontSize = 19.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Ctl(if (showOdds) "Odds ✕" else "Odds & rules", small = true) { onOdds() }
                    onMute?.let { Ctl(if (muted) "Sound: off" else "Sound: on", small = true) { it() } }
                    onBig?.let { Ctl(if (big) "Size: big" else "Size: tabletop", small = true) { it() } }
                    onMove?.let { Ctl("Move", small = true) { it() } }
                    onNoAr?.let { Ctl(noArLabel, small = true) { it() } }
                }
            }
        }
    }
}

/** One bet: its stake, on/off, its auto cash-out, and in flight the big CASH OUT with what it would pay now. */
@Composable
private fun BetPanel(t: CrashTable, i: Int, modifier: Modifier) {
    val s = t.slots[i]; val betting = t.phase == CrashPhase.BETTING
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(GLASS).border(1.5.dp, if (s.riding) GOLD else if (s.cashedAt > 0) WIN else LINE, RoundedCornerShape(18.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("BET ${i + 1}", Modifier.weight(1f), color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(if (s.on) "ON" else "OFF", Modifier.clip(RoundedCornerShape(8.dp)).background(if (s.on) SKY.copy(alpha = 0.25f) else Color.Transparent).border(1.dp, if (s.on) SKY else LINE, RoundedCornerShape(8.dp)).clickable(enabled = betting) { t.toggle(i) }.padding(horizontal = 8.dp, vertical = 2.dp),
                color = if (s.on) SKY else Color.White.copy(alpha = 0.5f), fontSize = 11.sp, fontWeight = FontWeight.Black)
        }
        Text(nf.format(s.stake), color = if (s.on) Color.White else Color.White.copy(alpha = 0.4f), fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text(if (s.auto > 0) "AUTO ${Crash.label(s.auto)}" else "AUTO off", Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, if (s.auto > 0) VIOLET else LINE, RoundedCornerShape(8.dp)).clickable(enabled = betting) { t.cycleAuto(i) }.padding(horizontal = 8.dp, vertical = 3.dp),
            color = if (s.auto > 0) VIOLET else Color.White.copy(alpha = 0.55f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        when {
            betting -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("+${nf.format(t.chipValue)}", Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(SKY.copy(alpha = 0.2f)).clickable { t.addChip(i) }.padding(vertical = 8.dp), color = SKY, fontSize = 14.sp, fontWeight = FontWeight.Black, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("Clear", Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, LINE, RoundedCornerShape(10.dp)).clickable { t.clear(i) }.padding(horizontal = 10.dp, vertical = 8.dp), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            }
            s.riding -> Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(Brush.horizontalGradient(listOf(GOLD, Color(0xFFFFE9A6)))).clickable { t.cashOut(i) }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("CASH OUT", color = Color(0xFF08101E), fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    Text(nf.format(s.stake * t.mult / 100), color = Color(0xFF08101E), fontSize = 18.sp, fontWeight = FontWeight.Black)
                }
            }
            s.cashedAt > 0 -> Text("+${nf.format(s.won)}  at ${Crash.label(s.cashedAt)}", color = WIN, fontSize = 15.sp, fontWeight = FontWeight.Black)
            else -> Text(if (t.phase == CrashPhase.CRASHED && s.on && s.stake > 0) "Lost" else "Not in this round", color = Color.White.copy(alpha = 0.45f), fontSize = 13.sp)
        }
    }
}
