package com.rio.opluslauncher.hook

import android.content.Context
import android.content.SharedPreferences

/**
 * 搜索历史存储 + 「搜索活跃」时间跟踪。
 *
 * 记录「从搜索里启动过的 App」包名,按最近使用排序。
 * 存储位置:桌面进程自己的 SharedPreferences(rio_search_history)。
 * 读写都发生在桌面进程内(Hook 代码就跑在桌面进程),不需要跨进程。
 */
object SearchHistoryManager {

    private const val PREFS_NAME = "rio_search_history"
    private const val KEY_RECENT = "recent_packages"
    private const val MAX_RECENT = 12

    /** 启动算「来自搜索」的时间窗(毫秒) */
    private const val SEARCH_WINDOW_MS = 30_000L

    /** 最近一次「搜索活跃」(用户输入 或 历史展示)的时间戳 */
    @Volatile
    private var lastSearchActiveTime = 0L

    /** 标记当前处于搜索活跃状态(输入框被使用 / 历史正在展示)。 */
    fun markSearchActive() {
        lastSearchActiveTime = System.currentTimeMillis()
    }

    /** 当前是否还在「来自搜索」的时间窗内。 */
    fun isWithinSearchWindow(): Boolean =
        System.currentTimeMillis() - lastSearchActiveTime <= SEARCH_WINDOW_MS

    /** 记录一次启动:把包名挪到最前,去重,限长。 */
    fun record(context: Context, packageName: String) {
        if (packageName.isEmpty()) return
        val sp = prefs(context)
        val updated = (listOf(packageName) + read(sp).filter { it != packageName })
            .take(MAX_RECENT)
        sp.edit().putString(KEY_RECENT, updated.joinToString(",")).apply()
    }

    /** 取最近启动过的包名,最近的在前。 */
    fun getRecent(context: Context): List<String> = read(prefs(context))

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun read(sp: SharedPreferences): List<String> {
        val raw = sp.getString(KEY_RECENT, "").orEmpty()
        return if (raw.isEmpty()) emptyList() else raw.split(",").filter { it.isNotEmpty() }
    }
}
