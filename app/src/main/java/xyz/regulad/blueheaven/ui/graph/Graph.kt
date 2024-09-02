package xyz.regulad.blueheaven.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import xyz.regulad.blueheaven.util.versionAgnosticPutIfAbsent
import kotlin.math.pow
import kotlin.math.sqrt

// Our minimal graph library
class Graph<T> {
    private val adjacencyList = mutableMapOf<T, MutableSet<T>>()

    fun addNode(node: T) {
        adjacencyList.versionAgnosticPutIfAbsent(node, mutableSetOf())
    }

    fun addEdge(node1: T, node2: T) {
        addNode(node1)
        addNode(node2)
        adjacencyList[node1]!!.add(node2)
        adjacencyList[node2]!!.add(node1)
    }

    fun nodes(): Set<T> = adjacencyList.keys

    fun neighbors(node: T): Set<T> = adjacencyList[node] ?: emptySet()

    fun degree(node: T): Int = neighbors(node).size

    fun numberOfNodes(): Int = adjacencyList.size

    fun numberOfEdges(): Int = adjacencyList.values.sumOf { it.size } / 2
}
