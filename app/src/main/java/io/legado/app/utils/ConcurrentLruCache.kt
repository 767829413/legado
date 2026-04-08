package io.legado.app.utils

/**
 * 线程安全的 LRU 缓存，基于 LinkedHashMap(accessOrder=true)。
 *
 * @param maxSize  缓存上限，超出时自动淘汰最久未访问的条目
 * @param create   缓存未命中时的工厂函数
 */
class ConcurrentLruCache<K : Any, V : Any>(
    private val maxSize: Int,
    private val create: (K) -> V
) {

    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            return size > maxSize
        }
    }

    /** 获取缓存值，未命中时通过构造器的 create 工厂生成并缓存。 */
    fun get(key: K): V = synchronized(map) {
        map.getOrPut(key) { create(key) }
    }

    /** 仅查询，不触发工厂创建。 */
    fun getOrNull(key: K): V? = synchronized(map) {
        map[key]
    }

    fun put(key: K, value: V): Unit = synchronized(map) {
        map[key] = value
    }

    fun remove(key: K): V? = synchronized(map) {
        map.remove(key)
    }

    fun clear(): Unit = synchronized(map) {
        map.clear()
    }

    val size: Int get() = synchronized(map) { map.size }

}
