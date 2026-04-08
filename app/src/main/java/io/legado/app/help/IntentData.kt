package io.legado.app.help

import io.legado.app.utils.ConcurrentLruCache

/**
 * Activity 间传递大对象的临时存储。
 * 使用 LRU 淘汰防止无限增长（get 后立即移除条目）。
 */
object IntentData {

    private val bigData = ConcurrentLruCache<String, Any>(50) {
        throw IllegalStateException("IntentData should not auto-create entries")
    }

    fun put(key: String, data: Any?): String {
        data?.let { bigData.put(key, it) }
        return key
    }

    fun put(data: Any?): String {
        val key = System.currentTimeMillis().toString()
        data?.let { bigData.put(key, it) }
        return key
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String?): T? {
        if (key == null) return null
        return bigData.remove(key) as? T
    }

}