package com.stocktracker.core.data.sync

/** A same-key change on both sides that [threeWayMerge] couldn't auto-resolve. */
data class MergeConflict<T>(val key: String, val local: T?, val remote: T?)

data class MergeResult<T>(val merged: List<T>, val conflicts: List<MergeConflict<T>>)

/**
 * Three-way merge over a keyed list — see the Mobile Sync Blueprint, Phase 3
 * "Merge". [base] is the last-synced snapshot (the common ancestor); [local]
 * is this device's current state; [remote] is the server's current state,
 * fetched fresh right before push.
 *
 * - Unchanged on both sides → kept as-is.
 * - Changed on exactly one side → that side's version wins (this is how two
 *   independent additions from two different devices both survive: each is
 *   "changed on exactly one side" relative to the shared base).
 * - Changed identically on both sides → no real conflict, that value wins.
 * - Changed on both sides to *different* values (same key) → a genuine
 *   conflict. If [resolveConflict] is supplied (e.g. compare `updatedAt` for
 *   a key that carries a real modification timestamp), it picks the winner
 *   silently. Otherwise the conflict is reported in [MergeResult.conflicts]
 *   for the caller to surface to the user — the local version is kept
 *   provisionally so a push isn't left empty while that's pending.
 * - Deleted on one side only → the deletion wins, unless the other side also
 *   changed it, which is an edit-vs-delete conflict handled the same way as
 *   an edit-vs-edit conflict above.
 */
fun <T> threeWayMerge(
    base: List<T>,
    local: List<T>,
    remote: List<T>,
    keyOf: (T) -> String,
    resolveConflict: ((local: T, remote: T) -> T)? = null,
): MergeResult<T> {
    val baseMap = base.associateBy(keyOf)
    val localMap = local.associateBy(keyOf)
    val remoteMap = remote.associateBy(keyOf)
    val allKeys = baseMap.keys + localMap.keys + remoteMap.keys

    val merged = mutableListOf<T>()
    val conflicts = mutableListOf<MergeConflict<T>>()

    for (key in allKeys) {
        val b = baseMap[key]
        val l = localMap[key]
        val r = remoteMap[key]
        val localChanged = l != b
        val remoteChanged = r != b

        when {
            !localChanged && !remoteChanged -> b?.let(merged::add)
            localChanged && !remoteChanged -> l?.let(merged::add)
            !localChanged && remoteChanged -> r?.let(merged::add)
            l != null && r != null && l == r -> merged.add(l)
            l != null && r != null -> {
                if (resolveConflict != null) merged.add(resolveConflict(l, r))
                else { conflicts.add(MergeConflict(key, l, r)); merged.add(l) }
            }
            l != null -> { // remote deleted it, local changed it
                if (resolveConflict == null) conflicts.add(MergeConflict(key, l, null))
                merged.add(l)
            }
            r != null -> { // local deleted it, remote changed it
                if (resolveConflict == null) conflicts.add(MergeConflict(key, null, r))
                merged.add(r)
            }
            // else: both sides deleted it — nothing to add.
        }
    }

    return MergeResult(merged, conflicts)
}
