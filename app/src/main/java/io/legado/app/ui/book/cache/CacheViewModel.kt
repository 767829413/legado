package io.legado.app.ui.book.cache

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.utils.sendValue
import kotlinx.coroutines.ensureActive
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap


class CacheViewModel(application: Application) : BaseViewModel(application) {
    val upAdapterLiveData = MutableLiveData<String>()

    private var loadChapterCoroutine: Coroutine<Unit>? = null

    /**
     * 已缓存章节集合 (key=bookUrl, value=已缓存章节 url 集合).
     *
     * 写入方:
     *  - 后台 IO 协程 (loadCacheFiles) 在书目加载时整书替换 entry;
     *  - 主线程 (CacheActivity 收到 SAVE_CONTENT 事件) 向 entry 内 add 单条章节 url.
     * 读取方:
     *  - 主线程 RecyclerView 绑定 (CacheAdapter.convert) 读取 size.
     *
     * 因此使用 ConcurrentHashMap + 并发安全 Set, 避免 HashMap/HashSet 的多线程结构性问题.
     */
    val cacheChapters: MutableMap<String, MutableSet<String>> = ConcurrentHashMap()

    fun loadCacheFiles(books: List<Book>) {
        loadChapterCoroutine?.cancel()
        loadChapterCoroutine = execute {
            books.forEach { book ->
                if (!book.isLocal && !cacheChapters.containsKey(book.bookUrl)) {
                    val chapterCaches: MutableSet<String> =
                        Collections.newSetFromMap(ConcurrentHashMap())
                    val cacheNames = BookHelp.getChapterFiles(book)
                    if (cacheNames.isNotEmpty()) {
                        appDb.bookChapterDao.getChapterList(book.bookUrl).also {
                            book.totalChapterNum = it.size
                        }.forEach { chapter ->
                            if (cacheNames.contains(chapter.getFileName()) || chapter.isVolume) {
                                chapterCaches.add(chapter.url)
                            }
                        }
                    }
                    cacheChapters[book.bookUrl] = chapterCaches
                    upAdapterLiveData.sendValue(book.bookUrl)
                }
                ensureActive()
            }
        }
    }

}