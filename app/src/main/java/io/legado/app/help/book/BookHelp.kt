package io.legado.app.help.book

import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.script.rhino.runScriptWithContext
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.ImageUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.StringUtils
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.UrlUtil
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.exists
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.onEachParallel
import io.legado.app.utils.postEvent
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.text.similarity.JaccardSimilarity
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Suppress("unused", "ConstPropertyName")
object BookHelp {
    private val downloadDir: File = appCtx.externalFiles
    private const val cacheFolderName = "book_cache"
    private const val cacheImageFolderName = "images"
    private const val cacheEpubFolderName = "epub"
    private val downloadImages = ConcurrentHashMap<String, Mutex>()

    val cachePath = FileUtils.getPath(downloadDir, cacheFolderName)

    fun clearCache() {
        FileUtils.delete(
            FileUtils.getPath(downloadDir, cacheFolderName)
        )
        // 整库缓存被清, 同步丢弃所有 per-src Mutex, 避免长期运行后驻留;
        // 只清整库时执行 —— 单本书的 clearCache(book) 不动它, 因为 src 是绝对 URL 可能被多本书共用.
        downloadImages.clear()
    }

    fun clearCache(book: Book) {
        val filePath = FileUtils.getPath(downloadDir, cacheFolderName, book.getFolderName())
        FileUtils.delete(filePath)
    }

    /**
     * 换源/章节列表替换时调用. 两件事:
     *  1. 若 bookUrl 变了 (folder name 由 bookUrl MD5 决定), 把旧目录整体 move 到新目录.
     *  2. 若提供了 newChapters, 顺手清掉新目录下不在 newChapters 里的孤儿章节文件.
     *
     * 不传 newChapters 时只 move 不清理 (e.g. BookInfoEditActivity 里手工编辑书信息但不换章节列表).
     */
    fun updateCacheFolder(
        oldBook: Book,
        newBook: Book,
        newChapters: List<BookChapter>? = null
    ) {
        val oldFolderName = oldBook.getFolderNameNoCache()
        val newFolderName = newBook.getFolderNameNoCache()
        if (oldFolderName != newFolderName) {
            val oldFolderPath = FileUtils.getPath(
                downloadDir,
                cacheFolderName,
                oldFolderName
            )
            val newFolderPath = FileUtils.getPath(
                downloadDir,
                cacheFolderName,
                newFolderName
            )
            FileUtils.move(oldFolderPath, newFolderPath)
        }
        if (newChapters != null) {
            pruneOrphanChapterFiles(newBook, newChapters)
        }
    }

    /**
     * 删掉书目录下不在 validChapters 里的章节正文文件 (.nb).
     * 文件名规则 `<index>-<MD5(title)>.nb` (见 BookChapter.getFileName), 换源/同源更新章节列表后,
     * 上一份章节文件名可能整体或部分对不上新列表, 不清就会永远残留, 长期累积浪费磁盘.
     *
     * 范围限制: 只清根目录下的 .nb 文本文件, 不动 images / font / epub 等子目录 ——
     * 它们与章节是多对多关系, 单凭文件名判断会误删. 后续如要彻底治理图片孤儿,
     * 需基于 "新章节内容里引用的 src 集合" 比对, 那是另一个工程.
     *
     * 故意用 getFolderNameNoCache(): Book.getFolderName() 会缓存结果, 若 runPreUpdateJs
     * 在本次调用链早期已修改过 bookUrl, 缓存里可能留着旧 URL 的 MD5, 扫到的就是已被 move
     * 走的旧目录, prune 什么都清不掉. updateCacheFolder 比较目录名时也是用 NoCache 版本,
     * 此处保持一致.
     *
     * 性能: validChapters 一般几千级, HashSet 占用百 KB; listFiles 一次磁盘 IO,
     * 调用方都在 IO 协程上下文中, 无主线程影响.
     */
    private fun pruneOrphanChapterFiles(book: Book, validChapters: List<BookChapter>) {
        val folder = downloadDir.getFile(cacheFolderName, book.getFolderNameNoCache())
        if (!folder.exists() || !folder.isDirectory) return
        val validNames = HashSet<String>(validChapters.size)
        validChapters.forEach { validNames.add(it.getFileName()) }
        folder.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".nb") && !validNames.contains(file.name)) {
                // File.delete() 失败静默返回 false (权限/句柄占用); 不 log 的话下次扫仍会命中,
                // 但用户看不到原因. 丢到 AppLog 便于定位, 不抛异常避免打断换源流程.
                if (!file.delete()) {
                    AppLog.put("pruneOrphanChapterFiles: 删除失败 ${file.name}")
                }
            }
        }
    }

    /**
     * 清除已删除书的缓存 解压缓存
     */
    suspend fun clearInvalidCache() {
        withContext(IO) {
            val bookFolderNames = hashSetOf<String>()
            val originNames = hashSetOf<String>()
            appDb.bookDao.all.forEach {
                clearComicCache(it)
                bookFolderNames.add(it.getFolderName())
                if (it.isEpub) originNames.add(it.originName)
            }
            downloadDir.getFile(cacheFolderName)
                .listFiles()?.forEach { bookFile ->
                    if (!bookFolderNames.contains(bookFile.name)) {
                        FileUtils.delete(bookFile.absolutePath)
                    }
                }
            downloadDir.getFile(cacheEpubFolderName)
                .listFiles()?.forEach { epubFile ->
                    if (!originNames.contains(epubFile.name)) {
                        FileUtils.delete(epubFile.absolutePath)
                    }
                }
            FileUtils.delete(ArchiveUtils.TEMP_PATH)
            val filesDir = appCtx.filesDir
            FileUtils.delete("$filesDir/shareBookSource.json")
            FileUtils.delete("$filesDir/shareRssSource.json")
            FileUtils.delete("$filesDir/books.json")
        }
    }

    //清除已经看过的漫画数据
    private fun clearComicCache(book: Book) {
        //只处理漫画
        //为0的时候，不清除已缓存数据
        if (!book.isImage || AppConfig.imageRetainNum == 0) {
            return
        }
        //向前保留设定数量，向后保留预下载数量
        val startIndex = book.durChapterIndex - AppConfig.imageRetainNum
        val endIndex = book.durChapterIndex + AppConfig.preDownloadNum
        val chapterList = appDb.bookChapterDao.getChapterList(book.bookUrl, startIndex, endIndex)
        val imgNames = hashSetOf<String>()
        //获取需要保留章节的图片信息
        chapterList.forEach {
            val content = getContent(book, it)
            if (content != null) {
                val matcher = AppPattern.imgPattern.matcher(content)
                while (matcher.find()) {
                    val src = matcher.group(1) ?: continue
                    val mSrc = NetworkUtils.getAbsoluteURL(it.url, src)
                    imgNames.add("${MD5Utils.md5Encode16(mSrc)}.${getImageSuffix(mSrc)}")
                }
            }
        }
        downloadDir.getFile(
            cacheFolderName,
            book.getFolderName(),
            cacheImageFolderName
        ).listFiles()?.forEach { imgFile ->
            if (!imgNames.contains(imgFile.name)) {
                imgFile.delete()
            }
        }
    }

    suspend fun saveContent(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        content: String
    ) {
        try {
            saveText(book, bookChapter, content)
            //saveImages(bookSource, book, bookChapter, content)
            postEvent(EventBus.SAVE_CONTENT, Pair(book, bookChapter))
        } catch (e: Exception) {
            e.printStackTrace()
            AppLog.put("保存正文失败 ${book.name} ${bookChapter.title}", e)
        }
    }

    fun saveText(
        book: Book,
        bookChapter: BookChapter,
        content: String
    ) {
        if (content.isEmpty()) return
        //保存文本
        FileUtils.createFileIfNotExist(
            downloadDir,
            cacheFolderName,
            book.getFolderName(),
            bookChapter.getFileName(),
        ).writeText(content)
        if (book.isOnLineTxt && AppConfig.tocCountWords) {
            val wordCount = StringUtils.wordCountFormat(content.length)
            bookChapter.wordCount = wordCount
            appDb.bookChapterDao.upWordCount(bookChapter.bookUrl, bookChapter.url, wordCount)
        }
    }

    fun flowImages(bookChapter: BookChapter, content: String): Flow<String> {
        return flow {
            val matcher = AppPattern.imgPattern.matcher(content)
            while (matcher.find()) {
                val src = matcher.group(1) ?: continue
                val mSrc = NetworkUtils.getAbsoluteURL(bookChapter.url, src)
                emit(mSrc)
            }
        }
    }

    suspend fun saveImages(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        content: String,
        concurrency: Int = AppConfig.threadCount
    ) = coroutineScope {
        flowImages(bookChapter, content).onEachParallel(concurrency) { mSrc ->
            saveImage(bookSource, book, mSrc, bookChapter)
        }.collect()
    }

    suspend fun saveImage(
        bookSource: BookSource?,
        book: Book,
        src: String,
        chapter: BookChapter? = null
    ) {
        if (isImageExist(book, src)) {
            return
        }
        // 用 ConcurrentHashMap.computeIfAbsent 原子获取 per-src Mutex,
        // 避免原本 "synchronized + getOrPut + finally remove" 路径下的竞态:
        // 持锁线程在 finally 中先 remove 再 unlock, 新到达的线程会创建新的 mutex 与等待者并发下载.
        val mutex = downloadImages.computeIfAbsent(src) { Mutex() }
        var success = false
        mutex.withLock {
            if (isImageExist(book, src)) {
                success = true
                return@withLock
            }
            try {
                val analyzeUrl = AnalyzeUrl(
                    src, source = bookSource, coroutineContext = coroutineContext
                )
                val bytes = analyzeUrl.getByteArrayAwait()
                //某些图片被加密，需要进一步解密
                runScriptWithContext {
                    ImageUtils.decode(
                        src, bytes, isCover = false, bookSource, book
                    )
                }?.let {
                    if (!checkImage(it)) {
                        // 如果部分图片失效，每次进入正文都会花很长时间再次获取图片数据
                        // 所以无论如何都要将数据写入到文件里
                        // throw NoStackTraceException("数据异常")
                        AppLog.put("${book.name} ${chapter?.title} 图片 $src 下载错误 数据异常")
                    }
                    writeImage(book, src, it)
                    success = true
                }
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                val msg = "${book.name} ${chapter?.title} 图片 $src 下载失败\n${e.localizedMessage}"
                AppLog.put(msg, e)
            }
        }
        // 仅在确认成功时丢弃 Mutex: 后续 saveImage(src) 会被入口的 isImageExist() 早返, 不再用到它.
        // 失败/解码返回 null 时保留 Mutex, 让重试继续走串行, 避免并发重复请求同一张坏图.
        // remove(key, value) 的 CAS 语义保证不会误删别的线程刚 putIfAbsent 的新 Mutex.
        if (success) {
            downloadImages.remove(src, mutex)
        }
    }

    fun getImage(book: Book, src: String): File {
        return downloadDir.getFile(
            cacheFolderName,
            book.getFolderName(),
            cacheImageFolderName,
            "${MD5Utils.md5Encode16(src)}.${getImageSuffix(src)}"
        )
    }

    @Synchronized
    fun writeImage(book: Book, src: String, bytes: ByteArray) {
        getImage(book, src).createFileIfNotExist().writeBytes(bytes)
    }

    @Synchronized
    fun isImageExist(book: Book, src: String): Boolean {
        return getImage(book, src).exists()
    }

    fun getImageSuffix(src: String): String {
        return UrlUtil.getSuffix(src, "jpg")
    }

    @Throws(IOException::class, FileNotFoundException::class)
    fun getEpubFile(book: Book): ZipFile {
        val uri = book.getLocalUri()
        if (uri.isContentScheme()) {
            FileUtils.createFolderIfNotExist(downloadDir, cacheEpubFolderName)
            val path = FileUtils.getPath(downloadDir, cacheEpubFolderName, book.originName)
            val file = File(path)
            val doc = DocumentFile.fromSingleUri(appCtx, uri)
                ?: throw IOException("文件不存在")
            if (!file.exists() || doc.lastModified() > book.latestChapterTime) {
                LocalBook.getBookInputStream(book).use { inputStream ->
                    FileOutputStream(file).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }
            return ZipFile(file)
        }
        return ZipFile(uri.path)
    }

    /**
     * 获取本地书籍文件的ParcelFileDescriptor
     *
     * @param book
     * @return
     */
    @Throws(IOException::class, FileNotFoundException::class)
    fun getBookPFD(book: Book): ParcelFileDescriptor? {
        val uri = book.getLocalUri()
        return if (uri.isContentScheme()) {
            appCtx.contentResolver.openFileDescriptor(uri, "r")
        } else {
            ParcelFileDescriptor.open(File(uri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    fun getChapterFiles(book: Book): HashSet<String> {
        val fileNames = hashSetOf<String>()
        if (book.isLocalTxt) {
            return fileNames
        }
        FileUtils.createFolderIfNotExist(
            downloadDir,
            subDirs = arrayOf(cacheFolderName, book.getFolderName())
        ).list()?.let {
            fileNames.addAll(it)
        }
        return fileNames
    }

    /**
     * 检测该章节是否下载
     */
    fun hasContent(book: Book, bookChapter: BookChapter): Boolean {
        return if (book.isLocalTxt ||
            (bookChapter.isVolume && bookChapter.url.startsWith(bookChapter.title))
        ) {
            true
        } else {
            downloadDir.exists(
                cacheFolderName,
                book.getFolderName(),
                bookChapter.getFileName()
            )
        }
    }

    /**
     * 检测图片是否下载
     */
    fun hasImageContent(book: Book, bookChapter: BookChapter): Boolean {
        if (!hasContent(book, bookChapter)) {
            return false
        }
        var ret = true
        val op = BitmapFactory.Options()
        op.inJustDecodeBounds = true
        getContent(book, bookChapter)?.let {
            val matcher = AppPattern.imgPattern.matcher(it)
            while (matcher.find()) {
                val src = matcher.group(1)!!
                val image = getImage(book, src)
                if (!image.exists()) {
                    ret = false
                    continue
                }
                BitmapFactory.decodeFile(image.absolutePath, op)
                if (op.outWidth < 1 && op.outHeight < 1) {
                    if (SvgUtils.getSize(image.absolutePath) != null) {
                        continue
                    }
                    ret = false
                    image.delete()
                }
            }
        }
        return ret
    }

    private fun checkImage(bytes: ByteArray): Boolean {
        val op = BitmapFactory.Options()
        op.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, op)
        if (op.outWidth < 1 && op.outHeight < 1) {
            return SvgUtils.getSize(ByteArrayInputStream(bytes)) != null
        }
        return true
    }

    /**
     * 读取章节内容
     */
    fun getContent(book: Book, bookChapter: BookChapter): String? {
        val file = downloadDir.getFile(
            cacheFolderName,
            book.getFolderName(),
            bookChapter.getFileName()
        )
        if (file.exists()) {
            val string = file.readText()
            if (string.isEmpty()) {
                return null
            }
            return string
        }
        if (book.isLocal) {
            val string = LocalBook.getContent(book, bookChapter)
            if (string != null && book.isEpub) {
                saveText(book, bookChapter, string)
            }
            return string
        }
        return null
    }

    /**
     * 删除章节内容
     */
    fun delContent(book: Book, bookChapter: BookChapter) {
        FileUtils.createFileIfNotExist(
            downloadDir,
            cacheFolderName,
            book.getFolderName(),
            bookChapter.getFileName()
        ).delete()
    }

    /**
     * 设置是否禁用正文的去除重复标题,针对单个章节
     */
    fun setRemoveSameTitle(book: Book, bookChapter: BookChapter, removeSameTitle: Boolean) {
        val fileName = bookChapter.getFileName("nr")
        val contentProcessor = ContentProcessor.get(book)
        if (removeSameTitle) {
            val path = FileUtils.getPath(
                downloadDir,
                cacheFolderName,
                book.getFolderName(),
                fileName
            )
            contentProcessor.removeSameTitleCache.remove(fileName)
            File(path).delete()
        } else {
            FileUtils.createFileIfNotExist(
                downloadDir,
                cacheFolderName,
                book.getFolderName(),
                fileName
            )
            contentProcessor.removeSameTitleCache.add(fileName)
        }
    }

    /**
     * 获取是否去除重复标题
     */
    fun removeSameTitle(book: Book, bookChapter: BookChapter): Boolean {
        val path = FileUtils.getPath(
            downloadDir,
            cacheFolderName,
            book.getFolderName(),
            bookChapter.getFileName("nr")
        )
        return !File(path).exists()
    }

    /**
     * 格式化书名
     */
    fun formatBookName(name: String): String {
        return name
            .replace(AppPattern.nameRegex, "")
            .trim { it <= ' ' }
    }

    /**
     * 格式化作者
     */
    fun formatBookAuthor(author: String): String {
        return author
            .replace(AppPattern.authorRegex, "")
            .trim { it <= ' ' }
    }

    private val jaccardSimilarity by lazy {
        JaccardSimilarity()
    }

    /**
     * 根据目录名获取当前章节
     */
    fun getDurChapter(
        oldDurChapterIndex: Int,
        oldDurChapterName: String?,
        newChapterList: List<BookChapter>,
        oldChapterListSize: Int = 0
    ): Int {
        if (oldDurChapterIndex <= 0) return 0
        if (newChapterList.isEmpty()) return oldDurChapterIndex
        val oldChapterNum = getChapterNum(oldDurChapterName)
        val oldName = getPureChapterName(oldDurChapterName)
        val newChapterSize = newChapterList.size
        val durIndex =
            if (oldChapterListSize == 0) oldDurChapterIndex
            else oldDurChapterIndex * oldChapterListSize / newChapterSize
        val min = max(0, min(oldDurChapterIndex, durIndex) - 10)
        val max = min(newChapterSize - 1, max(oldDurChapterIndex, durIndex) + 10)
        var nameSim = 0.0
        var newIndex = 0
        var newNum = 0
        if (oldName.isNotEmpty()) {
            for (i in min..max) {
                val newName = getPureChapterName(newChapterList[i].title)
                val temp = jaccardSimilarity.apply(oldName, newName)
                if (temp > nameSim) {
                    nameSim = temp
                    newIndex = i
                }
            }
        }
        if (nameSim < 0.96 && oldChapterNum > 0) {
            for (i in min..max) {
                val temp = getChapterNum(newChapterList[i].title)
                if (temp == oldChapterNum) {
                    newNum = temp
                    newIndex = i
                    break
                } else if (abs(temp - oldChapterNum) < abs(newNum - oldChapterNum)) {
                    newNum = temp
                    newIndex = i
                }
            }
        }
        return if (nameSim > 0.96 || abs(newNum - oldChapterNum) < 1) {
            newIndex
        } else {
            min(max(0, newChapterList.size - 1), oldDurChapterIndex)
        }
    }

    fun getDurChapter(
        oldBook: Book,
        newChapterList: List<BookChapter>
    ): Int {
        return oldBook.run {
            getDurChapter(durChapterIndex, durChapterTitle, newChapterList, totalChapterNum)
        }
    }

    private val chapterNamePattern1 by lazy {
        Pattern.compile(
            ".*?第([\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)[章节篇回集话]"
        )
    }

    @Suppress("RegExpSimplifiable")
    private val chapterNamePattern2 by lazy {
        Pattern.compile(
            "^(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+[,:、])*([\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)(?:[,:、]|\\.[^\\d])"
        )
    }

    private val regexA by lazy {
        return@lazy "\\s".toRegex()
    }

    private fun getChapterNum(chapterName: String?): Int {
        chapterName ?: return -1
        val chapterName1 = StringUtils.fullToHalf(chapterName).replace(regexA, "")
        return StringUtils.stringToInt(
            (
                    chapterNamePattern1.matcher(chapterName1).takeIf { it.find() }
                        ?: chapterNamePattern2.matcher(chapterName1).takeIf { it.find() }
                    )?.group(1)
                ?: "-1"
        )
    }

    private val regexOther by lazy {
        // 所有非字母数字中日韩文字 CJK区+扩展A-F区
        @Suppress("RegExpDuplicateCharacterInClass")
        return@lazy "[^\\w\\u4E00-\\u9FEF〇\\u3400-\\u4DBF\\u20000-\\u2A6DF\\u2A700-\\u2EBEF]".toRegex()
    }

    @Suppress("RegExpUnnecessaryNonCapturingGroup", "RegExpSimplifiable")
    private val regexB by lazy {
        //章节序号，排除处于结尾的状况，避免将章节名替换为空字串
        return@lazy "^.*?第(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)[章节篇回集话](?!$)|^(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+[,:、])*(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)(?:[,:、](?!$)|\\.(?=[^\\d]))".toRegex()
    }

    private val regexC by lazy {
        //前后附加内容，整个章节名都在括号中时只剔除首尾括号，避免将章节名替换为空字串
        return@lazy "(?!^)(?:[〖【《〔\\[{(][^〖【《〔\\[{()〕》】〗\\]}]+)?[)〕》】〗\\]}]$|^[〖【《〔\\[{(](?:[^〖【《〔\\[{()〕》】〗\\]}]+[〕》】〗\\]})])?(?!$)".toRegex()
    }

    private fun getPureChapterName(chapterName: String?): String {
        return if (chapterName == null) "" else StringUtils.fullToHalf(chapterName)
            .replace(regexA, "")
            .replace(regexB, "")
            .replace(regexC, "")
            .replace(regexOther, "")
    }

}
