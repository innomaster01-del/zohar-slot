package com.zohar.slotar

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.zohar.slotar.audio.SlotSounds
import kotlinx.coroutines.delay

/**
 * CI only, no 3D: plays a machine's game on simulated time through its real screen renderer, checks after every spin that
 * the reels show exactly what the engine decided, and logs screen captures of the moments that matter (a win, the feature).
 */
@Composable
fun ScreenTest(machineId: String) {
    val context = LocalContext.current
    val sounds = remember { SlotSounds(context).also { it.muted = true } }
    val game = remember { Machines.create(machineId, sounds) {} }
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val T = "ZoharSmoke"
        Log.i(T, "screentest $machineId start")
        var spins = 0; var bad = 0; var wins = 0; var feature = 0
        val shots = HashSet<String>()
        fun shot(name: String) { if (shots.add(name)) DebugCapture.screen("${machineId}_$name", game.screen, 360) }
        game.update(0.0); shot("idle")
        var featT = -1.0; var featShots = 0; var featureDone = false
        while (spins < 900 && !featureDone) {
            if (!game.busy && !game.inFree && game.credits >= 0 && game.credits < game.bet * 2) game.spin()     // free refill path
            val r = if (game.busy) "auto" else game.spin()                // free spins continue on their own
            if (r != "spin" && r != "auto") { game.update(0.1); continue }
            spins++
            var t = 0.0
            while (game.busy && t < 180.0) {
                game.update(1.0 / 30); t += 1.0 / 30
                if (game.inFree) {
                    if (featT < 0) { featT = 0.0; feature++; Log.i(T, "screentest $machineId feature starts at spin $spins") }
                    featT += 1.0 / 30
                    if (featShots < 7 && featT >= 0.8 + featShots * 2.6) { featShots++; shot("feature$featShots") }
                }
                if ((t * 30).toInt() % 15 == 0) { frame++; delay(1) }
            }
            if (featT >= 0 && !game.inFree) featureDone = true
            val ok = game.shownMatchesEngine(); if (!ok) bad++
            if (spins <= 2) shot("spin$spins")
            if (t > 2.6 && wins < 2 && featT < 0) { wins++; shot("win$wins") }
            game.update(0.6)
            if (spins % 20 == 0 || !ok || featT >= 0) Log.i(T, "screentest $machineId spin $spins result=$r shown==engine=$ok credits=${game.credits} t=${"%.1f".format(t)}")
        }
        // bet buttons
        val b0 = game.bet; game.bet(+1); val b1 = game.bet; game.bet(99); val b2 = game.bet; game.bet(-1)
        Log.i(T, "screentest $machineId bets $b0 → $b1 → max $b2 → ${game.bet}")
        Log.i(T, "screentest $machineId done spins=$spins mismatches=$bad features=$feature")
        game.topper?.let { DebugCapture.screen("${machineId}_topper", it, 480) }
    }
    if (frame >= 0) Image(game.screen.asImageBitmap(), null, Modifier.fillMaxSize())
}
