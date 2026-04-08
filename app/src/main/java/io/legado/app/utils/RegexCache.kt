package io.legado.app.utils

import java.util.regex.Pattern

/**
 * 全局正则缓存，避免热路径上重复编译相同的 pattern 字符串。
 *
 * Regex 和 Pattern 编译成本高（词法分析 + NFA 构建），
 * 对同一 pattern 字符串应只编译一次并复用。
 * Regex/Pattern 自身是不可变且线程安全的。
 */
object RegexCache {

    private const val MAX_SIZE = 256

    private val regexCache = ConcurrentLruCache<String, Regex>(MAX_SIZE) { it.toRegex() }
    private val patternCache = ConcurrentLruCache<String, Pattern>(MAX_SIZE) { Pattern.compile(it) }

    fun regex(pattern: String): Regex = regexCache.get(pattern)

    fun pattern(pattern: String): Pattern = patternCache.get(pattern)

}
