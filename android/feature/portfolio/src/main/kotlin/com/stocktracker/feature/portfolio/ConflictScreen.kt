package com.stocktracker.feature.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.stocktracker.core.data.sync.ConflictCenter
import com.stocktracker.core.data.sync.PendingConflict
import com.stocktracker.core.data.sync.SyncCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConflictViewModel @Inject constructor(
    conflictCenter: ConflictCenter,
    private val syncCoordinator: SyncCoordinator,
) : ViewModel() {
    val pending: StateFlow<List<PendingConflict>> = conflictCenter.pending

    fun resolve(conflict: PendingConflict, keepLocal: Boolean) {
        viewModelScope.launch { syncCoordinator.resolveConflict(conflict, keepLocal) }
    }
}

/**
 * Lets the user pick a winner for each pending same-record sync conflict —
 * see ConflictCenter and the Mobile Sync Blueprint, Phase 3 "Merge". This is
 * a rare-path safety net (single-device use never reaches it), so the
 * before/after values render via each record's own `toString()` rather than
 * a bespoke per-type diff view.
 */
@Composable
fun ConflictRoute(onBack: () -> Unit, viewModel: ConflictViewModel = hiltViewModel()) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    ConflictScreen(pending = pending, onResolve = viewModel::resolve, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConflictScreen(pending: List<PendingConflict>, onResolve: (PendingConflict, Boolean) -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync conflicts") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        if (pending.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No conflicts.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(pending, key = { "${it.storageKey}::${it.recordKey}" }) { conflict ->
                    ConflictCard(conflict, onResolve = { keepLocal -> onResolve(conflict, keepLocal) })
                }
            }
        }
    }
}

@Composable
private fun ConflictCard(conflict: PendingConflict, onResolve: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(conflict.recordKey, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("This device", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            Text(conflict.local?.toString() ?: "(deleted)", style = MaterialTheme.typography.bodySmall)
            Text("Server", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            Text(conflict.remote?.toString() ?: "(deleted)", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onResolve(false) }) { Text("Keep server's") }
                Button(onClick = { onResolve(true) }) { Text("Keep mine") }
            }
        }
    }
}
