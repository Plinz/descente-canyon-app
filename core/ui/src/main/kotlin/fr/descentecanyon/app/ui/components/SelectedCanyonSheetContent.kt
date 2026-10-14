package fr.descentecanyon.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.domain.model.CanyonSummary

@Composable
fun SelectedCanyonSheetContent(canyon: CanyonSummary, onOpen: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        CanyonSummaryCard(
            canyon = canyon,
            onClick = onOpen,
            variant = CanyonSummaryCardVariant.MapSheet,
        )
        Spacer(modifier = Modifier.height(12.dp))
    }
}
