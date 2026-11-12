package com.zohar.slotar.chips

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node

/**
 * The player's chips standing on the table in front of him, in upright stacks: one column for each colour (the biggest
 * value nearest the middle of the table), up to 20 chips a stack, more stacks of the same colour behind it. They are
 * always worth exactly his credits (ChipBank); when the credits change, chips drop onto the stacks or leave them.
 * Which chips he holds is kept on the phone, so a chip he broke stays broken.
 *
 * origin: the felt point of the first column (table frame); along: the way the columns go; back: the way extra stacks go.
 */
class BankView(
    context: Context, private val key: String, private val modelLoader: ModelLoader,
    private val origin: Float3, private val along: Float3 = Float3(1f, 0f, 0f), private val back: Float3 = Float3(0f, 0f, -1f),
) {
    private val prefs = context.getSharedPreferences("zohar_chips", Context.MODE_PRIVATE)
    val bank: ChipBank = ChipBank.load(prefs.getString(key, null))
    private var parent: Node? = null
    private val pools = HashMap<Long, ArrayList<ModelNode>>()
    private class Drop(val node: ModelNode, val target: Float3, var t: Float)
    private val drops = ArrayList<Drop>()
    private var shown = ""
    /** Changes whenever the chips change, so the screen can redraw what depends on them. */
    var version by mutableIntStateOf(0); private set
    private var credits = -1L

    fun attach(frame: Node) { if (parent === frame) return; parent = frame; pools.clear(); drops.clear(); shown = "" }

    /** Every frame: follow the credits, lay the stacks out again when the chips changed, let new chips fall into place. */
    fun update(dt: Float, creditsNow: Long) {
        if (parent == null) return
        if (creditsNow != credits) { credits = creditsNow; bank.follow(maxOf(0L, creditsNow)); save() }
        val s = bank.save(); if (s != shown) { layout(shown.isNotEmpty()); shown = s; version++ }
        val it = drops.iterator()
        while (it.hasNext()) { val d = it.next(); d.t += dt
            val u = (d.t / 0.28f).coerceIn(0f, 1f); val y = d.target.y + 0.07f * (1f - u * u)
            if (d.t >= 0f) { d.node.isVisible = true; d.node.position = Position(d.target.x, y, d.target.z) }
            if (u >= 1f) it.remove() }
    }
    /** Change one chip of value v into smaller ones (the player's own request). */
    fun breakChip(v: Long): Boolean { val ok = bank.breakChip(v); if (ok) save(); return ok }
    fun breakable() = bank.breakable()
    private fun save() { prefs.edit().putString(key, bank.save()).apply() }

    private fun node(v: Long, i: Int): ModelNode {
        val pool = pools.getOrPut(v) { ArrayList() }
        while (pool.size <= i) pool.add(ModelNode(modelLoader.createModelInstance("models/bj_chip_$v.glb"), autoAnimate = false).also { it.isVisible = false; parent!!.addChildNode(it) })
        return pool[i]
    }
    private fun layout(animate: Boolean) {
        for (d in drops) d.node.isVisible = false; drops.clear()
        var col = 0; var stagger = 0f
        val wasVisible = pools.mapValues { (_, l) -> l.count { it.isVisible } }
        for (v in ChipBank.VALUES.reversed()) {
            val n = minOf(bank.counts.getValue(v), PER_STACK * MAX_STACKS)
            val pool = pools[v]
            if (n == 0) { pool?.forEach { it.isVisible = false }; continue }
            for (i in 0 until n) {
                val stack = i / PER_STACK; val k = i % PER_STACK
                val p = Float3(origin.x + along.x * PITCH * col + back.x * PITCH * stack, origin.y + k * CHIP_H, origin.z + along.z * PITCH * col + back.z * PITCH * stack)
                val nd = node(v, i)
                // a real stack is never perfectly straight: a hair of offset and a turn for each chip
                val j = ((i * 2654435761L + v) % 1000) / 1000f - 0.5f; val q = ((i * 40503L + v * 7) % 1000) / 1000f - 0.5f
                val target = Float3(p.x + j * 0.0012f, p.y, p.z + q * 0.0012f)
                nd.quaternion = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), (i * 37f + v) % 360f)
                if (animate && i >= (wasVisible[v] ?: 0)) { nd.isVisible = false; drops.add(Drop(nd, target, -stagger)); stagger += 0.035f }
                else { nd.isVisible = true; nd.position = Position(target.x, target.y, target.z) }
            }
            pool?.let { l -> for (i in n until l.size) l[i].isVisible = false }
            col++
        }
    }

    companion object {
        const val PER_STACK = 20; const val MAX_STACKS = 4
        const val CHIP_H = 0.0033f; const val PITCH = 0.044f
    }
}
