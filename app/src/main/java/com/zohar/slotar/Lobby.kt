package com.zohar.slotar

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private class MachineCard(val id: String, val title: String, val game: String, val lines: List<String>, val accent: Color)

private val CARDS = listOf(
    MachineCard("zohar", "ZOHAR CLASSIC", "20 lines · free spins ×3", listOf("Classic cabinet with a real pull lever", "3 scatters = 10 free spins, all wins ×3"), Color(0xFF18C95A)),
    MachineCard("pharaoh", "PHARAOH'S BOOK", "10 lines · expanding symbol", listOf("49\" J-curve screen, gold light rails", "Book = wild + scatter · 3 books = 10 free spins", "One symbol expands over whole reels"), Color(0xFFF2B630)),
    MachineCard("dragon", "DRAGON FORTUNE", "243 ways · Hold & Win", listOf("C-curve screen + 27\" jackpot topper", "6+ coins = Hold & Win, 3 respins", "MINI · MINOR · MAJOR · GRAND 1000× bet"), Color(0xFFE0141E)),
)

/** Pick a machine. Each one is its own cabinet and its own game (play money only). */
@Composable
fun MachineLobby(onPick: (String) -> Unit) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF14061E), Color(0xFF06040A))))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("ZOHAR SLOTS", color = Color(0xFFFFD34D), fontSize = 30.sp, fontWeight = FontWeight.Black)
            Text("Choose a machine · it stands in your room in AR", color = Color(0xCCFFFFFF), fontSize = 14.sp)
            for (c in CARDS) {
                val img = remember(c.id) { runCatching { context.assets.open("lobby/${c.id}.jpg").use { BitmapFactory.decodeStream(it) } }.getOrNull() }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF1A1424)).border(2.dp, c.accent.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
                    .clickable { onPick(c.id) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 110.dp, height = 160.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                        if (img != null) Image(img.asImageBitmap(), c.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                        Text(c.game, color = c.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        for (l in c.lines) Text("• $l", color = Color(0xCCFFFFFF), fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("PLAY ›", Modifier.clip(RoundedCornerShape(18.dp)).background(c.accent).padding(horizontal = 16.dp, vertical = 6.dp), color = Color(0xFF0A0A0A), fontWeight = FontWeight.Black, fontSize = 15.sp)
                    }
                }
            }
            Text("Play money only · no real gambling, no purchases, no cash-out", color = Color(0x99FFFFFF), fontSize = 11.sp)
        }
    }
}
