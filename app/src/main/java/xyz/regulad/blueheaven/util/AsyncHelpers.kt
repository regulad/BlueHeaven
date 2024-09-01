package xyz.regulad.blueheaven.util

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Barrier<T>(private val size: Int) {
    private val items = mutableListOf<T>()
    private val mutex = Mutex()

    suspend fun collect(value: T, block: suspend (List<T>) -> Unit) {
        mutex.withLock {
            items.add(value)
            if (items.size == size) {
                try {
                    block(items.toList())
                } finally {
                    items.clear()
                }
            }
        }
    }
}
