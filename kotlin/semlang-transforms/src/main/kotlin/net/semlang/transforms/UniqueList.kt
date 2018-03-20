package net.semlang.transforms

/**
 * This is a data structure that maintains the order of its elements like a [List], but allows only one of a given
 * value to be inserted like a [Set]. Note that its [hashCode] and [equals] are compatible neither with Lists nor with
 * Sets.
 *
 * Unlike [LinkedHashSet], this supports constant-time lookup of a given index within the collection via [get].
 */
class MutableUniqueList<T>: Collection<T> {
    val list = ArrayList<T>()
    val set = HashSet<T>()
    override val size: Int
        get() = list.size

    override fun contains(element: T): Boolean {
        return set.contains(element)
    }

    override fun containsAll(elements: Collection<T>): Boolean {
        return set.containsAll(elements)
    }

    override fun isEmpty(): Boolean {
        return list.isEmpty()
    }

    override fun iterator(): Iterator<T> {
        // Don't expose remove() from the list iterator, which would corrupt our state if called
        val delegate = list.iterator()
        return object: Iterator<T> {
            override fun next(): T {
                return delegate.next()
            }

            override fun hasNext(): Boolean {
                return delegate.hasNext()
            }
        }
    }

    fun add(element: T) {
        val wasAdded = set.add(element)
        if (wasAdded) {
            list.add(element)
        }
    }

    operator fun get(index: Int): T {
        return list.get(index)
    }
}
