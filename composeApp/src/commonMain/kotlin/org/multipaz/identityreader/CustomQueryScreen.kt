package org.multipaz.identityreader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import org.multipaz.documenttype.knowntypes.DrivingLicense

/**
 * Chooses the mDL data elements [ReaderQuery.CUSTOM] requests. Changes are saved as they're made.
 * At least one element always stays selected, so an empty request can't be sent.
 */
@Composable
fun CustomQueryScreen(
    settingsModel: SettingsModel,
    onBackPressed: () -> Unit,
) {
    val dataElements = remember {
        DrivingLicense.getDocumentType().mdocDocumentType!!
            .namespaces[DrivingLicense.MDL_NAMESPACE]!!.dataElements.values.toList()
    }
    val selected = settingsModel.customQueryDataElements.collectAsState().value

    // Keep the catalogue order, so requests and results always list fields the same way.
    fun setSelected(identifiers: Set<String>) {
        settingsModel.customQueryDataElements.value =
            dataElements.map { it.attribute.identifier }.filter { it in identifiers }
    }

    Scaffold(
        topBar = {
            AppBar(
                title = AnnotatedString("Custom request"),
                onBackPressed = onBackPressed,
            )
        },
    ) { innerPadding ->
        val scrollState = rememberScrollState()
        Surface(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                Text(
                    modifier = Modifier.padding(vertical = 16.dp),
                    text = "Choose the driving licence fields to request. The wallet only shares " +
                            "the fields you ask for, and the holder sees this list before agreeing.",
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        modifier = Modifier.weight(1.0f),
                        text = "${selected.size} of ${dataElements.size} selected",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(
                        onClick = { setSelected(IDENTIFICATION_MDL_DATA_ELEMENTS.toSet()) }
                    ) {
                        Text("Same as Identification")
                    }
                }

                EntryList(
                    title = null,
                    entries = dataElements.map { dataElement ->
                        val identifier = dataElement.attribute.identifier
                        val isSelected = identifier in selected
                        // Don't allow unselecting the last field.
                        val canToggle = !isSelected || selected.size > 1
                        @Composable {
                            Row(
                                modifier = Modifier.clickable(enabled = canToggle) {
                                    setSelected(
                                        if (isSelected) selected.toSet() - identifier
                                        else selected.toSet() + identifier
                                    )
                                },
                                horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Start),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                EntryItem(
                                    modifier = Modifier.weight(1.0f),
                                    key = dataElement.attribute.displayName,
                                    valueText = identifier
                                )
                                Checkbox(
                                    enabled = canToggle,
                                    checked = isSelected,
                                    onCheckedChange = { value ->
                                        setSelected(
                                            if (value) selected.toSet() + identifier
                                            else selected.toSet() - identifier
                                        )
                                    },
                                )
                            }
                        }
                    }
                )
            }
        }
    }
}
