package com.zohar.slotar.blackjack

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
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

const val ODDS_TEXT = "Play money only · nothing to buy, nothing to cash out.\n" +
    "Rules: 6 decks · dealer stands on all 17s · blackjack pays 3:2 · dealer peeks · double any 2 cards · double after split · split to 4 hands · split aces one card · insurance pays 2:1 · late surrender.\n" +
    "House edge with perfect basic strategy ≈ 0.3–0.5 % (return to player ≈ 99.5 %). Insurance is a bad bet unless you count cards."

/** Blackjack in AR: a real-size table on your floor, the dealer behind it, you in the player's seat. */
@Composable
fun BlackjackAr(smoke: Boolean = false, onNoAr: () -> Unit) {
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
    val table = remember { BlackjackTable(context, engine, modelLoader, sounds, scope, { fx -> fxTokens[fx] = (fxTokens[fx] ?: 0) + 1 }) }
    DisposableEffect(Unit) { onDispose { table.close(); sounds.close() } }
    val tableModel = rememberModelInstance(modelLoader, "models/bj_table.glb")
    val dealerModel = rememberModelInstance(modelLoader, "models/dealer.glb")
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
                    if (f != null && d != null) table.attach(f, d)
                    frame.camera.displayOrientedPose.let { cp -> table.cameraWorld = floatArrayOf(cp.tx(), cp.ty(), cp.tz()) }
                    table.update((SystemClock.uptimeMillis() - t0) / 1000.0, dt)
                }
            },
        ) {
            reticleModel?.let { ModelNode(modelInstance = it, autoAnimate = false, isVisible = anchor == null && canPlace, scale = Scale(0.6f), apply = { reticleRef.v = this }) }
            anchorPlane?.let { ShadowReceiverPlane(plane = it) }
            Node(apply = { placedRoot.v = this }) {
                // the rail edge in front of you sits where you aimed; the table and the dealer are behind it
                // the frame stays visible (late-added children of a hidden parent stay hidden); the models hide while placing
                Node(position = Position(0f, 0f, -0.95f * s), scale = Scale(s), apply = { frameRef.v = this }) {
                    tableModel?.let { ModelNode(modelInstance = it, autoAnimate = false, isVisible = anchor != null) }
                    dealerModel?.let { ModelNode(modelInstance = it, autoAnimate = false, isVisible = anchor != null, position = Position(0f, 0f, table.L.dealerZ), apply = { dealerRef.v = this }) }
                    for (fx in Fx.entries) {
                        val tok = fxTokens[fx] ?: 0
                        if (tok > 0 && fxActive[fx] == true) key(fx, tok) {
                            rememberModelInstance(modelLoader, FX_FILES.getValue(fx))?.let { ModelNode(modelInstance = it, autoAnimate = true, animationLoop = false, position = Position(0f, table.L.feltY - 0.1f, 0.5f), scale = Scale(0.45f)) }
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
        BlackjackHud(table, placed = anchor != null, msg = msg, canPlace = canPlace, onPlace = { place() }, mini = mini, onMini = { mini = !mini },
            onMove = { anchor?.detach(); anchor = null; anchorPlane = null; scanSecs = 0f }, onNoAr = onNoAr,
            muted = muted, onMute = { muted = !muted; sounds.muted = muted; table.voice.muted = muted }, showOdds = showOdds, onOdds = { showOdds = !showOdds })
    }
}

/** Same table and dealer without AR (phones without ARCore, and the CI self-test that plays real rounds). */
@Composable
fun BlackjackPreview(smoke: Boolean = false, onAr: (() -> Unit)? = null) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val view = rememberView(engine)
    val scope = rememberCoroutineScope()
    val sounds = remember { SlotSounds(context) }
    val table = remember { BlackjackTable(context, engine, modelLoader, sounds, scope, {}, rng = if (smoke) SeededRng(7) else SecureRng, wallet = if (smoke) object : Wallet { override var credits = 1000L } else null) }
    DisposableEffect(Unit) { onDispose { table.close(); sounds.close() } }
    val tableModel = rememberModelInstance(modelLoader, "models/bj_table.glb")
    // the emulator's SwiftShader GL counts the skinning uniform block against its 256 vertex vectors and fails to link,
    // so the CI self-test uses the same dealer rig without skin/morphs (rigid mesh; debug builds only ship that file)
    val dealerAsset = remember { if (smoke && runCatching { context.assets.open("models/dealer_nomorph.glb").close() }.isSuccess) "models/dealer_nomorph.glb" else "models/dealer.glb" }
    LaunchedEffect(dealerAsset) { Log.i("ZoharSmoke", "bj dealer asset=$dealerAsset") }
    val dealerModel = rememberModelInstance(modelLoader, dealerAsset)
    val frameRef = remember { Ref<io.github.sceneview.node.Node?>(null) }
    val dealerRef = remember { Ref<io.github.sceneview.node.ModelNode?>(null) }
    val t0 = remember { SystemClock.uptimeMillis() }
    val last = remember { Ref(0L) }
    var showOdds by remember { mutableStateOf(false) }
    val eye = table.L.let { floatArrayOf(0f, 1.22f, 1.25f) }
    val cameraNode = rememberCameraNode(engine) { position = Position(eye[0], eye[1], eye[2]); lookAt(Position(0f, 1.0f, 0.1f)) }

    if (smoke) LaunchedEffect(Unit) {
        // CI self-test: play real rounds through the same buttons a player uses, check what is on the table = the engine
        while (table.dealer == null) delay(200)
        Log.i("ZoharSmoke", "bj dealerOk=${table.dealer?.ok} clips=${listOf("Idle", "DealPlayer", "DealDealer", "Flip", "Collect", "Pay", "Clear", "Clap", "Welcome").all { table.dealer?.has(it) == true }}")
        delay(2500)
        val act = context as? android.app.Activity
        act?.let { com.zohar.slotar.DebugCapture.shoot(it, "bj_idle") }
        var mismatches = 0; var shots = 0
        repeat(6) { round ->
            while (table.ui != Ui.BETTING) delay(100)
            if (table.bet == 0L) table.addChip(25)
            table.deal()
            if (round == 0) { delay(700); act?.let { com.zohar.slotar.DebugCapture.shoot(it, "bj_dealing") } }
            var guard = 0
            while (guard++ < 600) {
                delay(100)
                when (table.ui) {
                    Ui.INSURANCE -> table.insurance(false)
                    Ui.PLAYER -> {
                        if (shots < 2) { shots++; delay(400); act?.let { com.zohar.slotar.DebugCapture.shoot(it, "bj_player$shots") } }
                        val shown = table.cardsOnTable.filter { it.owner == BlackjackTable.Owner.PLAYER }.groupBy { it.hand }.toSortedMap().values.map { cs -> cs.sortedBy { it.indexInHand }.map { it.card } }
                        val eng = table.game.hands.map { it.cards.toList() }
                        if (shown != eng) { mismatches++; Log.w("ZoharSmoke", "bj mismatch shown=$shown engine=$eng") }
                        val dUp = table.cardsOnTable.firstOrNull { it.owner == BlackjackTable.Owner.DEALER && it.indexInHand == 0 }?.card
                        if (dUp != table.game.dealer[0]) { mismatches++; Log.w("ZoharSmoke", "bj dealer up shown=$dUp engine=${table.game.dealer[0]}") }
                        table.act(basicStrategy(table.game.hand.cards, table.game.dealer[0], table.game.actions()))
                    }
                    Ui.BETTING -> break
                    Ui.BUSY -> {}
                }
            }
            Log.i("ZoharSmoke", "bj round ${round + 1}: ${table.outcome} credits=${table.credits} wallet=${table.wallet.credits} cardsLeftOnTable=${table.cardsOnTable.size}")
        }
        Log.i("ZoharSmoke", "bj done rounds=${table.rounds} mismatches=$mismatches creditsShown==wallet ${table.credits + table.bet == table.wallet.credits}")
    }

    Box(Modifier.fillMaxSize()) {
        SceneView(
            modifier = Modifier.fillMaxSize(), engine = engine, modelLoader = modelLoader, view = view,
            frameRatePolicy = FrameRatePolicy.Continuous(), autoCenterContent = false, cameraNode = cameraNode,
            cameraManipulator = if (smoke) null else rememberCameraManipulator(orbitHomePosition = Position(eye[0], eye[1], eye[2]), targetPosition = Position(0f, 0.95f, 0.25f)),
            onFrame = { ns ->
                val dt = if (last.v == 0L) 1f / 60 else ((ns - last.v) / 1e9f).coerceIn(0f, 0.1f); last.v = ns
                val f = frameRef.v; val d = dealerRef.v
                if (f != null && d != null) table.attach(f, d)
                table.cameraWorld = floatArrayOf(cameraNode.worldPosition.x, cameraNode.worldPosition.y, cameraNode.worldPosition.z)
                table.update((SystemClock.uptimeMillis() - t0) / 1000.0, dt)
            },
        ) {
            Node(apply = { frameRef.v = this }) {
                tableModel?.let { ModelNode(modelInstance = it, autoAnimate = false) }
                dealerModel?.let { ModelNode(modelInstance = it, autoAnimate = false, position = Position(0f, 0f, table.L.dealerZ), apply = { dealerRef.v = this }) }
            }
        }
        BlackjackHud(table, placed = true, msg = null, canPlace = false, onPlace = {}, mini = false, onMini = null, onMove = null, onNoAr = onAr,
            muted = false, onMute = null, showOdds = showOdds, onOdds = { showOdds = !showOdds }, noArLabel = "AR")
    }
}

@Composable
private fun BlackjackHud(
    t: BlackjackTable, placed: Boolean, msg: String?, canPlace: Boolean, onPlace: () -> Unit, mini: Boolean, onMini: (() -> Unit)?,
    onMove: (() -> Unit)?, onNoAr: (() -> Unit)?, muted: Boolean, onMute: (() -> Unit)?, showOdds: Boolean, onOdds: () -> Unit, noArLabel: String = "No AR",
) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("CREDIT ${nf.format(t.credits)}" + (if (t.bet > 0) " · BET ${nf.format(t.bet)}" else "") + " · PLAY MONEY")
            if (msg != null) Pill(msg)
            if (placed) {
                if (t.line.isNotEmpty()) Text("“${t.line}”", Modifier.widthIn(max = 360.dp).background(Color(0xB0000000), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp), color = Color(0xFFFFE6A8), fontSize = 15.sp, textAlign = TextAlign.Center)
                if (t.totals.isNotEmpty()) Pill(t.totals)
                if (t.outcome.isNotEmpty() && t.ui == Ui.BETTING) Text(t.outcome, color = if (t.outcome.startsWith("YOU WIN")) Color(0xFF5DFF9E) else Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
            }
            if (showOdds) Text(ODDS_TEXT, Modifier.widthIn(max = 360.dp).background(Color(0xE612141C), RoundedCornerShape(12.dp)).padding(12.dp), color = Color.White, fontSize = 13.sp)
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 10.dp, end = 10.dp, bottom = 14.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!placed) {
                Ctl(if (canPlace) "Place table" else "Finding the floor…", enabled = canPlace, primary = true, wide = true) { onPlace() }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onMini?.let { Ctl(if (mini) "Size: tabletop" else "Size: real", small = true) { it() } }
                    onNoAr?.let { Ctl("View without AR", small = true) { it() } }
                }
            } else {
                when (t.ui) {
                    Ui.BETTING -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            for ((v, c) in listOf(5L to Color(0xFFC8262E), 25L to Color(0xFF1E8A3E), 100L to Color(0xFF151515), 500L to Color(0xFF5B2A86)))
                                Chip(v, c, enabled = t.bet + v <= minOf(t.game.rules.maxBet, t.credits + t.bet)) { t.addChip(v) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (t.canRefill()) Ctl("Free refill", primary = true, wide = true) { t.refill() }
                            else {
                                Ctl("Clear", small = true, enabled = t.bet > 0) { t.clearBet() }
                                Ctl("DEAL", primary = true, wide = true, enabled = t.bet >= t.game.rules.minBet) { t.deal() }
                            }
                        }
                    }
                    Ui.INSURANCE -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Ctl(if (t.evenMoney) "Even money" else "Insurance ${t.insuranceCost}", primary = true, enabled = t.game.canInsure()) { t.insurance(true) }
                        Ctl(if (t.evenMoney) "No, play on" else "No insurance") { t.insurance(false) }
                    }
                    Ui.PLAYER -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Ctl("HIT", primary = true, enabled = Action.HIT in t.actions) { t.act(Action.HIT) }
                            Ctl("STAND", primary = true, enabled = Action.STAND in t.actions) { t.act(Action.STAND) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (Action.DOUBLE in t.actions) Ctl("Double", small = true) { t.act(Action.DOUBLE) }
                            if (Action.SPLIT in t.actions) Ctl("Split", small = true) { t.act(Action.SPLIT) }
                            if (Action.SURRENDER in t.actions) Ctl("Surrender", small = true) { t.act(Action.SURRENDER) }
                            var hint by remember { mutableStateOf<String?>(null) }
                            Ctl(hint ?: "Hint", small = true) { hint = basicStrategy(t.game.hand.cards, t.game.dealer[0], t.game.actions()).name.lowercase().replaceFirstChar { it.uppercase() } }
                            LaunchedEffect(t.game.hand.cards.size, t.game.active) { hint = null }
                        }
                    }
                    Ui.BUSY -> Text("…", color = Color.White, fontSize = 22.sp)
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

@Composable
private fun Chip(v: Long, c: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(58.dp).background(Brush.radialGradient(listOf(c.copy(alpha = if (enabled) 1f else 0.35f), c.copy(alpha = if (enabled) 0.8f else 0.3f))), CircleShape)
        .border(4.dp, Color.White.copy(alpha = if (enabled) 0.9f else 0.3f), CircleShape).clickable(enabled = enabled) { onClick() }, contentAlignment = Alignment.Center) {
        Text(nf.format(v), color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp)
    }
}
