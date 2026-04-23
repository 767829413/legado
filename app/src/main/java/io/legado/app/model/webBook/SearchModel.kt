package io.legado.app.model.webBook

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.mapParallelSafe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import splitties.init.appCtx
import java.util.concurrent.Executors
import kotlin.math.min

class SearchModel(private val scope: CoroutineScope, private val callBack: CallBack) {
    val threadCount = AppConfig.threadCount
    private var searchPool: ExecutorCoroutineDispatcher? = null
    private var mSearchId = 0L
    private var searchPage = 1
    private var searchKey: String = ""
    private var bookSourceParts = emptyList<BookSourcePart>()

    /**
     * 已合并的搜索结果, key = 书名+作者, 用 LinkedHashMap 保持首次出现顺序,
     * 同一本书后续命中只追加 origin, 整个合并过程为 O(M)。
     * 旧 searchJob 取消后短暂窗口内仍可能并发写, 用 mergeMutex 兜底。
     */
    private val mergedBooks = LinkedHashMap<String, SearchBook>()
    private val mergeMutex = Mutex()
    private var searchJob: Job? = null
    private var workingState = MutableStateFlow(true)

    // 落库不能阻塞搜索线程池, 用独立 IO scope, fire-and-forget
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)


    private fun initSearchPool() {
        searchPool?.close()
        searchPool = Executors
            .newFixedThreadPool(min(threadCount, AppConst.MAX_THREAD)).asCoroutineDispatcher()
    }

    suspend fun search(searchId: Long, key: String) {
        if (searchId != mSearchId) {
            if (key.isEmpty()) {
                return
            }
            searchKey = key
            if (mSearchId != 0L) {
                close()
            }
            mergeMutex.withLock { mergedBooks.clear() }
            bookSourceParts = callBack.getSearchScope().getBookSourceParts()
            if (bookSourceParts.isEmpty()) {
                callBack.onSearchCancel(NoStackTraceException("启用书源为空"))
                return
            }
            mSearchId = searchId
            searchPage = 1
            initSearchPool()
        } else {
            // 加载更多: 上一页还在跑就直接忽略, 防止 searchJob 被覆盖、mergedBooks 被并发改写
            if (searchJob?.isActive == true) {
                return
            }
            searchPage++
        }
        startSearch()
    }

    private fun startSearch() {
        val precision = appCtx.getPrefBoolean(PreferKey.precisionSearch)
        var hasMore = false
        searchJob = scope.launch(searchPool!!) {
            flow {
                for (bs in bookSourceParts) {
                    bs.getBookSource()?.let {
                        emit(it)
                    }
                    workingState.first { it }
                }
            }.onStart {
                callBack.onSearchStart()
            }.mapParallelSafe(threadCount) {
                withTimeout(30000L) {
                    WebBook.searchBookAwait(
                        it, searchKey, searchPage,
                        filter = { name, author ->
                            !precision || name.contains(searchKey) ||
                                    author.contains(searchKey)
                        })
                }
            }.onEach { items ->
                for (book in items) {
                    book.releaseHtmlData()
                }
                hasMore = hasMore || items.isNotEmpty()
                if (items.isNotEmpty()) {
                    val snapshot = items.toTypedArray()
                    persistScope.launch {
                        runCatching { appDb.searchBookDao.insert(*snapshot) }
                            .onFailure { AppLog.put("搜索结果落库失败\n${it.localizedMessage}", it) }
                    }
                }
                currentCoroutineContext().ensureActive()
                val result = mergeMutex.withLock {
                    mergeItems(items, precision)
                    buildSortedResult(precision)
                }
                currentCoroutineContext().ensureActive()
                callBack.onSearchSuccess(result)
            }.onCompletion {
                if (it == null) {
                    val isEmpty = mergeMutex.withLock { mergedBooks.isEmpty() }
                    callBack.onSearchFinish(isEmpty, hasMore)
                }
            }.catch {
                AppLog.put("书源搜索出错\n${it.localizedMessage}", it)
            }.collect()
        }
    }

    /**
     * 合并新到达的搜索结果。
     * 同书(同名同作者)在结果列表里只保留一条, 多源用 origins 累加。
     * 复杂度: O(M), 不再随 mergedBooks 增长。
     */
    private fun mergeItems(newDataS: List<SearchBook>, precision: Boolean) {
        if (newDataS.isEmpty()) return
        for (nBook in newDataS) {
            if (precision && !matchesKey(nBook)) continue
            val key = bookKey(nBook)
            val existed = mergedBooks[key]
            if (existed == null) {
                mergedBooks[key] = nBook
            } else if (existed !== nBook) {
                existed.addOrigin(nBook.origin)
            }
        }
    }

    /**
     * 输出当前结果, 按"完全匹配 -> 包含匹配 -> 其他"分组,
     * 前两组按聚合源数量降序, 让多源命中的书优先展示。
     */
    private fun buildSortedResult(precision: Boolean): List<SearchBook> {
        if (mergedBooks.isEmpty()) return emptyList()
        val equalData = ArrayList<SearchBook>()
        val containsData = ArrayList<SearchBook>()
        val otherData = if (precision) null else ArrayList<SearchBook>()
        for (book in mergedBooks.values) {
            when {
                book.name == searchKey || book.author == searchKey -> equalData.add(book)
                book.name.contains(searchKey) || book.author.contains(searchKey) ->
                    containsData.add(book)
                otherData != null -> otherData.add(book)
            }
        }
        equalData.sortByDescending { it.origins.size }
        containsData.sortByDescending { it.origins.size }
        return ArrayList<SearchBook>(equalData.size + containsData.size + (otherData?.size ?: 0))
            .apply {
                addAll(equalData)
                addAll(containsData)
                otherData?.let(::addAll)
            }
    }

    private fun matchesKey(book: SearchBook): Boolean {
        return book.name == searchKey || book.author == searchKey ||
                book.name.contains(searchKey) || book.author.contains(searchKey)
    }

    private fun bookKey(book: SearchBook): String = book.name + "\u0000" + book.author

    fun pause() {
        workingState.value = false
    }

    fun resume() {
        workingState.value = true
    }

    fun cancelSearch() {
        close()
        callBack.onSearchCancel()
    }

    fun close() {
        searchJob?.cancel()
        searchPool?.close()
        searchPool = null
        mSearchId = 0L
    }

    interface CallBack {
        fun getSearchScope(): SearchScope
        fun onSearchStart()
        fun onSearchSuccess(searchBooks: List<SearchBook>)
        fun onSearchFinish(isEmpty: Boolean, hasMore: Boolean)
        fun onSearchCancel(exception: Throwable? = null)
    }

}