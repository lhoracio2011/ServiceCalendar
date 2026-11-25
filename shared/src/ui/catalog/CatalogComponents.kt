package app.servicecalendar.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.servicecalendar.domain.DomainException
import app.servicecalendar.domain.toUserMessage
import app.servicecalendar.ui.theme.toComposeColor
import kotlinx.coroutines.CancellationException

@Composable
internal fun CatalogHeader(
    title: String,
    modifier: Modifier = Modifier,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navigation?.invoke()
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            actions()
        }
    }
}

@Composable
internal fun ColorSwatch(color: Long, size: Dp, modifier: Modifier = Modifier, selected: Boolean = false) {
    val shape = RoundedCornerShape(4.dp)
    val border = if (selected) {
        Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, shape)
    } else {
        Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape)
    }
    Box(modifier.size(size).background(color.toComposeColor(), shape).then(border))
}

internal inline fun <T> tryCatalog(onError: (Exception) -> Unit, block: () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError(e)
        null
    }

internal fun Exception.toCatalogMessage(): String =
    (this as? DomainException)?.toUserMessage() ?: CatalogStrings.UNEXPECTED_ERROR
