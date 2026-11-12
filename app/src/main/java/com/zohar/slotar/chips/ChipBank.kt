package com.zohar.slotar.chips

/**
 * The player's own chips: how his play credits look as real chips in front of him. The value is always exactly his
 * credits; which chips make it up is kept from moment to moment, the way a real stack changes:
 *  - a win adds chips (the fewest that make the amount);
 *  - a loss or a bet pays with chips he has, the smallest first, and when they cannot make the exact amount a bigger
 *    chip is changed into smaller ones (as the dealer would);
 *  - he can change any chip into smaller ones himself (`breakChip`).
 * Pure logic, no graphics; the stacks on the table show `counts`.
 */
class ChipBank(initial: Map<Long, Int> = emptyMap()) {
    /** How many chips of each value he holds (values from VALUES only). */
    val counts: MutableMap<Long, Int> = linkedMapOf<Long, Int>().also { m -> for (v in VALUES) m[v] = initial[v] ?: 0 }
    val total: Long get() = counts.entries.sumOf { it.key * it.value }

    /** Make the chips worth exactly `credits` (credits are always a whole number of the smallest chip … see `remainder`). */
    fun follow(credits: Long) {
        val target = credits - credits % SMALLEST
        val now = total
        if (target > now) add(target - now) else if (target < now) pay(now - target)
    }
    /** What the chips cannot show (credits that are not a multiple of the smallest chip, 5). */
    fun remainder(credits: Long) = credits % SMALLEST

    private fun add(amount: Long) {
        var left = amount
        for (v in VALUES.reversed()) { val k = left / v; if (k > 0) { counts[v] = counts.getValue(v) + k.toInt(); left -= k * v } }
        check(left == 0L)
    }
    /** Pay `amount` from the chips: the smallest chips first; change a bigger chip when needed. */
    private fun pay(amount: Long) {
        require(amount <= total)
        var left = amount
        // take whole chips, smallest value first, while they fit
        for (v in VALUES) { while (left >= v && counts.getValue(v) > 0) { val k = minOf(counts.getValue(v).toLong(), left / v); counts[v] = counts.getValue(v) - k.toInt(); left -= k * v; if (k == 0L) break } }
        // what is left is smaller than every chip still held: change the smallest chip bigger than it
        while (left > 0) {
            val v = VALUES.first { it > left && counts.getValue(it) > 0 }
            counts[v] = counts.getValue(v) - 1
            add(v - left)            // the change comes back as the fewest chips
            left = 0
        }
    }
    /** Change one chip of value v into smaller chips (the next value down, e.g. 500 → 5 × 100). False if there is none. */
    fun breakChip(v: Long): Boolean {
        val i = VALUES.indexOf(v); if (i <= 0 || counts.getValue(v) == 0) return false
        val down = VALUES[i - 1]
        counts[v] = counts.getValue(v) - 1; counts[down] = counts.getValue(down) + (v / down).toInt()
        // 25 does not divide into 25s… every pair of neighbours divides: 5000/1000, 1000/500, 500/100, 100/25, 25/5
        return true
    }
    /** The values he could change now (he holds one and it has a smaller value). */
    fun breakable(): List<Long> = VALUES.drop(1).filter { counts.getValue(it) > 0 }.reversed()

    fun save(): String = VALUES.joinToString(",") { "${counts.getValue(it)}" }

    companion object {
        /** Chip values, smallest first: red 5, green 25, black 100, purple 500, yellow 1,000, brown 5,000. */
        val VALUES = listOf(5L, 25L, 100L, 500L, 1000L, 5000L)
        const val SMALLEST = 5L
        fun load(s: String?): ChipBank {
            val parts = s?.split(',')?.mapNotNull { it.toIntOrNull() } ?: emptyList()
            return ChipBank(if (parts.size == VALUES.size) VALUES.zip(parts).toMap() else emptyMap())
        }
    }
}
