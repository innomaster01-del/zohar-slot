package com.zohar.slotar

import com.zohar.slotar.scratch.ScratchLayout
import com.zohar.slotar.scratch.ScratchState
import org.junit.Assert.*
import org.junit.Test

class ScratchStateTest {
    @Test fun scratchingACellRevealsOnlyThatCell() {
        val s = ScratchState(); val c = ScratchLayout.cell(4)
        var y = c.t + 10; while (y < c.b) { s.stroke(c.l, y, c.r, y, 30f); y += 30 }     // zig-zag over the centre cell
        assertTrue(s.revealed(4)); assertFalse(s.revealed(0)); assertFalse(s.allRevealed())
    }
    @Test fun regionsDoNotOverlapAndFitTheCard() {
        val r = ScratchLayout.regions
        for (i in r.indices) { assertTrue(r[i].l >= 0 && r[i].r <= ScratchLayout.W && r[i].t >= 0 && r[i].b <= ScratchLayout.H)
            for (j in i + 1 until r.size) assertFalse("$i/$j overlap", r[i].l < r[j].r && r[j].l < r[i].r && r[i].t < r[j].b && r[j].t < r[i].b) }
    }
    @Test fun revealAllRevealsEverything() { val s = ScratchState(); s.revealAll(); assertTrue(s.allRevealed()) }
}
