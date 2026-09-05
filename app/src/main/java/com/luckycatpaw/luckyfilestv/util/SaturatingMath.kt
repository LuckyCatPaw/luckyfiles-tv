package com.luckycatpaw.luckyfilestv.util

/**
 * Adds without wrapping around, saturating at [Long.MAX_VALUE] instead.
 *
 * Everything that sums file sizes needs this. A single file cannot overflow a [Long], but a
 * running total over a tree can be pushed there by a sparse file, a broken listing or a
 * server reporting nonsense — and a wrapped total is worse than a wrong one: it goes
 * negative, which turns a progress bar backwards and makes a free space check pass on a
 * volume that has nothing left.
 *
 * Saturating is the right answer here because the value is only ever compared or displayed.
 * A total pinned at [Long.MAX_VALUE] reads as "more than fits", which is exactly what it is.
 *
 * Existed three times over, once each in the walker, the coordinator and the engine, which
 * is what a bare arithmetic helper tends to do. One copy so a fix reaches all of them.
 */
internal fun safeAdd(first: Long, second: Long): Long = if (Long.MAX_VALUE - first < second) {
    Long.MAX_VALUE
} else {
    first + second
}
