package org.multipaz.identityreader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Chips to choose what to request. When [ReaderQuery.CUSTOM] is selected, also shows a button
 * to choose its fields.
 */
@Composable
fun QueryChips(
    settingsModel: SettingsModel,
    onCustomQueryClicked: () -> Unit,
) {
    val selectedQueryName = settingsModel.selectedQueryName.collectAsState().value
    val customDataElements = settingsModel.customQueryDataElements.collectAsState().value

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(
                16.dp,
                alignment = Alignment.CenterHorizontally
            )
        ) {
            for (query in ReaderQuery.entries) {
                FilterChip(
                    selected = query.name == selectedQueryName,
                    onClick = { settingsModel.selectedQueryName.value = query.name },
                    label = { Text(text = query.displayName) },
                )
            }
        }
        if (selectedQueryName == ReaderQuery.CUSTOM.name) {
            TextButton(onClick = onCustomQueryClicked) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
                Text(
                    modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
                    text = "Choose fields (${customDataElements.size} selected)"
                )
            }
        }
    }
}
