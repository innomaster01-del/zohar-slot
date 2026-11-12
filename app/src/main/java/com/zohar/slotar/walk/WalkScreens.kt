package com.zohar.slotar.walk

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Plane
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.zohar.slotar.BuildConfig
import com.zohar.slotar.DebugCapture
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.scene.SceneUnderstanding
import io.github.sceneview.math.Scale
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private val Ink = Color(0xFF0B1220); private val Card = Color(0xFF111A2C); private val Line = Color(0xFF22314A)
private val Cyan = Color(0xFF22D3EE); private val Amber = Color(0xFFF5B841); private val Muted = Color(0xFF9FB0C8)
private const val TAG = "ZoharWalk"

/** The device language for spoken / written directions (Mapbox speaks Hebrew as "he"). */
internal fun walkLanguage(): String = when (val l = Locale.getDefault().language) { "iw", "he" -> "he"; "" -> "en"; else -> l }

/** All the words on screen, in Hebrew (right to left) or English. */
internal class Words(val he: Boolean) {
    fun t(h: String, e: String) = if (he) h else e
    fun dist(m: Double) = when {
        m >= 1000 -> if (he) "%.1f ק״מ".format(Locale.US, m / 1000) else "%.1f km".format(Locale.US, m / 1000)
        else -> "${(m / 5).roundToInt() * 5}" + (if (he) " מ׳" else " m")
    }
    fun meters(m: Double) = "${(m / 5).roundToInt() * 5}" + (if (he) " מטר" else " meters")
    fun mins(s: Double): String {
        val m = (s / 60).roundToInt().coerceAtLeast(1)
        return if (m < 60) "$m" + t(" דק׳", " min") else "${m / 60}" + t(" ש׳ ", " h ") + "${m % 60}" + t(" דק׳", " min")
    }
    fun bubble(s: PoppyGuide.Say) = when (s) {
        PoppyGuide.Say.LETS_GO -> t("יאללה, הולכים!", "Let's go!")
        PoppyGuide.Say.COME_ON -> t("נו, בוא!", "Come on!")
        PoppyGuide.Say.SOON_LEFT -> t("עוד מעט שמאלה!", "Left soon!")
        PoppyGuide.Say.SOON_RIGHT -> t("עוד מעט ימינה!", "Right soon!")
        PoppyGuide.Say.LEFT_HERE -> t("כאן שמאלה!", "Left here!")
        PoppyGuide.Say.RIGHT_HERE -> t("כאן ימינה!", "Right here!")
        PoppyGuide.Say.LOOK -> t("מסתכלים ימינה ושמאלה…", "Look left and right…")
        PoppyGuide.Say.ALMOST -> t("כמעט הגענו!", "Almost there!")
        PoppyGuide.Say.ARRIVED -> t("הגענו! 🎉", "We made it! 🎉")
        PoppyGuide.Say.WAIT_FOR_ME -> t("חכה לי!", "Wait for me!")
        PoppyGuide.Say.WAKE -> t("אה! ממשיכים?", "Oh! Off we go?")
        PoppyGuide.Say.YAWN -> t("אההההה… 🥱", "Yaaawn… 🥱")
        PoppyGuide.Say.ZZZ -> "Zzz…"
    }
}

/** Follow Poppy: walking-only navigation; Poppy walks ahead of you on the street in the camera view, arrows on the floor. */
@Composable
fun WalkApp(smoke: Boolean = false, testDest: LatLng? = null, testFrom: LatLng? = null) {
    val w = remember { Words(walkLanguage() == "he") }
    CompositionLocalProvider(LocalLayoutDirection provides if (w.he) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences("walk", android.content.Context.MODE_PRIVATE) }
        var token by remember { mutableStateOf(BuildConfig.MAPBOX_TOKEN.ifEmpty { prefs.getString("mapbox", "") ?: "" }) }
        if (token.isEmpty()) { TokenScreen(w) { t -> prefs.edit().putString("mapbox", t).apply(); token = t }; return@CompositionLocalProvider }
        val api = remember(token) { MapboxApi(token, walkLanguage()) }
        val sensors = remember { WalkSensors(context) }
        var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> granted = r[Manifest.permission.ACCESS_FINE_LOCATION] == true || r[Manifest.permission.ACCESS_COARSE_LOCATION] == true }
        LaunchedEffect(Unit) { if (!granted) ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.CAMERA)) }
        DisposableEffect(granted) { if (granted) sensors.start(); onDispose { sensors.stop() } }
        var dest by remember { mutableStateOf(testDest?.let { Place("Test destination", "", it) }) }
        val d = dest
        var poppy by remember { mutableStateOf(prefs.getString("poppy", "girl") ?: "girl") }
        if (d == null) SearchScreen(w, api, sensors, poppy, { poppy = it; prefs.edit().putString("poppy", it).apply() }) { dest = it }
        else NavScreen(w, api, sensors, d, smoke, testFrom, poppy) { dest = null }
    }
}

/** First launch of a build made without the Mapbox token: paste it once (it stays on this phone only). */
@Composable
private fun TokenScreen(w: Words, onSave: (String) -> Unit) {
    var t by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(w.t("מפתח Mapbox", "Mapbox access token"), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Text(w.t("הדבק את הטוקן הציבורי (מתחיל ב-pk.). הוא נשמר רק בטלפון הזה.", "Paste your public token (starts with pk.). It is saved on this phone only."), color = Muted, fontSize = 14.sp)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(14.dp)).padding(14.dp)) {
            BasicTextField(t, { t = it.trim() }, textStyle = TextStyle(color = Color.White, fontSize = 14.sp), cursorBrush = SolidColor(Cyan), modifier = Modifier.fillMaxWidth())
        }
        Text(w.t("שמירה", "Save"), color = Ink, fontWeight = FontWeight.ExtraBold, modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (t.startsWith("pk.")) Cyan else Muted).clickable(enabled = t.startsWith("pk.")) { onSave(t) }.padding(horizontal = 24.dp, vertical = 14.dp))
    }
}

/** Where on the camera view (fractions of width, height) the app asks ARCore for the street's height. */
private val GROUND_PROBES = listOf(floatArrayOf(0.5f, 0.72f), floatArrayOf(0.3f, 0.82f), floatArrayOf(0.7f, 0.82f), floatArrayOf(0.5f, 0.9f), floatArrayOf(0.2f, 0.95f), floatArrayOf(0.8f, 0.95f))

/** What is drawn over the camera this frame (screen pixels): the turn label, the destination flag, Poppy's head. */
private class Overlay(val turn: FloatArray?, val dest: FloatArray?, val head: FloatArray?)

@Composable
private fun NavScreen(w: Words, api: MapboxApi, sensors: WalkSensors, dest: Place, smoke: Boolean, testFrom: LatLng?, gender: String, onEnd: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = rememberEngine(); val modelLoader = rememberModelLoader(engine); val materialLoader = rememberMaterialLoader(engine)
    var route by remember { mutableStateOf<Route?>(null) }
    var nav by remember { mutableStateOf<Navigator?>(null) }
    var navState by remember { mutableStateOf<Navigator.State?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var viewW by remember { mutableStateOf(0) }; var viewH by remember { mutableStateOf(0) }
    var aligned by remember { mutableStateOf(false) }
    var vps by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var mapMode by remember { mutableStateOf(false) }
    var dark by remember { mutableStateOf(false) }
    var lookUp by remember { mutableStateOf(0L) }
    var miniMap by remember { mutableStateOf<Bitmap?>(null) }
    var bigMap by remember { mutableStateOf<Bitmap?>(null) }
    var heading by remember { mutableStateOf(0.0) }
    var bubble by remember { mutableStateOf<PoppyGuide.Say?>(null) }
    var partyAt by remember { mutableStateOf(0L) }
    var mapOpen by remember { mutableStateOf(false) }
    var seeGround by remember { mutableStateOf(true) }
    var turnAround by remember { mutableStateOf<Float?>(null) }   // degrees to turn (+ right) when the path is not in front of you
    var tooDark by remember { mutableStateOf(false) }
    var vpsTried by remember { mutableStateOf(false) }
    val align = remember { GeoAlign() }
    val poppy = remember { PoppyGuide() }
    val voice = remember { PoppyVoice(gender == "boy") }
    val refs = remember { object { var poppy: io.github.sceneview.node.ModelNode? = null; var trailRoot: io.github.sceneview.node.Node? = null; var rig: PoppyRig? = null; var trail: Trail3D? = null } }
    val poppyInstance = rememberModelInstance(modelLoader, "models/poppy_$gender.glb")
    var poppyShown by remember { mutableStateOf(false) }
    val holder = remember { object {
        var lastFix = 0L; var lastNav = 0L; var groundY = Double.NaN; var rerouting = false; var lastReroute = 0L; var geoConfigured = false; var userGeo: LatLng? = null
        var lastTs = 0L; var frames = 0L; var poppyGround = Double.NaN; var poppyGroundAt = 0L; var lastSay = 0; var turns: List<PoppyGuide.Turn> = emptyList()
        val ground = GroundFinder(); val snap = SidewalkSnap(); val motion = MotionHeading(); var routeAcc = 0.0; var startedAt = SystemClock.uptimeMillis()
        var courseFrom: V3? = null; var courseAt = 0L; var lastTurnBuzz = 0L; var ticks = 0L
    } }
    val tts = remember { arrayOfNulls<TextToSpeech>(1) }
    DisposableEffect(Unit) {
        // Poppy's voice: higher and a little quicker than the phone's normal voice, after her chirp
        tts[0] = TextToSpeech(context) { st -> if (st == TextToSpeech.SUCCESS) tts[0]?.apply { language = Locale.forLanguageTag(walkLanguage()); setPitch(1.55f); setSpeechRate(1.05f) } }
        onDispose { tts[0]?.shutdown() }
    }
    fun say(text: String) { if (muted) return; voice.chirp(2); scope.launch { delay(260); tts[0]?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "walk") } }
    fun buzz(vararg ms: Long) { runCatching { context.getSystemService(android.os.Vibrator::class.java)?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, *ms), -1)) } }
    fun here(): LatLng? = holder.userGeo ?: testFrom ?: sensors.location?.let { LatLng(it.latitude, it.longitude) }

    fun fetch(from: LatLng) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.walk(from, dest.at) } }
                .onSuccess { r -> holder.turns = PoppyGuide.turnsOf(r); route = r; nav = Navigator(r); navState = nav?.state; error = null; Log.i(TAG, "route ${r.length.roundToInt()} m, ${r.steps.size} steps: ${r.steps.joinToString(" | ") { it.instruction }}") }
                .onFailure { error = w.t("אין מסלול: ", "No route: ") + it.message; Log.e(TAG, "route failed", it) }
            holder.rerouting = false
        }
    }
    // the route starts from an accurate fix: wait up to 10 s for ±20 m or better (an early network fix can be 100 m off)
    LaunchedEffect(Unit) {
        val t0 = SystemClock.uptimeMillis()
        while (route == null && error == null) {
            val l = sensors.location; val p = here()
            val good = testFrom != null || holder.userGeo != null || (l != null && l.accuracy <= 20f)
            if (p != null && (good || SystemClock.uptimeMillis() - t0 > 10_000)) { holder.routeAcc = if (testFrom != null) 0.0 else (l?.accuracy?.toDouble() ?: 99.0); fetch(p); return@LaunchedEffect }
            delay(400)
        }
    }
    // started from a rough fix and a much better one says you are elsewhere: plan again from there (once, near the start)
    LaunchedEffect(route) {
        val r = route ?: return@LaunchedEffect
        while (holder.routeAcc > 25) {
            val l = sensors.location
            if (l != null && l.accuracy <= 12f && (navState?.along ?: 0.0) < 25 && Geo.distance(LatLng(l.latitude, l.longitude), r.line.geo.first()) > 20) {
                holder.routeAcc = l.accuracy.toDouble(); Log.i(TAG, "better fix ±${l.accuracy} m: route again"); fetch(LatLng(l.latitude, l.longitude)); break
            }
            delay(1000)
        }
    }
    LaunchedEffect(muted) { voice.muted = muted }

    // corner map and flat-phone map, refreshed as you walk
    LaunchedEffect(route) {
        var lastAt: LatLng? = null; var lastT = 0L
        while (true) {
            val r = route; val at = here()
            if (r != null && at != null && (lastAt == null || Geo.distance(lastAt, at) > 15 || SystemClock.uptimeMillis() - lastT > 8000)) {
                lastAt = at; lastT = SystemClock.uptimeMillis()
                val h = if (heading.isNaN()) 0.0 else heading
                runCatching { withContext(Dispatchers.IO) {
                    miniMap = URL(api.staticMapUrl(r, at, 16.6, h, 200, 200)).openStream().use { BitmapFactory.decodeStream(it) }
                    if (mapMode || bigMap == null) bigMap = URL(api.staticMapUrl(r, at, 16.0, h, 400, 700)).openStream().use { BitmapFactory.decodeStream(it) }
                } }.onFailure { Log.w(TAG, "map image: ${it.message}") }
                if (smoke) miniMap?.let { DebugCapture.screen("walk_minimap", it, 300) }
            }
            delay(1000)
        }
    }
    LaunchedEffect(Unit) { var flatSince = 0L; while (true) { val flat = sensors.pitchUp < 35; val now = SystemClock.uptimeMillis(); if (flat) { if (flatSince == 0L) flatSince = now; if (now - flatSince > 600) mapMode = true } else { flatSince = 0L; mapMode = false }; delay(150) } }

    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { viewW = it.width; viewH = it.height }) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine, modelLoader = modelLoader, materialLoader = materialLoader,
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL,
            depthMode = Config.DepthMode.AUTOMATIC,
            sceneUnderstanding = SceneUnderstanding(occlusion = true, lighting = false, physics = false, planeVisualization = false),
            onSessionUpdated = { session, frame ->
                // once: ARCore Geospatial (Street View VPS) with a Google Cloud key, and depth (finds the street even without planes)
                if (!holder.geoConfigured) {
                    holder.geoConfigured = true
                    runCatching {
                        val c = session.config; var changed = false
                        if (BuildConfig.AR_GEO_KEY.isNotEmpty() && session.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) { c.geospatialMode = Config.GeospatialMode.ENABLED; changed = true }
                        if (changed) session.configure(c)
                        Log.i(TAG, "session geo=${c.geospatialMode} depth=${c.depthMode}")
                    }.onFailure { Log.w(TAG, "session config: ${it.message}") }
                }
                val cam = frame.camera
                holder.ticks++
                if (holder.ticks % 30 == 0L) runCatching {
                    frame.acquireCameraImage().use { img ->
                        val pl = img.planes[0]; val buf = pl.buffer; val rs = pl.rowStride; var sum = 0L; var n = 0
                        var y = img.height / 8; while (y < img.height) { var x = img.width / 8; while (x < img.width) { sum += (buf.get(y * rs + x).toInt() and 0xFF); n++; x += img.width / 8 }; y += img.height / 8 }
                        val luma = if (n > 0) sum / n else 255
                        val d = if (tooDark) luma < 55 else luma < 40                     // a little hysteresis
                        if (d != tooDark) tooDark = d
                    }
                }
                val darkNow = cam.trackingState != TrackingState.TRACKING && cam.trackingFailureReason == TrackingFailureReason.INSUFFICIENT_LIGHT
                if (darkNow != dark) dark = darkNow
                if (cam.trackingState == TrackingState.TRACKING) {
                    holder.frames++
                    val pose = cam.displayOrientedPose; val z = pose.zAxis
                    val fx = -z[0].toDouble(); val fz = -z[2].toDouble()
                    val camPos = V3(pose.tx().toDouble(), pose.ty().toDouble(), pose.tz().toDouble())
                    val r = route
                    val origin = r?.line?.origin
                    var usedVps = false
                    val earth = runCatching { session.earth }.getOrNull()
                    if (origin != null && earth != null && earth.trackingState == TrackingState.TRACKING) {
                        val gp = earth.cameraGeospatialPose
                        // camera forward in east-up-south from the pose quaternion → true heading
                        val q = gp.eastUpSouthQuaternion; val qx = q[0].toDouble(); val qy = q[1].toDouble(); val qz = q[2].toDouble(); val qw = q[3].toDouble()
                        val fe = -(2 * (qx * qz + qw * qy)); val fs = -(1 - 2 * (qx * qx + qy * qy))
                        val gh = (Math.toDegrees(kotlin.math.atan2(fe, -fs)) + 360) % 360
                        val hAcc = gp.orientationYawAccuracy
                        if (gp.horizontalAccuracy < 6 && hAcc < 20) {
                            align.heading(fx, fz, gh, if (hAcc < 6) 0.3 else 0.1)
                            align.position(camPos, Geo.toEnu(origin, LatLng(gp.latitude, gp.longitude)), if (gp.horizontalAccuracy < 2.5) 0.3 else 0.1)
                            usedVps = true
                        }
                    }
                    if (origin != null && !usedVps) {
                        val h = sensors.cameraHeading
                        if (!h.isNaN() && sensors.pitchUp > 45) align.heading(fx, fz, h, if (align.headingFixes < 30) 0.2 else 0.02)
                        val l = sensors.location
                        if (l != null && l.time != holder.lastFix) {
                            holder.lastFix = l.time
                            val acc = l.accuracy.toDouble().coerceAtLeast(3.0)
                            align.position(camPos, Geo.toEnu(origin, LatLng(l.latitude, l.longitude)), (9.0 / (acc * acc)).coerceIn(0.02, 0.5))
                        } else if (testFrom != null && !align.ready) align.position(camPos, Geo.toEnu(origin, testFrom), 1.0)
                    }
                    vps = usedVps; aligned = align.ready
                    heading = (Math.toDegrees(align.arAngle(fx, fz) - align.yaw) + 360) % 360
                    // the street's height: hit tests at several points low in the view (floor planes and depth), floor = low
                    // in their spread; with nothing seen yet, 1.4 m below the phone
                    val tNow = SystemClock.uptimeMillis()
                    if (viewW > 0 && holder.frames % 4 == 0L) for (pr in GROUND_PROBES) {
                        runCatching { frame.hitTest(viewW * pr[0], viewH * pr[1]) }.getOrNull()?.firstOrNull { hr ->
                            val y = hr.hitPose.ty(); val t = hr.trackable
                            y < camPos.y - 0.6 && y > camPos.y - 2.3 && ((t is Plane && t.type == Plane.Type.HORIZONTAL_UPWARD_FACING && t.isPoseInPolygon(hr.hitPose)) || t is DepthPoint)
                        }?.let { holder.ground.add(tNow, it.hitPose.ty().toDouble()) }
                    }
                    val found = holder.ground.estimate(tNow)
                    val gy = found ?: (camPos.y - 1.4)
                    holder.groundY = if (holder.groundY.isNaN()) gy else holder.groundY + (gy - holder.groundY) * (if (found != null) 0.2 else 0.03)
                    val sees = found != null || tNow - holder.startedAt < 4000
                    if (seeGround != sees) seeGround = sees
                    val n = nav
                    if (r != null && n != null && align.ready) {
                        val me = Geo.fromEnu(origin!!, align.toEnu(camPos)); holder.userGeo = me
                        val now = SystemClock.uptimeMillis()
                        if (now - holder.lastNav > 400) {
                            holder.lastNav = now
                            val acc = if (usedVps) 2.0 else (sensors.location?.accuracy?.toDouble() ?: 15.0)
                            for ((cue, step) in n.update(me, acc, now / 1000.0)) {
                                Log.i(TAG, "cue $cue ${step?.instruction}")
                                when (cue) {
                                    Navigator.Cue.PREPARE -> { say(w.t("בעוד ${w.meters(n.state.toNext)}, ", "In ${w.meters(n.state.toNext)}, ") + step!!.instruction); buzz(60) }
                                    Navigator.Cue.NOW -> { say(step!!.instruction); buzz(80, 120, 80); lookUp = now }
                                    Navigator.Cue.ARRIVED -> { say(w.t("הגענו! כל הכבוד!", "We made it! Well done!")); buzz(200, 100, 200); partyAt = now }
                                    Navigator.Cue.OFF_ROUTE -> if (!holder.rerouting && now - holder.lastReroute > 15000) { holder.rerouting = true; holder.lastReroute = now; say(w.t("מחשב מסלול מחדש", "Recalculating")); fetch(me) }
                                }
                            }
                            navState = n.state
                            // keep the drawing on your sidewalk, and let your own walking straighten the compass
                            holder.snap.update(align.toEnu(camPos), r.line, n.along, n.state.offset)
                            if (!usedVps) holder.motion.update(camPos, n.along, n.state.offset, r.line)?.let { m -> align.heading(m[0], m[1], m[2], 0.3); Log.i(TAG, "heading from walking: ${m[2].roundToInt()}°") }
                            val loc = sensors.location
                            if (!usedVps && loc != null && loc.hasBearing() && loc.speed > 0.9f && (android.os.Build.VERSION.SDK_INT < 26 || !loc.hasBearingAccuracy() || loc.bearingAccuracyDegrees < 25f)) {
                                val from = holder.courseFrom
                                if (from == null || now - holder.courseAt > 4000) { holder.courseFrom = camPos; holder.courseAt = now }
                                else if (hypot(camPos.x - from.x, camPos.z - from.z) > 4) { align.heading(camPos.x - from.x, camPos.z - from.z, loc.bearing.toDouble(), 0.15); holder.courseFrom = camPos; holder.courseAt = now }
                            } else holder.courseFrom = null
                        }
                        val toArR = { e: Enu, yy: Double -> align.toAr(holder.snap.apply(e), yy) }
                        run {
                            val t = toArR(r.line.at((n.along + 6).coerceAtMost(r.length)), holder.groundY)
                            val diff = Math.toDegrees(GeoAlign.wrap(align.arAngle(t.x - camPos.x, t.z - camPos.z) - align.arAngle(fx, fz))).toFloat()
                            val show = !n.state.arrived && n.state.offset < 25 && kotlin.math.abs(diff) > (if (turnAround != null) 35f else 55f)
                            val cur = turnAround
                            if (!show) { if (cur != null) turnAround = null }
                            else if (cur == null || kotlin.math.abs(cur - diff) > 4f) {
                                if (cur == null && now - holder.lastTurnBuzz > 8000) { holder.lastTurnBuzz = now; buzz(40) }
                                turnAround = diff
                            }
                        }
                        val view = FloatArray(16); val proj = FloatArray(16)
                        cam.getViewMatrix(view, 0); cam.getProjectionMatrix(proj, 0, 0.1f, 200f)
                        val pj = Projector(view, proj, viewW.toFloat().coerceAtLeast(1f), viewH.toFloat().coerceAtLeast(1f))
                        // Poppy: her own ground from a hit test under her feet (plane or depth), else the walker's ground
                        val dt = if (holder.lastTs == 0L) 1.0 / 30 else ((frame.timestamp - holder.lastTs) / 1e9).coerceIn(0.0, 0.1); holder.lastTs = frame.timestamp
                        val gyP = if (!holder.poppyGround.isNaN() && now - holder.poppyGroundAt < 2500) holder.poppyGround else holder.groundY
                        val st = n.state
                        val pp = poppy.step(dt, n.along, st.offset, r.length, st.arrived, holder.turns, V3(camPos.x, holder.groundY, camPos.z), camPos) { s -> toArR(r.line.at(s), gyP) }
                        if (viewW > 0 && holder.frames % 3 == 0L) pj.screen(pp.ground)?.let { foot ->
                            val hit = runCatching { frame.hitTest(foot[0], foot[1]) }.getOrNull()?.firstOrNull { hr ->
                                val y = hr.hitPose.ty(); val t = hr.trackable
                                y < camPos.y - 0.7 && y > camPos.y - 2.4 && hypot(hr.hitPose.tx() - pp.ground.x, hr.hitPose.tz() - pp.ground.z) < 2.5 &&
                                    ((t is Plane && t.type == Plane.Type.HORIZONTAL_UPWARD_FACING && t.isPoseInPolygon(hr.hitPose)) || t is DepthPoint)
                            }
                            if (hit != null) {
                                val y = hit.hitPose.ty().toDouble()
                                holder.poppyGround = if (holder.poppyGround.isNaN() || now - holder.poppyGroundAt > 2500) y else holder.poppyGround + (y - holder.poppyGround) * 0.25
                                holder.poppyGroundAt = now; holder.groundY += (y - holder.groundY) * 0.05
                            }
                        }
                        refs.poppy?.let { node -> if (refs.rig == null) refs.rig = runCatching { PoppyRig(engine, node) }.onFailure { Log.w(TAG, "rig: ${it.message}") }.getOrNull() }
                        refs.rig?.apply(pp)
                        if (poppyShown == mapMode) poppyShown = !mapMode
                        // the trail in 3D: ribbon, gliding chevrons, her paw prints, her shadow
                        refs.trailRoot?.let { root -> if (refs.trail == null) refs.trail = Trail3D(modelLoader, root, gender) }
                        refs.trail?.let { t -> if (mapMode) t.hideAll() else t.update(r, toArR, holder.groundY, n.along, if (pp.onRoute) poppy.along else Double.NaN, pp, now / 1000.0) }
                        // her speech bubble and her little voice
                        if (poppy.sayCount != holder.lastSay) {
                            holder.lastSay = poppy.sayCount
                            val s = poppy.say; bubble = s
                            when (s) {
                                PoppyGuide.Say.LETS_GO, PoppyGuide.Say.WAKE, PoppyGuide.Say.ARRIVED, PoppyGuide.Say.LEFT_HERE, PoppyGuide.Say.RIGHT_HERE -> voice.giggle()
                                PoppyGuide.Say.YAWN -> voice.yawn()
                                PoppyGuide.Say.ZZZ -> voice.snore()
                                PoppyGuide.Say.WAIT_FOR_ME -> voice.boing()
                                null -> {}
                                else -> voice.chirp(3)
                            }
                        } else if (poppy.say == null && bubble != null) bubble = null
                        // labels over the camera: the next turn, the destination, Poppy's head (bubble, confetti)
                        if (viewW > 0) {
                            val y = holder.groundY + 0.02
                            val step = r.steps.getOrNull(st.stepIndex)
                            val turn = step?.takeIf { it.type != "arrive" && st.toNext < 120 }?.let { pj.screen(toArR(r.line.at(it.along), y + 2.0)) }
                            val dpt = if (st.remaining < 150) pj.screen(toArR(r.line.at(r.length), y + 2.4)) else null
                            overlay = Overlay(turn, dpt, poppyHead(pj, pp))
                        }
                    }
                }
            },
        ) {
            poppyInstance?.let { ModelNode(modelInstance = it, autoAnimate = false, isVisible = poppyShown, scale = Scale(PoppyGuide.SCALE), apply = { refs.poppy = this }) }
            Node(apply = { refs.trailRoot = this }) { }
        }
        val o = overlay
        val density = LocalDensity.current
        // labels and Poppy's bubble sit at screen pixels from the AR projection, so this layer is laid out left-to-right
        // whatever the language (in Hebrew a right-to-left Box would anchor them to the right edge and push them aside),
        // and every label is kept inside the screen
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(Modifier.fillMaxSize()) {
                val sw = viewW / density.density; val sh = viewH / density.density
                fun ax(px: Float, half: Float) = ((px / density.density) - half).coerceIn(8f, (sw - 2 * half - 8f).coerceAtLeast(8f)).dp
                fun ay(px: Float, up: Float) = ((px / density.density) - up).coerceIn(150f, (sh - 200f).coerceAtLeast(150f)).dp
                o?.turn?.takeIf { !mapMode && turnAround == null }?.let { p ->
                    val st = navState; val step = route?.steps?.getOrNull(st?.stepIndex ?: -1)
                    if (step != null) Column(Modifier.absoluteOffset(x = ax(p[0], 70f), y = ay(p[1], 64f)).width(140.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.clip(RoundedCornerShape(14.dp)).background(Color.White).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            TurnIcon(step, Ink, 22); Spacer(Modifier.width(6.dp)); Text(w.dist(st!!.toNext), color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                        }
                        Box(Modifier.width(2.dp).height(40.dp).background(Color.White.copy(alpha = 0.9f)))
                    }
                }
                o?.dest?.takeIf { !mapMode }?.let { p ->
                    Column(Modifier.absoluteOffset(x = ax(p[0], 60f), y = ay(p[1], 52f)).width(120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(dest.name, color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Amber).padding(horizontal = 10.dp, vertical = 5.dp))
                        Box(Modifier.width(2.dp).height(36.dp).background(Amber))
                    }
                }
                // Poppy's speech bubble over her head (centred on her, kept on screen)
                val b = bubble
                val head = o?.head
                if (b != null && !mapMode && head != null) {
                    Column(Modifier.absoluteOffset(x = ax(head[0], 90f), y = ay(head[1], 62f)).width(180.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(w.bubble(b), color = Ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(max = 180.dp).clip(RoundedCornerShape(18.dp)).background(Color.White).border(2.dp, if (gender == "boy") Color(0xFF63AEF0) else Color(0xFFF484BD), RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
                        Canvas(Modifier.size(18.dp, 10.dp)) { drawPath(Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height); close() }, Color.White) }
                    }
                }
            }
        }
        // facing the wrong way: a big arrow that turns you towards the path (yours and Poppy's)
        turnAround?.takeIf { !mapMode }?.let { deg -> TurnAround(w, deg, Modifier.align(Alignment.Center)) }
        // arrival: confetti and hearts around Poppy
        if (partyAt > 0 && !mapMode) Party(partyAt, o?.head)
        if (mapMode) bigMap?.let { Image(it.asImageBitmap(), w.t("מפת המסלול", "Route map"), Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }

        // ---- top: the next manoeuvre ----
        val st = navState; val r = route
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val step = r?.steps?.getOrNull(st?.stepIndex ?: -1)
            Row(Modifier.fillMaxWidth().darkGlass(RoundedCornerShape(24.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Cyan), contentAlignment = Alignment.Center) { if (step != null) TurnIcon(step, Ink, 34) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(when {
                        error != null -> error!!
                        st?.arrived == true -> w.t("הגעת ליעד", "You have arrived")
                        st?.offRoute == true -> w.t("מחשב מסלול מחדש…", "Recalculating…")
                        step != null -> step.instruction
                        sensors.location.let { it == null || it.accuracy > 20f } && route == null -> w.t("מחפש את המיקום המדויק שלך", "Finding your exact location") + (sensors.location?.let { w.t(" (±${it.accuracy.roundToInt()} מ׳)", " (±${it.accuracy.roundToInt()} m)") } ?: "…")
                        else -> w.t("מחפש מסלול הליכה…", "Finding a walking route…")
                    }, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (st != null && step != null && st.arrived.not()) Text(w.dist(st.toNext), color = Cyan, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
            val then = r?.steps?.getOrNull((st?.stepIndex ?: -1) + 1)
            if (then != null && (st?.toNext ?: 999.0) < 60) Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Ink.copy(alpha = 0.85f)).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(w.t("ואז ", "Then "), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp); TurnIcon(then, Color.White, 18)
            }
            if (tooDark && !dark && !mapMode) Hint(w.t("חשוך מדי בשביל AR מדויק. ביום זה עובד הכי טוב; עכשיו אפשר להניח את הטלפון שטוח וללכת לפי המפה.", "Too dark for accurate AR. It works best in daylight; for now lay the phone flat and follow the map."), Amber)
            else if (dark) Hint(w.t("חשוך מדי ל-AR. הנח את הטלפון שטוח כדי לראות מפה.", "Too dark for AR. Lay the phone flat to see the map."), Amber)
            else if (BuildConfig.AR_GEO_KEY.isNotEmpty() && !vps && aligned && !mapMode && SystemClock.uptimeMillis() - holder.startedAt < 40_000) Hint(w.t("לדיוק מלא: כוון לרגע את המצלמה לבניינים מסביב", "For full accuracy: point the camera at the buildings around you for a moment"))
            else if (aligned && !seeGround && !mapMode) Hint(w.t("כוון את המצלמה רגע למדרכה, כדי שפופי יעמוד עליה", "Point the camera at the sidewalk for a moment so Poppy can stand on it"))
            else if (!aligned && route != null) Hint(w.t("הרם את הטלפון והסתכל קדימה לרגע", "Hold the phone up and look ahead for a moment"))
            else if (!vps && (sensors.location?.accuracy ?: 0f) > 25f) Hint(w.t("GPS חלש כאן: פופי עלול לזוז כמה מטרים", "Weak GPS here: Poppy may be a few metres off"))
            if (SystemClock.uptimeMillis() - lookUp < 4000) Hint(w.t("הרם את הראש: תסתכל על הרחוב, לא על המסך", "Look up: watch the street, not the screen"), Amber)
        }

        // ---- corner map ----
        if (!mapMode && !mapOpen) miniMap?.let { bm ->
            Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 12.dp, bottom = 104.dp).size(88.dp).clip(CircleShape).border(3.dp, Color.White.copy(alpha = 0.85f), CircleShape)) {
                Image(bm.asImageBitmap(), w.t("המפה סביבך", "Map around you"), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Canvas(Modifier.size(16.dp).align(Alignment.Center)) { drawPath(Path().apply { moveTo(size.width / 2, 0f); lineTo(size.width, size.height); lineTo(size.width / 2, size.height * 0.72f); lineTo(0f, size.height); close() }, Color(0xFFFF3B30)) }
            }
        }

        // ---- the map that slides up with a tap ----
        AnimatedVisibility(mapOpen && !mapMode, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 86.dp),
            enter = slideInVertically(spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(tween(180)),
            exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(200))) {
            MapSheet(w, api, route, navState?.along ?: 0.0, if (poppy.mode == PoppyGuide.Mode.LEAD || poppy.mode == PoppyGuide.Mode.PARTY) poppy.along else Double.NaN,
                { here() }, { heading }, gender, navState?.let { "${w.dist(it.remaining)} · ${w.mins(it.etaSec)}" } ?: dest.name) { mapOpen = false }
        }

        // ---- bottom: time, distance, controls ----
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp).darkGlass(RoundedCornerShape(26.dp)).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(st?.let { w.mins(it.etaSec) } ?: "—", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Text(st?.let { "${w.dist(it.remaining)} · ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + (it.etaSec * 1000).toLong()))}" + if (vps) " · VPS" else "" } ?: dest.name, color = Muted, fontSize = 13.sp)
            }
            Row(Modifier.clip(RoundedCornerShape(12.dp)).background(if (mapOpen) Cyan else Card).clickable { mapOpen = !mapOpen }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(16.dp)) {                                     // a folded map
                    val c = if (mapOpen) Ink else Color.White; val wd = size.width; val ht = size.height
                    drawPath(Path().apply { moveTo(0f, ht * 0.15f); lineTo(wd * 0.33f, 0f); lineTo(wd * 0.66f, ht * 0.15f); lineTo(wd, 0f); lineTo(wd, ht * 0.85f); lineTo(wd * 0.66f, ht); lineTo(wd * 0.33f, ht * 0.85f); lineTo(0f, ht); close() }, c, style = Stroke(width = 3f))
                    drawLine(c, Offset(wd * 0.33f, 0f), Offset(wd * 0.33f, ht * 0.85f), 3f); drawLine(c, Offset(wd * 0.66f, ht * 0.15f), Offset(wd * 0.66f, ht), 3f)
                }
                Spacer(Modifier.width(6.dp))
                Text(w.t("מפה", "Map"), color = if (mapOpen) Ink else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            Text(if (muted) w.t("מושתק", "Sound off") else w.t("קול", "Sound on"), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Card).clickable { muted = !muted }.padding(horizontal = 12.dp, vertical = 10.dp))
            Spacer(Modifier.width(8.dp))
            Text(w.t("סיום", "End"), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Amber).clickable { onEnd() }.padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}

/** Arrival party: confetti bursting from Poppy's head and hearts floating up, for a few seconds. */
@Composable
private fun Party(startedAt: Long, head: FloatArray?) {
    var now by remember { mutableStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(startedAt) { while (SystemClock.uptimeMillis() - startedAt < 9000) withFrameMillis { now = SystemClock.uptimeMillis() } }
    val t = (now - startedAt) / 1000f
    if (t > 9f) return
    val colors = listOf(Color(0xFFFFE680), Color(0xFFFF7BA8), Color(0xFF9FE8FF), Color(0xFFA7F3D0), Color(0xFFFFFFFF), Color(0xFFF5B841))
    Canvas(Modifier.fillMaxSize()) {
        val hx = head?.get(0) ?: (size.width / 2); val hy = head?.get(1) ?: (size.height * 0.45f)
        // confetti: three bursts, each piece flies out and falls, spinning
        for (burst in 0 until 3) {
            val tb = t - burst * 2.2f; if (tb < 0f || tb > 3.2f) continue
            for (i in 0 until 46) {
                val a = (i * 2.399 + burst).toFloat(); val sp = 380f + (i * 37 % 300)
                val x = hx + cos(a) * sp * tb; val y = hy + sin(a) * sp * tb * 0.8f - 520f * tb + 900f * tb * tb
                val alpha = (1f - tb / 3.2f).coerceIn(0f, 1f)
                rotate(degrees = (i * 47 + tb * 540f) % 360f, pivot = Offset(x, y)) { drawRect(colors[i % colors.size].copy(alpha = alpha), Offset(x - 9f, y - 5f), Size(18f, 10f)) }
            }
        }
        // hearts floating up and swaying
        for (i in 0 until 10) {
            val th = t - i * 0.55f; if (th < 0f || th > 2.6f) continue
            val x = hx + ((i * 53) % 160 - 80) + sin(th * 4f + i) * 18f; val y = hy - 40f - th * 170f; val s = 26f + (i % 3) * 8f
            val alpha = (1f - th / 2.6f).coerceIn(0f, 1f)
            drawPath(Path().apply {
                moveTo(x, y + s * 0.35f)
                cubicTo(x - s * 0.9f, y - s * 0.25f, x - s * 0.35f, y - s * 0.95f, x, y - s * 0.4f)
                cubicTo(x + s * 0.35f, y - s * 0.95f, x + s * 0.9f, y - s * 0.25f, x, y + s * 0.35f)
                close()
            }, Color(0xFFFF5C8A).copy(alpha = alpha))
        }
    }
}

/** Facing away from the path: a glass disc with a big arrow that swings round to where the path is, and words. */
@Composable
private fun TurnAround(w: Words, deg: Float, modifier: Modifier) {
    val rot by androidx.compose.animation.core.animateFloatAsState(deg.coerceIn(-170f, 170f), spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow), label = "turn")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(132.dp).darkGlass(CircleShape), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(84.dp)) {
                rotate(rot) {
                    val c = Offset(size.width / 2, size.height / 2); val r = size.width / 2
                    drawPath(Path().apply { moveTo(c.x, c.y - r); lineTo(c.x + r * 0.62f, c.y - r * 0.05f); lineTo(c.x + r * 0.22f, c.y - r * 0.05f); lineTo(c.x + r * 0.22f, c.y + r * 0.85f); lineTo(c.x - r * 0.22f, c.y + r * 0.85f); lineTo(c.x - r * 0.22f, c.y - r * 0.05f); lineTo(c.x - r * 0.62f, c.y - r * 0.05f); close() }, Cyan)
                }
            }
        }
        Column(Modifier.darkGlass(RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(when { kotlin.math.abs(deg) > 140 -> w.t("הסתובב לאחור", "Turn around"); deg > 0 -> w.t("הסתובב ימינה", "Turn right"); else -> w.t("הסתובב שמאלה", "Turn left") },
                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(w.t("השביל שלך ושל פופי נמצא שם", "Your path and Poppy are that way"), color = Color(0xFFCBD5E1), fontSize = 14.sp)
        }
    }
}

@Composable private fun Hint(text: String, color: Color = Color.White) =
    Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Ink.copy(alpha = 0.85f)).padding(horizontal = 12.dp, vertical = 8.dp))

/** A turn arrow for a manoeuvre (left / right / slight / sharp / u-turn / straight / arrive). */
@Composable
private fun TurnIcon(step: Step, color: Color, sizeDp: Int) {
    val angle = when {
        step.type == "arrive" -> Float.NaN
        step.modifier.contains("uturn") -> 180f
        step.modifier == "sharp left" -> -135f; step.modifier == "sharp right" -> 135f
        step.modifier == "slight left" -> -40f; step.modifier == "slight right" -> 40f
        step.modifier == "left" -> -90f; step.modifier == "right" -> 90f
        else -> 0f
    }
    Canvas(Modifier.size(sizeDp.dp)) {
        val w = size.width; val s = Stroke(width = w * 0.13f, cap = StrokeCap.Round)
        if (angle.isNaN()) { drawCircle(color, w * 0.28f, Offset(w / 2, w * 0.4f), style = s); drawLine(color, Offset(w / 2, w * 0.68f), Offset(w / 2, w * 0.92f), w * 0.13f, StrokeCap.Round); return@Canvas }
        val a = Math.toRadians(angle.toDouble()); val bx = w / 2; val by = w * 0.92f; val mx = bx; val my = w * 0.5f
        val tx = (mx + kotlin.math.sin(a) * w * 0.38).toFloat(); val ty = (my - kotlin.math.cos(a) * w * 0.38).toFloat()
        drawLine(color, Offset(bx, by), Offset(mx, my), w * 0.13f, StrokeCap.Round); drawLine(color, Offset(mx, my), Offset(tx, ty), w * 0.13f, StrokeCap.Round)
        val hl = w * 0.24
        for (d in listOf(-0.62, 0.62)) drawLine(color, Offset(tx, ty), Offset((tx - kotlin.math.sin(a + d) * hl).toFloat(), (ty + kotlin.math.cos(a + d) * hl).toFloat()), w * 0.13f, StrokeCap.Round)
    }
}

/** CI self-test without AR: a real walking route in Tel Aviv, a simulated walk along it, the cues, a search, and the map image. */
@Composable
fun WalkTest() {
    val api = remember { MapboxApi(BuildConfig.MAPBOX_TOKEN, "he") }
    var text by remember { mutableStateOf("walk test…") }
    LaunchedEffect(Unit) {
        val T = "ZoharSmoke"
        if (BuildConfig.MAPBOX_TOKEN.isEmpty()) { Log.i(T, "walktest no MAPBOX_TOKEN secret in this build"); Log.i(T, "walktest done"); text = "no token"; return@LaunchedEffect }
        runCatching {
            val from = LatLng(32.0753, 34.7746); val to = LatLng(32.0808, 34.7813)
            val r = withContext(Dispatchers.IO) { api.walk(from, to) }
            Log.i(T, "walktest route ${r.length.roundToInt()} m ${r.steps.size} steps")
            r.steps.forEach { Log.i(T, "walktest step @${it.along.roundToInt()} ${it.type}/${it.modifier}: ${it.instruction}") }
            val n = Navigator(r); var t = 0.0; var s = 0.0; val cues = ArrayList<String>()
            while (s <= r.length + 5) { val p = Geo.fromEnu(r.line.origin, r.line.at(s)); for ((c, st) in n.update(p, 5.0, t)) cues.add("$c@${s.roundToInt()}:${st?.instruction}"); s += 1.4; t += 1.0 }
            cues.forEach { Log.i(T, "walktest cue $it") }
            Log.i(T, "walktest arrived=${n.state.arrived} cues=${cues.size}")
            // walking off the route triggers a reroute cue
            val n2 = Navigator(r); var off = false
            for (k in 0..20) { val p = Geo.fromEnu(r.line.origin, Enu(r.line.at(60.0).e + 80, r.line.at(60.0).n)); if (n2.update(p, 5.0, k.toDouble()).any { it.first == Navigator.Cue.OFF_ROUTE }) off = true }
            Log.i(T, "walktest offroute=$off")
            val places = withContext(Dispatchers.IO) { api.search("דיזנגוף סנטר", from) }
            Log.i(T, "walktest search ${places.size}: ${places.take(3).joinToString { it.name + " @" + Geo.distance(from, it.at).roundToInt() + "m" }}")
            val bm = withContext(Dispatchers.IO) { URL(api.staticMapUrl(r, from, 15.5, 30.0, 300, 300)).openStream().use { BitmapFactory.decodeStream(it) } }
            DebugCapture.screen("walk_map", bm, 300)
            text = "ok ${r.length.roundToInt()} m"
        }.onFailure { Log.e(T, "walktest failed", it); text = "failed: ${it.message}" }
        Log.i(T, "walktest done")
    }
    Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) { Text(text, color = Color.White) }
}
