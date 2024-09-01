package xyz.regulad.blueheaven.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.regulad.blueheaven.util.getRecentLogcatEntries

@Composable
fun LogcatViewer(rows: Int = 40, columns: Int = 150, lineHeight: Int = 12) {
    var logEntries by remember { mutableStateOf(List(rows) { "" }) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Calculate font size based on columns
    val fontSize = remember(columns) {
        maxOf(2f, minOf(8f, 500f / columns))
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            val newEntries = getRecentLogcatEntries(rows)
                .flatMap { it.wrapToLines(columns) }
                .takeLast(rows)
            logEntries = newEntries + List(rows - newEntries.size) { "" }
            coroutineScope.launch {
                listState.animateScrollToItem(logEntries.size - 1)
            }
            delay(300)
        }
    }

    Box(
        modifier = Modifier
            .background(Color.Black)
            .height((rows * lineHeight).dp)
            .padding(4.dp)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            items(logEntries) { entry ->
                Text(
                    text = entry,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize.sp,
                    lineHeight = lineHeight.sp,
                    modifier = Modifier.fillMaxWidth()
                        .padding(0.dp, 0.dp, 0.dp, 1.dp)
                )
            }
        }
    }
}

fun String.wrapToLines(maxLength: Int): List<String> {
    return if (length <= maxLength) {
        listOf(this)
    } else {
        listOf(substring(0, maxLength)) + substring(maxLength).wrapToLines(maxLength)
    }
}

