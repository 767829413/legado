package io.legado.app.utils

import io.legado.app.lib.icu4j.CharsetDetector
import org.jsoup.Jsoup
import java.io.File

/**
 * 自动获取文件的编码
 * */
@Suppress("MemberVisibilityCanBePrivate", "unused")
object EncodingDetect {

    private val headOpenBytes = "<head".toByteArray()
    private val headCloseBytes = "</head>".toByteArray()

    /**
     * 仅扫描 HTML head 片段中的 meta charset 声明。
     * 关键点：在编码未知时，绝不把整个字节流按平台默认编码解码,
     * 否则会破坏 charset 元信息或抛出 NPE。
     * 字节级查找 head 区段后, 用 ISO_8859_1 1:1 映射成字符串供 Jsoup 解析,
     * meta 声明只会出现在 ASCII 范围, ISO_8859_1 不会改写任何 ASCII 字节。
     */
    fun getHtmlEncode(bytes: ByteArray): String {
        val head = extractHeadSegment(bytes) ?: return getEncode(bytes)
        try {
            val doc = Jsoup.parseBodyFragment(head)
            for (metaTag in doc.getElementsByTag("meta")) {
                metaTag.attr("charset").takeIf { it.isNotEmpty() }?.let { return it }
                if (metaTag.attr("http-equiv").equals("content-type", true)) {
                    val content = metaTag.attr("content")
                    val idx = content.indexOf("charset=", ignoreCase = true)
                    val charsetStr = if (idx > -1) {
                        content.substring(idx + "charset=".length)
                    } else {
                        content.substringAfter(";", "")
                    }.trim()
                    if (charsetStr.isNotEmpty()) return charsetStr
                }
            }
        } catch (_: Exception) {
        }
        return getEncode(bytes)
    }

    private fun extractHeadSegment(bytes: ByteArray): String? {
        val start = bytes.indexOf(headOpenBytes)
        if (start < 0) return null
        val end = bytes.indexOf(headCloseBytes, start)
        val to = if (end < 0) {
            // 容忍未闭合的 head, 取首段(<= 8KB)足以覆盖 meta 声明
            minOf(bytes.size, start + 8 * 1024)
        } else {
            end + headCloseBytes.size
        }
        return String(bytes, start, to - start, Charsets.ISO_8859_1)
    }

    fun getEncode(bytes: ByteArray): String {
        val match = charsetDetector.get()!!.setText(bytes).detect()
        return match?.name ?: "UTF-8"
    }

    // ICU CharsetDetector 内部带状态且非线程安全, 复用降低分配
    private val charsetDetector = ThreadLocal.withInitial { CharsetDetector() }

    /**
     * 得到文件的编码
     */
    fun getEncode(filePath: String): String {
        return getEncode(File(filePath))
    }

    /**
     * 得到文件的编码
     */
    fun getEncode(file: File): String {
        val tempByte = getFileBytes(file)
        if (tempByte.isEmpty()) {
            return "UTF-8"
        }
        return getEncode(tempByte)
    }

    private fun getFileBytes(file: File): ByteArray {
        val byteArray = ByteArray(8000)
        var pos = 0
        try {
            file.inputStream().buffered().use {
                while (pos < byteArray.size) {
                    val n = it.read(byteArray, pos, 1)
                    if (n == -1) {
                        break
                    }
                    if (byteArray[pos] < 0) {
                        pos++
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("Error: $e")
        }
        return byteArray.copyOf(pos)
    }
}