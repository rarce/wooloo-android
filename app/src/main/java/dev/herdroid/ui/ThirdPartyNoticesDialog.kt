package dev.herdroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Each original notice is read separately, without laying out a megabyte of text at once. */
@Composable
internal fun ThirdPartyNoticesDialog(onOpen: (String, String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val entries = remember {
        context.assets.open("legal/third-party/index.json").bufferedReader().use { reader ->
            Json.parseToJsonElement(reader.readText()).jsonArray.map {
                it.jsonObject.getValue("title").jsonPrimitive.content to
                    it.jsonObject.getValue("path").jsonPrimitive.content
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Third-party notices") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                items(entries, key = { it.second }) { (title, path) ->
                    ListItem(
                        modifier = Modifier.clickable { onOpen(title, path) },
                        headlineContent = { Text(title) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
