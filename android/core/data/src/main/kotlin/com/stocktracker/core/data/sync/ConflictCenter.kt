package com.stocktracker.core.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pending same-record conflicts a push couldn't auto-resolve, exposed to the
 * UI as a one-tap "keep yours / keep server's" prompt instead of a silent
 * overwrite — see the Mobile Sync Blueprint, Phase 3 "Merge". A resolved
 * conflict clears from here and the caller re-runs the push, which now
 * succeeds because the resolved value becomes the new local state.
 */
@Singleton
class ConflictCenter @Inject constructor() {
    private val _pending = MutableStateFlow<List<PendingConflict>>(emptyList())
    val pending: StateFlow<List<PendingConflict>> = _pending

    fun report(portfolioId: String, storageKey: String, conflicts: List<MergeConflict<*>>) {
        val entries = conflicts.map { PendingConflict(portfolioId, storageKey, it.key, it.local, it.remote) }
        _pending.update { current -> current.filterNot { it.storageKey == storageKey } + entries }
    }

    fun clear(storageKey: String, recordKey: String) {
        _pending.update { current -> current.filterNot { it.storageKey == storageKey && it.recordKey == recordKey } }
    }
}

data class PendingConflict(
    val portfolioId: String,
    val storageKey: String,
    val recordKey: String,
    val local: Any?,
    val remote: Any?,
)
