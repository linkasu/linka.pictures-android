package su.linka.pictures.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import su.linka.pictures.shared.generated.resources.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PagingControls(pagination: BoardPagination, enabled: Boolean, onPage: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = { onPage(pagination.page - 1) },
            enabled = enabled && pagination.page > 0,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        ) { Text(stringResource(Res.string.previous_page)) }
        Box(Modifier.sizeIn(minHeight = 48.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.page_number, pagination.page + 1, pagination.pageCount))
        }
        TextButton(
            onClick = { onPage(pagination.page + 1) },
            enabled = enabled && pagination.page + 1 < pagination.pageCount,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        ) { Text(stringResource(Res.string.next_page)) }
    }
}
