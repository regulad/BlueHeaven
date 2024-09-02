package xyz.regulad.blueheaven.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.min

@Composable
fun MaxWidthContainer(
    maxWidthDp: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth()
    ) {
        val width = min(maxWidth, maxWidthDp)
        Box(
            modifier = Modifier.width(width)
        ) {
            content()
        }
    }
}
