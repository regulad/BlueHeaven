package xyz.regulad.blueheaven.util

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Barrier<T>(private val size: Int) {
    private val items = mutableListOf<T>()
    private val mutex = Mutex()

    suspend fun collect(value: T, block: suspend (List<T>) -> Unit) {
        val itemsNow: List<T>
        val canRun: Boolean

        mutex.withLock {
            items.add(value)
            itemsNow = items.toList()
            canRun = itemsNow.size >= size

            if (canRun) {
                items.clear()
            }
        }

        if (canRun) {
            block(itemsNow)
        }
    }
}
