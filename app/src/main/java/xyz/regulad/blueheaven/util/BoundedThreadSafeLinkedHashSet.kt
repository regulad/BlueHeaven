package xyz.regulad.blueheaven.util

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class BoundedThreadSafeLinkedHashSet<E>(private val maxSize: Int?) : MutableSet<E> {
    private val internalSet: LinkedHashSet<E> = LinkedHashSet()
    private val lock = ReentrantReadWriteLock()

    override val size: Int
        get() = lock.read { internalSet.size }

    override fun add(element: E): Boolean = lock.write {
        if (internalSet.contains(element)) {
            false
        } else {
            if (maxSize != null) {
                if (internalSet.size >= maxSize) {
                    internalSet.iterator().let {
                        it.next()
                        it.remove()
                    }
                }
            }
            internalSet.add(element)
        }
    }

    override fun addAll(elements: Collection<E>): Boolean = lock.write {
        var changed = false
        for (element in elements) {
            if (add(element)) {
                changed = true
            }
        }
        changed
    }

    override fun clear() = lock.write {
        internalSet.clear()
    }

    override fun iterator(): MutableIterator<E> = lock.read {
        ThreadSafeIterator(internalSet.iterator())
    }

    override fun remove(element: E): Boolean = lock.write {
        internalSet.remove(element)
    }

    override fun removeAll(elements: Collection<E>): Boolean = lock.write {
        internalSet.removeAll(elements.toSet())
    }

    override fun retainAll(elements: Collection<E>): Boolean = lock.write {
        internalSet.retainAll(elements.toSet())
    }

    override fun contains(element: E): Boolean = lock.read {
        internalSet.contains(element)
    }

    override fun containsAll(elements: Collection<E>): Boolean = lock.read {
        internalSet.containsAll(elements)
    }

    override fun isEmpty(): Boolean = lock.read {
        internalSet.isEmpty()
    }

    private inner class ThreadSafeIterator(private val delegate: MutableIterator<E>) : MutableIterator<E> {
        override fun hasNext(): Boolean = lock.read { delegate.hasNext() }
        override fun next(): E = lock.read { delegate.next() }
        override fun remove() = lock.write { delegate.remove() }
    }
}
