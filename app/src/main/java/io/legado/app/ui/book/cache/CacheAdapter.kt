package io.legado.app.ui.book.cache

import android.content.Context
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemDownloadBinding
import io.legado.app.help.book.isLocal
import io.legado.app.model.CacheBook
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.visible

/**
 * UI 视角的下载状态; 由 CallBack.downloadState() 解释 model 的运行态.
 *  - Idle: 没有 model 或 model.isStop().
 *  - Preparing: model.isLoading() (拉目录中).
 *  - Running: 有 wait/onDownload 在跑, 或 waitingRetry 等下一轮重试.
 * Adapter 只面向这三个枚举值, 不再耦合 CacheBook 单例.
 */
enum class DownloadState { Idle, Preparing, Running }

class CacheAdapter(context: Context, private val callBack: CallBack) :
    DiffRecyclerAdapter<Book, ItemDownloadBinding>(context) {

    /**
     * bookUrl -> position 索引, 让按 bookUrl 刷新走 O(1) 而不是 O(N) 遍历.
     * 仅在 AsyncListDiffer 派发新列表后(主线程)重建.
     */
    private val bookUrlIndex = HashMap<String, Int>()

    override val diffItemCallback: DiffUtil.ItemCallback<Book>
        get() = object : DiffUtil.ItemCallback<Book>() {
            override fun areItemsTheSame(oldItem: Book, newItem: Book): Boolean {
                return oldItem.bookUrl == newItem.bookUrl
            }

            override fun areContentsTheSame(oldItem: Book, newItem: Book): Boolean {
                return oldItem.name == newItem.name
                        && oldItem.author == newItem.author
            }

        }

    override fun onCurrentListChanged() {
        bookUrlIndex.clear()
        getItems().forEachIndexed { index, book ->
            bookUrlIndex[book.bookUrl] = index
        }
    }

    /**
     * 按 bookUrl 局部刷新, 找不到对应 item 时静默返回 (列表已经移除该书).
     */
    fun notifyBookChanged(bookUrl: String, payload: Any = true) {
        val position = bookUrlIndex[bookUrl] ?: return
        notifyItemChanged(position, payload)
    }

    override fun getViewBinding(parent: ViewGroup): ItemDownloadBinding {
        return ItemDownloadBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemDownloadBinding,
        item: Book,
        payloads: MutableList<Any>
    ) {
        binding.run {
            if (payloads.isEmpty()) {
                tvName.text = item.name
                tvAuthor.text = context.getString(R.string.author_show, item.getRealAuthor())
            }
            // 一次 bind 一次状态读取: 同帧内 callBack.downloadState() 可能跨多个 @Synchronized,
            // 算一次再传给文案/图标两路, 保证两者基于同一快照, 顺便砍半 monitor entry.
            val state = if (item.isLocal) DownloadState.Idle else callBack.downloadState(item.bookUrl)
            bindDownloadStatus(item, state)
            upDownloadIv(ivDownload, item, state)
            upExportInfo(tvMsg, progressExport, item)
        }
    }

    /**
     * 下载状态 -> 文案 + 进度条 的单一来源.
     *
     * 状态机优先级 (高到低):
     *  1. 本地书: 仅文案, 进度条 invisible 占位.
     *  2. 章节文件尚未扫描完 (cacheChapters 中无 entry) 且无下载任务: "加载中…", 进度条 invisible.
     *  3. Preparing (model 在拉目录): "准备下载…", 进度条 indeterminate.
     *  4. Running (model 在跑/等重试): "下载中 X/Y", 进度条 determinate.
     *  5. Idle (静止): "已下载 X/Y", 进度条 invisible.
     *
     * 静止/本地/未扫描态都用 invisible 占位 (而非 gone), 避免下载开始/结束时 item 高度跳变.
     * 性能注: 进度条数值/visibility 都做了 "值未变就不写" 短路, 仅必要 invalidate.
     */
    private fun ItemDownloadBinding.bindDownloadStatus(item: Book, state: DownloadState) {
        if (item.isLocal) {
            tvDownload.setText(R.string.local_book)
            hideDownloadProgress()
            return
        }
        val cs = callBack.cacheChapters[item.bookUrl]
        val total = item.totalChapterNum
        val cacheSize = cs?.size ?: 0
        when {
            cs == null && state == DownloadState.Idle -> {
                tvDownload.setText(R.string.loading)
                hideDownloadProgress()
            }
            state == DownloadState.Preparing -> {
                tvDownload.setText(R.string.download_preparing)
                showDownloadProgress(indeterminate = true, total = total, current = cacheSize)
            }
            state == DownloadState.Running -> {
                tvDownload.text =
                    context.getString(R.string.download_progress, cacheSize, total)
                showDownloadProgress(indeterminate = false, total = total, current = cacheSize)
            }
            else -> {
                tvDownload.text =
                    context.getString(R.string.download_count, cacheSize, total)
                hideDownloadProgress()
            }
        }
    }

    /**
     * 进度条仅在状态切换或数值变化时更新; max/progress 与 indeterminate 状态有变化才写,
     * 减少不必要的 invalidate.
     */
    private fun ItemDownloadBinding.showDownloadProgress(
        indeterminate: Boolean,
        total: Int,
        current: Int
    ) {
        if (progressDownload.isIndeterminate != indeterminate) {
            progressDownload.isIndeterminate = indeterminate
        }
        if (!indeterminate) {
            val safeMax = if (total > 0) total else 1
            if (progressDownload.max != safeMax) progressDownload.max = safeMax
            val clamped = current.coerceIn(0, safeMax)
            if (progressDownload.progress != clamped) progressDownload.progress = clamped
        }
        if (progressDownload.visibility != android.view.View.VISIBLE) {
            progressDownload.visible()
        }
    }

    /**
     * 静止态: 用 invisible 而非 gone 保留 3dp 占位, 避免下载开始/结束时 item 高度跳变.
     * indeterminate 状态也一并复位, 防止下次进入 indeterminate=true 时残留动画.
     */
    private fun ItemDownloadBinding.hideDownloadProgress() {
        if (progressDownload.isIndeterminate) {
            progressDownload.isIndeterminate = false
        }
        if (progressDownload.visibility != android.view.View.INVISIBLE) {
            progressDownload.invisible()
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemDownloadBinding) {
        binding.run {
            ivDownload.setOnClickListener {
                getItem(holder.layoutPosition)?.let { book ->
                    CacheBook.cacheBookMap[book.bookUrl]?.let {
                        if (!it.isStop()) {
                            CacheBook.remove(context, book.bookUrl)
                        } else {
                            CacheBook.start(context, book, 0, book.lastChapterIndex)
                        }
                    } ?: let {
                        CacheBook.start(context, book, 0, book.lastChapterIndex)
                    }
                }
            }
            tvExport.setOnClickListener {
                callBack.export(holder.layoutPosition)
            }
        }
    }

    private fun upDownloadIv(iv: ImageView, book: Book, state: DownloadState) {
        if (book.isLocal) {
            iv.gone()
            return
        }
        iv.visible()
        val resId = if (state == DownloadState.Idle) {
            R.drawable.ic_play_24dp
        } else {
            R.drawable.ic_stop_black_24dp
        }
        iv.setImageResource(resId)
    }

    private fun upExportInfo(msgView: TextView, progressView: ProgressBar, book: Book) {
        val msg = callBack.exportMsg(book.bookUrl)
        if (msg != null) {
            msgView.text = msg
            msgView.visible()
            progressView.gone()
            return
        }
        msgView.gone()
        val progress = callBack.exportProgress(book.bookUrl)
        if (progress != null) {
            progressView.max = book.totalChapterNum
            progressView.progress = progress
            progressView.visible()
            return
        }
        progressView.gone()
    }

    interface CallBack {
        val cacheChapters: Map<String, MutableSet<String>>
        fun downloadState(bookUrl: String): DownloadState
        fun export(position: Int)
        fun exportProgress(bookUrl: String): Int?
        fun exportMsg(bookUrl: String): String?
    }
}