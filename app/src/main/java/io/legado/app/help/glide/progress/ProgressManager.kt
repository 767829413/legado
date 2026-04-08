package io.legado.app.help.glide.progress

import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.ConcurrentLruCache

/**
 * 图片加载进度监听管理。
 * 使用 LRU 淘汰防止已取消/失败的加载残留 listener。
 */
object ProgressManager {

    private val listenersMap = ConcurrentLruCache<String, OnProgressListener>(200) {
        throw IllegalStateException("ProgressManager should not auto-create listeners")
    }

    val LISTENER = object : ProgressResponseBody.InternalProgressListener {
        override fun onProgress(
            url: String,
            bytesRead: Long,
            totalBytes: Long,
            isComplete: Boolean
        ) {
            val cleanUrl = getUrlNoOption(url)
            listenersMap.getOrNull(cleanUrl)?.let {
                val percentage = when {
                    isComplete -> 100
                    totalBytes == -1L -> 0
                    else -> (bytesRead * 1f / totalBytes * 100f).toInt().coerceIn(0, 100)
                }
                it.invoke(isComplete, percentage, bytesRead, totalBytes)
                if (isComplete) {
                    listenersMap.remove(cleanUrl)
                }
            }
        }
    }

    fun addListener(url: String, listener: OnProgressListener) {
        if (url.isNotEmpty()) {
            val cleanUrl = getUrlNoOption(url)
            listenersMap.put(cleanUrl, listener)
            listener.invoke(false, 1, 0, 0)
        }
    }

    fun removeListener(url: String) {
        if (url.isNotEmpty()) {
            listenersMap.remove(getUrlNoOption(url))
        }
    }

    private fun getUrlNoOption(url: String): String {
        val urlMatcher = AnalyzeUrl.paramPattern.matcher(url)
        return if (urlMatcher.find()) {
            url.take(urlMatcher.start())
        } else {
            url
        }
    }

}
