package com.stocktracker.feature.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stocktracker.core.data.PositionRepository
import com.stocktracker.core.data.sync.SyncCoordinator
import com.stocktracker.core.data.sync.SyncTarget
import com.stocktracker.core.model.Position
import com.stocktracker.core.model.PositionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class AddPositionViewModel @Inject constructor(
    private val positionRepository: PositionRepository,
    private val syncCoordinator: SyncCoordinator,
) : ViewModel() {

    /** Mirrors AddPositionModal's fields. */
    fun addPosition(
        portfolioId: String,
        ticker: String,
        name: String,
        type: PositionType,
        quantity: Double,
        buyPrice: Double,
        buyDate: String,
        currency: String,
        broker: String?,
        isin: String?,
        isClosed: Boolean,
        sellPrice: Double?,
        sellDate: String?,
        onDone: () -> Unit,
    ) {
        viewModelScope.launch {
            val position = Position(
                id = UUID.randomUUID().toString(),
                ticker = ticker.uppercase(),
                name = name,
                type = type,
                quantity = quantity,
                buyPrice = buyPrice,
                buyDate = buyDate,
                currency = currency,
                broker = broker?.ifBlank { null },
                isin = isin?.ifBlank { null },
                sellPrice = if (isClosed) sellPrice else null,
                sellDate = if (isClosed) sellDate else null,
            )
            positionRepository.upsert(portfolioId, position)
            syncCoordinator.enqueuePush(portfolioId, SyncTarget.POSITIONS)
            onDone()
        }
    }
}
