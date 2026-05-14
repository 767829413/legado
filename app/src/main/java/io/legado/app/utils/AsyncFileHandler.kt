package io.legado.app.utils

import java.util.concurrent.Executors
import java.util.logging.FileHandler
import java.util.logging.LogRecord

class AsyncFileHandler(pattern: String) : FileHandler(pattern) {

    override fun publish(record: LogRecord?) {
        if (!isLoggable(record)) {
            return
        }
        // 单独的 single-thread executor, 与 ReadBook 翻页热路径 (saveRead / recycleRecorders /
        // bitmap 回收) 使用的 globalExecutor 隔离: 日志文件长大后单条 publish 的磁盘 IO
        // 不会阻塞 saveRead/recycleRecorders 的排队, 避免长读后 "翻几页卡一下" 的渐进式卡顿。
        logExecutor.execute {
            super.publish(record)
        }
    }

    companion object {
        private val logExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "AsyncFileHandler").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
    }

}
