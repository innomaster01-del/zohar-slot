package com.zohar.slotar.walk

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val Night = Color(0xFF070B14)
private val Text1 = Color(0xFFF4F7FB); private val Text2 = Color(0xFFAEB9CB); private val Text3 = Color(0xFF7A879C)
private val Warm = Color(0xFFF5B841)
private val PoppyPink = Color(0xFFFF8CC6); private val PoppyBlue = Color(0xFF6CB8FF)

/** A walk longer than this asks first ("are you sure?"). */
internal const val FAR_M = 5000.0

/** The quick "what's around me" buttons: Mapbox category id, label (he / en), and a name to search by if the id finds nothing. */
internal class Kind(val id: String, val he: String, val en: String, val query: String)
internal val KINDS = listOf(
    Kind("coffee", "קפה", "Coffee", "בית קפה"),
    Kind("restaurant", "אוכל", "Food", "מסעדה"),
    Kind("pharmacy", "בית מרקחת", "Pharmacy", "בית מרקחת"),
    Kind("supermarket", "סופרמרקט", "Supermarket", "סופרמרקט"),
)

/** Press feedback: shrinks a touch on finger-down, springs back on release. */
@Composable
private fun pressScale(src: MutableInteractionSource): Float {
    val pressed by src.collectIsPressedAsState()
    return animateFloatAsState(if (pressed) 0.97f else 1f, spring(dampingRatio = 1f, stiffness = 900f), label = "press").value
}

/**
 * Where to? A dark night background lit softly in Poppy's colour, and glass on top of it: the search capsule, the
 * "around me" buttons with drawn icons, and the results as one grouped glass list, nearest first.
 */
@Composable
internal fun SearchScreen(w: Words, api: MapboxApi, sensors: WalkSensors, poppy: String, onPoppy: (String) -> Unit, onPick: (Place) -> Unit) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var here by remember { mutableStateOf<LatLng?>(null) }
    var acc by remember { mutableStateOf<Int?>(null) }
    var far by remember { mutableStateOf<Place?>(null) }
    var kind by remember { mutableStateOf<Kind?>(null) }
    val accent = if (poppy == "boy") PoppyBlue else PoppyPink
    val accentAnim by animateColorAsState(accent, label = "accent")
    LaunchedEffect(Unit) { while (true) { here = sensors.location?.let { LatLng(it.latitude, it.longitude) }; acc = sensors.location?.accuracy?.roundToInt(); delay(1000) } }
    fun search() {
        if (q.isBlank()) return
        busy = true; error = null; far = null; kind = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.search(q, here) } }
                .onSuccess { results = it; if (it.isEmpty()) error = w.t("לא נמצא כלום. נסה שם אחר, בעברית או באנגלית.", "Nothing found. Try another name.") }
                .onFailure { error = it.message }
            busy = false
        }
    }
    fun nearby(k: Kind) {
        val h = here ?: run { error = w.t("מחכה למיקום שלך…", "Waiting for your location…"); return }
        busy = true; error = null; far = null; kind = k
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.nearby(k.id, k.query, h) } }
                .onSuccess { results = it; if (it.isEmpty()) error = w.t("לא מצאתי ${k.he} קרוב אליך", "No ${k.en.lowercase()} found near you") }
                .onFailure { error = it.message }
            busy = false
        }
    }
    fun pick(p: Place) { val h = here; if (h != null && Geo.distance(h, p.at) > FAR_M) far = p else onPick(p) }

    Box(Modifier.fillMaxSize().background(Night)) {
        // light: two soft glows (Poppy's colour high, warm low) so the glass has something to sit on
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.radialGradient(listOf(accentAnim.copy(alpha = 0.34f), Color.Transparent), center = Offset(size.width * 0.85f, size.height * 0.08f), radius = size.width * 0.95f))
            drawRect(Brush.radialGradient(listOf(Warm.copy(alpha = 0.16f), Color.Transparent), center = Offset(size.width * 0.05f, size.height * 0.78f), radius = size.width * 0.9f))
            drawRect(Brush.radialGradient(listOf(Color(0xFF3B82F6).copy(alpha = 0.10f), Color.Transparent), center = Offset(size.width * 0.2f, size.height * 0.3f), radius = size.width * 0.7f))
        }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(18.dp))
            // header: wordmark, and who walks with you (a small segmented glass control with both Poppys)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Follow Poppy", color = Text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp, modifier = Modifier.weight(1f))
                Row(Modifier.glass(CircleShape, 0.8f).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for ((k, label, c) in listOf(Triple("girl", w.t("בת", "Girl"), PoppyPink), Triple("boy", w.t("בן", "Boy"), PoppyBlue))) {
                        val on = poppy == k
                        Row(Modifier.clip(CircleShape).background(if (on) Color.White.copy(alpha = 0.16f) else Color.Transparent).clickable { onPoppy(k) }.padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Canvas(Modifier.size(22.dp)) { miniPoppyIcon(c, if (on) 1f else 0.45f) }
                            Spacer(Modifier.width(4.dp))
                            Text(label, color = if (on) Text1 else Text3, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Spacer(Modifier.height(26.dp))
            Text(w.t("לאן הולכים?", "Where to?"), color = Text1, fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
            Spacer(Modifier.height(6.dp))
            // where you are, quietly: a dot and the accuracy
            Row(verticalAlignment = Alignment.CenterVertically) {
                val good = here != null && (acc ?: 99) <= 20
                Box(Modifier.size(7.dp).clip(CircleShape).background(when { here == null -> Text3; good -> Color(0xFF4ADE80); else -> Warm }))
                Spacer(Modifier.width(8.dp))
                Text(when {
                    !sensors.precise -> w.t("מיקום משוער בלבד. הפעל ״מיקום מדויק״ להגדרות האפליקציה", "Approximate location only. Turn on Precise location in the app's settings")
                    here == null -> w.t("מאתר אותך…", "Finding you…")
                    good -> w.t("מיקום מדויק · ±${acc} מ׳", "Precise location · ±${acc} m")
                    else -> w.t("מדייק את המיקום · ±${acc} מ׳", "Refining your location · ±${acc} m")
                }, color = if (!sensors.precise) Warm else Text2, fontSize = 13.sp)
            }
            Spacer(Modifier.height(20.dp))
            // the search capsule
            Row(Modifier.fillMaxWidth().height(58.dp).glass(RoundedCornerShape(29.dp), 1.1f).padding(start = 18.dp, end = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                LineIcon("search", Text2, 20.dp)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    if (q.isEmpty()) Text(w.t("מקום, כתובת או עסק", "Place, address or business"), color = Text3, fontSize = 17.sp)
                    BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(color = Text1, fontSize = 17.sp, fontWeight = FontWeight.Medium), cursorBrush = SolidColor(accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }), modifier = Modifier.fillMaxWidth())
                }
                AnimatedVisibility(q.isNotBlank(), enter = scaleIn(spring(0.8f, 600f)) + fadeIn(), exit = scaleOut() + fadeOut()) {
                    val src = remember { MutableInteractionSource() }
                    Box(Modifier.size(44.dp).scale(pressScale(src)).clip(CircleShape).background(accent).clickable(src, null) { search() }, contentAlignment = Alignment.Center) {
                        if (busy) Text("…", color = Night, fontWeight = FontWeight.Bold) else LineIcon("arrow", Night, 20.dp, Modifier.scale(scaleX = if (w.he) -1f else 1f, scaleY = 1f))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            // around me
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in KINDS) {
                    val on = kind == k; val src = remember { MutableInteractionSource() }
                    Row(Modifier.scale(pressScale(src)).then(if (on) Modifier.clip(CircleShape).background(accent.copy(alpha = 0.22f)).border(1.dp, accent.copy(alpha = 0.7f), CircleShape) else Modifier.glass(CircleShape, 0.75f))
                        .clickable(src, null) { nearby(k) }.padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        LineIcon(k.id, if (on) accent else Text1, 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(w.t(k.he, k.en), color = Text1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            kind?.let { k -> Text(w.t("קרוב אליך", "Near you") + " · " + w.t(k.he, k.en), color = Text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) }
            error?.let { Text(it, color = Warm, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) }
            // results: one grouped glass list
            if (results.isNotEmpty()) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().glass(RoundedCornerShape(24.dp), 0.9f)) {
                    itemsIndexed(results) { i, p ->
                        val dist = here?.let { Geo.distance(it, p.at) }
                        val src = remember { MutableInteractionSource() }
                        Column(Modifier.scale(pressScale(src)).clickable(src, null) { pick(p) }) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) { LineIcon(kind?.id ?: "pin", Text2, 18.dp) }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, color = Text1, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val sub = listOf(p.alt, p.address).filter { it.isNotEmpty() }.joinToString(" · ")
                                    if (sub.isNotEmpty()) Text(sub, color = Text3, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                dist?.let {
                                    Spacer(Modifier.width(10.dp))
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(w.dist(it), color = if (it > FAR_M) Warm else Text1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                        Text(w.mins(it * 1.3 / 1.35), color = Text3, fontSize = 12.sp)
                                    }
                                }
                            }
                            if (i < results.size - 1) Box(Modifier.fillMaxWidth().padding(start = 66.dp).height(1.dp).background(Color.White.copy(alpha = 0.07f)))
                        }
                    }
                }
            } else Spacer(Modifier.weight(1f))
            Text(w.t("הליכה בלבד. כשחוצים כביש, מרימים את הראש מהמסך.", "Walking only. Look up from the screen when you cross a street."), color = Text3, fontSize = 12.sp, modifier = Modifier.padding(vertical = 14.dp))
        }
        // far away: ask before starting a walk of hours
        far?.let { p ->
            val dm = here?.let { Geo.distance(it, p.at) } ?: 0.0
            Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable { far = null }, contentAlignment = Alignment.Center) {
                Column(Modifier.padding(28.dp).darkGlass(RoundedCornerShape(28.dp)).clickable(enabled = false) {}.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(w.t("זה רחוק", "That's far"), color = Text1, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
                    Text(p.name, color = Text1, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(w.t("${w.dist(dm)} בקו אווירי, בערך ${w.mins(dm * 1.3 / 1.35)} הליכה. אולי התכוונת למקום אחר?", "${w.dist(dm)} away, about ${w.mins(dm * 1.3 / 1.35)} on foot. Did you mean another place?"), color = Text2, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(w.t("יוצאים בכל זאת", "Go anyway"), color = Night, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(CircleShape).background(Text1).clickable { far = null; onPick(p) }.padding(horizontal = 18.dp, vertical = 11.dp))
                        Text(w.t("חיפוש אחר", "Search again"), color = Text1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.glass(CircleShape, 0.8f).clickable { far = null }.padding(horizontal = 18.dp, vertical = 11.dp))
                    }
                }
            }
        }
    }
}
