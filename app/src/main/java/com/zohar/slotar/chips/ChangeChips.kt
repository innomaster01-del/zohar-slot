package com.zohar.slotar.chips

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zohar.slotar.Ctl

/** "Change chips": tap a chip of yours to change it into smaller ones (a 500 into five 100s, and so on). */
@Composable
fun ChangeChips(view: BankView) {
    var open by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val v = view.version          // redraw when the chips change
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (open) {
            val list = view.breakable()
            if (list.isEmpty()) Text("Only red 5s: nothing smaller to change into", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
            else Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                for (c in list) {
                    val img = remember(c) { runCatching { ctx.assets.open("cards/chip_$c.png").use { android.graphics.BitmapFactory.decodeStream(it) }.asImageBitmap() }.getOrNull() }
                    val i = ChipBank.VALUES.indexOf(c); val down = ChipBank.VALUES[i - 1]
                    Column(Modifier.clickable { view.breakChip(c) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        if (img != null) Image(img, contentDescription = "Change a $c chip", modifier = Modifier.size(46.dp).clip(CircleShape))
                        Text("→ ${c / down} × ${label(down)}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Ctl(if (open) "Done" else "Change chips", small = true) { open = !open }
    }
}
private fun label(v: Long) = if (v >= 1000) "${v / 1000}K" else "$v"
