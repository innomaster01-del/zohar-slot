package com.zohar.slotar.bonus

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
import com.zohar.slotar.hilo.HiLoCall
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


private val CYAN = Color(0xFF12D8FF); private val PINK = Color(0xFFFF2F92); private val INK = Color(0xFF070912)
private const val BASE_WIN = 100L

/** The bonus app: pick one of the three mini-games, play its one round on a sample win, see the bonus, come back. */
@Composable
fun BonusApp(smoke: Boolean = false) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("zohar_bonus", android.content.Context.MODE_PRIVATE) }
    var kind by remember { mutableStateOf<BonusKind?>(null) }
    var round by remember { mutableIntStateOf(0) }
    var total by remember { mutableLongStateOf(prefs.getLong("total", 0L)) }
    var last by remember { mutableStateOf<BonusResult?>(null) }
    val k = kind
    if (k == null) BonusMenu(total, last) { kind = it; round++ }
    else key(k, round) {
        BonusAr(k, BASE_WIN, smoke) { r ->
            if (r != null) { total += r.bonus; prefs.edit().putLong("total", total).apply(); last = r }
            kind = null
        }
    }
}

@Composable
private fun BonusMenu(total: Long, last: BonusResult?, onPick: (BonusKind) -> Unit) {
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B0E1E), INK))).statusBarsPadding().navigationBarsPadding().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("ZOHAR BONUS", color = CYAN, fontSize = 34.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 18.dp))
        Text("Three short bonus rounds in AR. Each plays once on a sample win of ${nf.format(BASE_WIN)} and ends with a bonus.", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, textAlign = TextAlign.Center)
        for ((kind, color, text) in listOf(
            Triple(BonusKind.HILO, CYAN, "Two cards on your table. Call the next one higher or lower.\nA right call pays its true odds; about +100 % on average."),
            Triple(BonusKind.DICE, PINK, "Two glowing dice. The total picks the bonus, +20 % to +500 %.\nAverage +${"%.0f".format(Locale.US, BonusDice.mean())} %."),
            Triple(BonusKind.PLINKO, Color(0xFFFFE14A), "A triangle of light in the air. One ball, +50 % to +2000 %.\nAverage +${"%.0f".format(Locale.US, BonusPlinko.mean())} %."),
        )) {
            Column(Modifier.fillMaxWidth().widthIn(max = 460.dp).clip(RoundedCornerShape(22.dp)).background(color.copy(alpha = 0.10f)).border(2.dp, color, RoundedCornerShape(22.dp)).clickable { onPick(kind) }.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(kind.title.uppercase(), color = color, fontSize = 22.sp, fontWeight = FontWeight.Black)
                Text(text, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
            }
        }
        if (last != null) Pill("Last round: ${last.kind.title} · +${last.pct} % · +${nf.format(last.bonus)} credits")
        Pill("Bonus credits so far: ${nf.format(total)} · PLAY MONEY")
    }
}

/** Screen tap → the stage's own space, on its ground plane (y = 0). */
private fun groundTap(view: com.google.android.filament.View, stage: BonusStage, x: Float, y: Float) {
    val r = view.screenToRay(x, y) ?: return; val f = stage.frame ?: return
    val inv = runCatching { dev.romainguy.kotlin.math.inverse(f.worldTransform) }.getOrNull() ?: return
    val o = (inv * dev.romainguy.kotlin.math.Float4(r.origin.x, r.origin.y, r.origin.z, 1f)).xyz
    val d = (inv * dev.romainguy.kotlin.math.Float4(r.direction.x, r.direction.y, r.direction.z, 0f)).xyz
    if (kotlin.math.abs(d.y) < 1e-6f) return
    val k = -o.y / d.y; if (k < 0) return
    stage.tapGround(o.x + d.x * k, o.z + d.z * k)
}

/** One mini-game in AR: its pieces appear on your table or in the air, it plays once, and it is over. */
@Composable
private fun BonusAr(kind: BonusKind, base: Long, smoke: Boolean, onDone: (BonusResult?) -> Unit) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val view = rememberARView(engine) {
        createARView(engine).apply {
            multiSampleAntiAliasingOptions = multiSampleAntiAliasingOptions.apply { enabled = true; sampleCount = 4 }
            bloomOptions = bloomOptions.apply { enabled = true; strength = 0.22f; threshold = true }      // the pieces are made of light
        }
    }
    val scope = rememberCoroutineScope()
    val sounds = remember { SlotSounds(context) }
    val stage = remember { BonusStage(kind, base, context, engine, modelLoader, sounds, scope) }
    DisposableEffect(Unit) { onDispose { stage.close(); sounds.close() } }
    val reticleModel = rememberModelInstance(modelLoader, "models/reticle.glb")
    val upright = kind == BonusKind.PLINKO          // the triangle hangs upright (on a wall or in the air); cards and dice lie on a surface

    var anchor by remember { mutableStateOf<Anchor?>(null) }
    var canPlace by remember { mutableStateOf(false) }
    var tracking by remember { mutableStateOf(false) }
    var viewW by remember { mutableIntStateOf(0) }
    var viewH by remember { mutableIntStateOf(0) }
    var scanSecs by remember { mutableFloatStateOf(0f) }
    var cap by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    val candidate = remember { Ref<Pair<Pose, com.google.ar.core.Trackable>?>(null) }
    val wantAir = remember { Ref(false) }
    val lastTs = remember { Ref(0L) }
    val placedRoot = remember { Ref<io.github.sceneview.node.Node?>(null) }
    val frameRef = remember { Ref<io.github.sceneview.node.Node?>(null) }
    val reticleRef = remember { Ref<io.github.sceneview.node.Node?>(null) }
    val t0 = remember { SystemClock.uptimeMillis() }

    fun place() {
        val c = candidate.v ?: return
        anchor = runCatching { c.second.createAnchor(c.first) }.getOrNull() ?: return
        sounds.play("click")
    }
    // taps on the scene: a call tile (Higher or Lower), or anywhere to throw / drop
    DisposableEffect(stage) {
        var dx = 0f; var dy = 0f
        com.zohar.slotar.TouchBus.handler = { e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.x; dy = e.y }
                MotionEvent.ACTION_UP -> if (stage.frame != null && kotlin.math.hypot(e.x - dx, e.y - dy) < 28f) {
                    if (kind == BonusKind.HILO) groundTap(view, stage, e.x, e.y) else stage.act()
                }
            }
        }
        onDispose { com.zohar.slotar.TouchBus.handler = null; com.zohar.slotar.TouchBus.blocked.clear() }
    }

    Box(Modifier.fillMaxSize().onSizeChanged { viewW = it.width; viewH = it.height }) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine, modelLoader = modelLoader, materialLoader = materialLoader, view = view,
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL,
            depthMode = Config.DepthMode.AUTOMATIC,
            instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP,
            sceneUnderstanding = SceneUnderstanding(occlusion = false, lighting = true, physics = false, planeVisualization = anchor == null),
            onSessionUpdated = { arSession, frame ->
                val ts = frame.timestamp; val dt = if (lastTs.v == 0L) 1f / 30 else ((ts - lastTs.v) / 1e9f).coerceIn(0f, 0.1f); lastTs.v = ts
                tracking = frame.camera.trackingState == TrackingState.TRACKING
                if (anchor == null) {
                    if (tracking) scanSecs += dt
                    val cam = frame.camera.pose
                    if (tracking && (wantAir.v || (smoke && scanSecs > 3f))) {
                        // in the air, in front of the phone and a little below it, turned to face you
                        wantAir.v = false
                        val fz = cam.zAxis; val hl = kotlin.math.sqrt(fz[0] * fz[0] + fz[2] * fz[2]).coerceAtLeast(1e-3f)
                        val dist = if (upright) 1.3f else 0.6f; val drop = if (upright) 0.55f else 0.35f
                        val px = cam.tx() - fz[0] / hl * dist; val pz = cam.tz() - fz[2] / hl * dist; val py = cam.ty() - drop
                        val yaw = atan2((cam.tx() - px).toDouble(), (cam.tz() - pz).toDouble())
                        anchor = runCatching { arSession.createAnchor(Pose(floatArrayOf(px, py, pz), floatArrayOf(0f, sin(yaw / 2).toFloat(), 0f, cos(yaw / 2).toFloat()))) }.getOrNull()
                        if (anchor != null) sounds.play("click")
                        Log.i("ZoharSmoke", "bonus $kind in the air placed=${anchor != null}")
                    } else {
                        val hits = if (viewW > 0) frame.hitTest(viewW / 2f, viewH * 0.55f) else emptyList()
                        val want = if (upright) Plane.Type.VERTICAL else Plane.Type.HORIZONTAL_UPWARD_FACING
                        val hit = hits.firstOrNull { h -> val p = h.trackable as? Plane; p != null && p.type == want && p.trackingState == TrackingState.TRACKING && h.distance < 4f && p.isPoseInPolygon(h.hitPose) && p.subsumedBy == null }
                        if (hit != null) {
                            val raw = hit.hitPose
                            val pose = if (upright) {
                                // on a wall: the triangle faces out of it, its middle where you aim
                                val n = raw.yAxis; val yaw = atan2(n[0].toDouble(), n[2].toDouble())
                                Pose(floatArrayOf(raw.tx() + n[0] * 0.04f, raw.ty() - 0.42f, raw.tz() + n[2] * 0.04f), floatArrayOf(0f, sin(yaw / 2).toFloat(), 0f, cos(yaw / 2).toFloat()))
                            } else {
                                val yaw = atan2((cam.tx() - raw.tx()).toDouble(), (cam.tz() - raw.tz()).toDouble())
                                Pose(floatArrayOf(raw.tx(), raw.ty(), raw.tz()), floatArrayOf(0f, sin(yaw / 2).toFloat(), 0f, cos(yaw / 2).toFloat()))
                            }
                            candidate.v = pose to hit.trackable
                            reticleRef.v?.let { r -> r.worldPosition = Position(raw.tx(), raw.ty(), raw.tz()); r.worldQuaternion = dev.romainguy.kotlin.math.Quaternion(raw.qx(), raw.qy(), raw.qz(), raw.qw()) }
                            canPlace = true
                        } else { candidate.v = null; canPlace = false }
                    }
                } else {
                    anchor?.takeIf { it.trackingState == TrackingState.TRACKING }?.pose?.let { p ->
                        placedRoot.v?.let { r -> r.worldPosition = Position(p.tx(), p.ty(), p.tz()); r.worldQuaternion = dev.romainguy.kotlin.math.Quaternion(p.qx(), p.qy(), p.qz(), p.qw()) }
                    }
                    frameRef.v?.let { f ->
                        stage.attach(f)
                        stage.update((SystemClock.uptimeMillis() - t0) / 1000.0)
                        // where the glowing words hang, on the screen
                        val l = stage.captionAt
                        val w = (f.worldTransform * dev.romainguy.kotlin.math.Float4(l.x, l.y, l.z, 1f)).xyz
                        val s = view.worldToScreen(Position(w.x, w.y, w.z))?.let { p -> androidx.compose.ui.geometry.Offset(p.x, p.y) }
                        if (s == null || cap == null || (s - cap!!).getDistance() > 2f) cap = s
                    }
                }
            },
        ) {
            reticleModel?.let { ModelNode(modelInstance = it, autoAnimate = true, animationLoop = true, isVisible = anchor == null && canPlace, scale = Scale(0.5f), apply = { reticleRef.v = this }) }
            Node(apply = { placedRoot.v = this }) {
                Node(apply = { frameRef.v = this }) { }
            }
        }
        // the words over the pieces
        val words = stage.caption; val at = cap
        if (anchor != null && words.isNotEmpty() && at != null) {
            var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
            val done = stage.phase == BPhase.DONE; val win = (stage.result?.pct ?: 0) > 0
            val color = if (!done) Color.White else if (win) Color(0xFFFFE14A) else Color.White.copy(alpha = 0.85f)
            Text(words, Modifier.offset { androidx.compose.ui.unit.IntOffset((at.x - size.width / 2f).toInt(), (at.y - size.height).toInt()) }.onSizeChanged { size = it },
                color = color, fontSize = if (done) 46.sp else 22.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
                style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = if (done && win) PINK else CYAN, blurRadius = 28f)))
        }
        // top
        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(10.dp).onGloballyPositioned { c -> c.boundsInWindow().let { b -> com.zohar.slotar.TouchBus.blocked["bonusTop"] = android.graphics.RectF(b.left, b.top, b.right, b.bottom) } },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("BONUS ROUND · ${kind.title} · on a win of ${nf.format(base)} · PLAY MONEY")
            if (anchor == null) Pill(when {
                !tracking -> "Move the phone slowly so it can see the room"
                upright && canPlace -> "Wall found · tap Place here, or hang it in the air"
                upright -> "Aim at a wall, or hang it in the air"
                canPlace -> "Surface found · tap Place here, or hang it in the air"
                else -> "Aim at your table, or hang it in the air"
            })
        }
        // bottom
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 10.dp, end = 10.dp, bottom = 14.dp).fillMaxWidth()
            .onGloballyPositioned { c -> c.boundsInWindow().let { b -> com.zohar.slotar.TouchBus.blocked["bonusBottom"] = android.graphics.RectF(b.left, b.top, b.right, b.bottom) } },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (anchor == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Ctl("Place here", enabled = canPlace, primary = canPlace, wide = true) { place() }
                    Ctl("In the air", enabled = tracking, primary = !canPlace, wide = true) { wantAir.v = true }
                }
                Ctl("Back", small = true) { onDone(null) }
            } else when (stage.phase) {
                BPhase.READY -> when (kind) {
                    BonusKind.HILO -> Row(Modifier.fillMaxWidth().widthIn(max = 460.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CallButton("▼  LOWER", stage.offer(HiLoCall.LOWER), Color(0xFF2F8BFF)) { stage.call(HiLoCall.LOWER) }
                        CallButton("▲  HIGHER", stage.offer(HiLoCall.HIGHER), PINK) { stage.call(HiLoCall.HIGHER) }
                    }
                    BonusKind.DICE -> Ctl("THROW", primary = true, wide = true) { stage.act() }
                    BonusKind.PLINKO -> Ctl("DROP", primary = true, wide = true) { stage.act() }
                }
                BPhase.DONE -> {
                    val r = stage.result
                    if (r != null) Pill("BONUS +${r.pct} % of ${nf.format(r.base)} = +${nf.format(r.bonus)} credits")
                    Ctl("Done", primary = true, wide = true) { onDone(r) }
                }
                else -> Text("…", color = Color.White, fontSize = 22.sp)
            }
        }
    }
}

/** One call: how many of the cards left make it right, and the bonus it pays if it is. */
@Composable
private fun RowScope.CallButton(label: String, o: BonusHiLo.Offer?, color: Color, onClick: () -> Unit) {
    Box(Modifier.weight(1f).height(66.dp).clip(RoundedCornerShape(18.dp)).background(color.copy(alpha = if (o != null) 0.92f else 0.25f)).clickable(enabled = o != null) { onClick() }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Color.White.copy(alpha = if (o != null) 1f else 0.45f), fontSize = 17.sp, fontWeight = FontWeight.Black)
            Text(if (o != null) "${o.wins} of ${o.left} · +${o.pct} %" else "cannot win", color = Color.White.copy(alpha = if (o != null) 0.95f else 0.45f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
