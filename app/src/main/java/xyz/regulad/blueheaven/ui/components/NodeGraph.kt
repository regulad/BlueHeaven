package xyz.regulad.blueheaven.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import xyz.regulad.blueheaven.network.NetworkConstants.toHex
import kotlin.math.cos
import kotlin.math.sin

const val INNER_RING_OFFSET = Math.PI / 2
const val OUTER_RING_OFFSET = Math.PI / 4

@Composable
fun NodeGraphVisualization(
    center: UInt,
    innerRing: Set<UInt>,
    outerRing: Set<UInt>,
    edges: Set<Pair<UInt, UInt>> = emptySet(),
    modifier: Modifier = Modifier
) {
    // Remove center node from rings and remove nodes from outerRing that are already in innerRing
    val actualInnerRing = innerRing - setOf(center)
    val actualOuterRing = (outerRing - innerRing) - setOf(center)

    // Convert sets to sorted lists for consistent ordering
    val innerRingList = actualInnerRing.sorted()
    val outerRingList = actualOuterRing.sorted()

    Canvas(modifier = modifier
        .fillMaxSize()
        .background(Color.LightGray)) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val innerRadius = size.width.coerceAtMost(size.height) * 0.2f
        val outerRadius = size.width.coerceAtMost(size.height) * 0.42f

        // Draw edges
        edges.forEach { (from, to) ->
            val fromPos = getNodePosition(from, center, innerRingList, outerRingList, centerX, centerY, innerRadius, outerRadius)
            val toPos = getNodePosition(to, center, innerRingList, outerRingList, centerX, centerY, innerRadius, outerRadius)
            drawLine(
                color = Color.Black,
                start = fromPos,
                end = toPos,
                strokeWidth = 10.dp.toPx()
            )
        }

        // Draw center node
        drawNode(center, Offset(centerX, centerY), Color.Red)

        // Draw inner ring
        drawRing(innerRingList, centerX, centerY, innerRadius, Color.Blue, INNER_RING_OFFSET)

        // Draw outer ring
        drawRing(outerRingList, centerX, centerY, outerRadius, Color.Green, OUTER_RING_OFFSET)
    }
}

private fun getNodePosition(
    node: UInt,
    center: UInt,
    innerRing: List<UInt>,
    outerRing: List<UInt>,
    centerX: Float,
    centerY: Float,
    innerRadius: Float,
    outerRadius: Float
): Offset {
    return when (node) {
        center -> Offset(centerX, centerY)
        in innerRing -> {
            // Calculate the angle for each node in the inner ring
            // Add PI/2 (90 degrees) to start from the top instead of the right
            val angle = 2 * Math.PI * innerRing.indexOf(node) / innerRing.size + INNER_RING_OFFSET

            // Calculate the x and y coordinates using polar to Cartesian conversion
            Offset(
                // x = centerX + radius * cos(angle)
                (centerX + innerRadius * cos(angle)).toFloat(),
                // y = centerY + radius * sin(angle)
                (centerY + innerRadius * sin(angle)).toFloat()
            )
        }
        in outerRing -> {
            // Calculate the angle for each node in the outer ring
            // Add PI/2 (90 degrees) to start from the top instead of the right
            val angle = 2 * Math.PI * outerRing.indexOf(node) / outerRing.size + OUTER_RING_OFFSET

            // Calculate the x and y coordinates using polar to Cartesian conversion
            Offset(
                // x = centerX + radius * cos(angle)
                (centerX + outerRadius * cos(angle)).toFloat(),
                // y = centerY + radius * sin(angle)
                (centerY + outerRadius * sin(angle)).toFloat()
            )
        }
        else -> Offset(centerX, centerY) // Fallback, shouldn't happen
    }
}

private fun DrawScope.drawRing(
    nodes: List<UInt>,
    centerX: Float,
    centerY: Float,
    radius: Float,
    color: Color,
    offset: Double,
) {
    nodes.forEachIndexed { index, node ->
        // Add PI/2 (90 degrees) to start from the top instead of the right
        val angle = 2 * Math.PI * index / nodes.size + offset
        val x = centerX + radius * cos(angle).toFloat()
        val y = centerY + radius * sin(angle).toFloat()
        drawNode(node, Offset(x, y), color)
    }
}

private fun DrawScope.drawNode(
    node: UInt,
    position: Offset,
    color: Color
) {
    val radius = 15.dp.toPx()
    val center = position

    val radiusX = radius * 2.5f
    val radiusY = radius

    val topLeft = Offset(center.x - radiusX, center.y - radiusY)
    val size = Size(radiusX * 2, radiusY * 2)

    drawOval(
        color = color,
        topLeft = topLeft,
        size = size
    )
    drawContext.canvas.nativeCanvas.drawText(
        node.toHex(),
        position.x,
        position.y + 5.dp.toPx(), // Adjust text position slightly
        Paint().apply {
            textSize = 12.dp.toPx()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setColor(Color.White.toArgb())
        }
    )
}
