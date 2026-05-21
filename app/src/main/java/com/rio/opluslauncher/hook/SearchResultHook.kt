package com.rio.opluslauncher.hook

import android.content.ComponentName
import android.content.Intent
import android.view.View
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.factory.toClass
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.PackageParam

/**
 * 抽屉搜索结果总 Hook —— 功能 4 + 5 合在一起。
 *
 * 为什么合并:YukiHookAPI 对「同一个方法」只允许 hook 一次,后注册的会被
 * 「Already Hooked Member, this will be ignored」忽略。功能 4/5 都要改
 * onSearchResult,所以必须在同一个 hook 里处理。
 *
 * onSearchResult(query, results) 的处理(before):
 *  - query 为空  -> 功能 5:注入「最近启动过的 App」,并把 query 改成 " "
 *  - query 非空  -> 功能 4:对全部 App 模糊匹配 + 排序,截到 4 个(1 行)
 *
 * 一行结果由桌面默认行为摆在搜索框正上方,无需上移定位 —— 不走 after hook,
 * 没有延迟、没有动画错位。
 *
 * 反编译依据(System Launcher 16.4.15):
 *  - AppsSearchContainerLayout / LauncherTaskbarAppsSearchContainerLayout
 *      void onSearchResult(String, ArrayList<AdapterItem>)
 *  - 容器字段 mAppsView -> 抽屉 AllAppsContainerView
 *  - BaseAllAppsContainerView#getAlphabeticalAppsList()
 *  - AlphabeticalAppsList#getApps() : List<AppInfo>
 *  - ItemInfo#title (CharSequence 字段)、AppInfo#getIntent() / componentName
 *  - BaseAllAppsAdapter.AdapterItem#asApp(AppInfo)  static 工厂
 */
object SearchResultHook {

    private const val SEARCH_CONTAINER_BASE =
        "com.android.launcher3.allapps.search.AppsSearchContainerLayout"
    private const val SEARCH_CONTAINER_TASKBAR =
        "com.android.launcher3.allapps.search.LauncherTaskbarAppsSearchContainerLayout"
    private const val APP_INFO_CLASS = "com.android.launcher3.model.data.AppInfo"
    private const val ADAPTER_ITEM_CLASS =
        "com.android.launcher3.allapps.BaseAllAppsAdapter\$AdapterItem"

    /** 输入查询时的结果上限 = 1 行 × 4 列 */
    private const val MAX_RESULTS = 4

    /** 搜索历史(空查询)的显示上限 = 1 行 × 4 列。 */
    private const val HISTORY_MAX = 4

    fun hook(param: PackageParam) = with(param) {
        listOf(SEARCH_CONTAINER_BASE, SEARCH_CONTAINER_TASKBAR).forEach { className ->
            runCatching { hookOne(className) }
                .onFailure { YLog.error("SearchResultHook 挂载 $className 失败:${it.message}") }
        }
    }

    private fun PackageParam.hookOne(className: String) {
        className.toClass()
            .method {
                name = "onSearchResult"
                paramCount = 2 // (String, ArrayList)
            }
            .hook {
                before {
                    val container = instance ?: return@before
                    val rawQuery = (args.getOrNull(0) as? String).orEmpty()

                    if (rawQuery.trim().isEmpty()) {
                        // ===== 功能 5:空查询 -> 注入最近启动过的 App =====
                        val items = buildHistoryItems(container)
                        if (items.isNotEmpty()) {
                            args[0] = " " // 非空,桌面才会渲染
                            args[1] = items
                            SearchHistoryManager.markSearchActive()
                        }
                    } else {
                        // ===== 功能 4:非空查询 -> 模糊匹配 + 排序 + 截断 =====
                        SearchHistoryManager.markSearchActive()
                        val sorted = computeFuzzyResults(container, rawQuery.trim().lowercase())
                        if (sorted.isNotEmpty()) {
                            args[1] = sorted
                        }
                    }
                }
            }
        YLog.info("SearchResultHook 已挂载:$className.onSearchResult")
    }

    // ============================ 功能 4:模糊搜索 ============================

    /** 对全部 App 做模糊匹配 + 排序,返回 AdapterItem 列表(已按分数降序,截到 [MAX_RESULTS] 个)。 */
    private fun computeFuzzyResults(container: Any, query: String): ArrayList<Any> = runCatching {
        val appsView = readField(container, "mAppsView") ?: error("mAppsView 为 null")
        val appsList = invokeNoArg(appsView, "getAlphabeticalAppsList")
            ?: error("getAlphabeticalAppsList 为 null")
        val allApps = invokeNoArg(appsList, "getApps") as? List<*>
            ?: error("getApps 为 null")

        val scored = allApps.filterNotNull().mapNotNull { appInfo ->
            val title = (readField(appInfo, "title") as? CharSequence)
                ?.toString()?.lowercase() ?: return@mapNotNull null
            val score = scoreMatch(title, query) ?: return@mapNotNull null
            appInfo to score
        }.sortedByDescending { it.second }
            .take(MAX_RESULTS)

        if (scored.isEmpty()) return@runCatching ArrayList<Any>()
        scored.map { it.first }.toAdapterItems(container)
    }.onFailure { YLog.error("computeFuzzyResults 失败:${it.message}") }
        .getOrDefault(ArrayList())

    /**
     * 匹配打分。返回 null = 不匹配(过滤掉,不走拼音)。
     * 完全相等 > 前缀 > 子串 > 子序列;同档内名字越短分越高。
     */
    private fun scoreMatch(title: String, query: String): Int? {
        if (title.isEmpty()) return null
        return when {
            title == query -> 100_000 - title.length
            title.startsWith(query) -> 80_000 - title.length
            title.contains(query) -> 60_000 - title.length
            isSubsequence(title, query) -> 40_000 - title.length
            else -> null
        }
    }

    /** query 的字符是否按序出现在 title 中(允许中间夹别的字符)。 */
    private fun isSubsequence(title: String, query: String): Boolean {
        var ti = 0
        var qi = 0
        while (ti < title.length && qi < query.length) {
            if (title[ti] == query[qi]) qi++
            ti++
        }
        return qi == query.length
    }

    // ============================ 功能 5:搜索历史 ============================

    /** 把最近启动过的 App 转成 AdapterItem 列表(按最近顺序,最多 [MAX_RESULTS] 个)。 */
    private fun buildHistoryItems(container: Any): ArrayList<Any> = runCatching {
        val ctx = (container as? View)?.context ?: return@runCatching ArrayList<Any>()
        val recentPkgs = SearchHistoryManager.getRecent(ctx)
        if (recentPkgs.isEmpty()) return@runCatching ArrayList<Any>()

        val appsView = readField(container, "mAppsView") ?: error("mAppsView 为 null")
        val appsList = invokeNoArg(appsView, "getAlphabeticalAppsList")
            ?: error("getAlphabeticalAppsList 为 null")
        val allApps = invokeNoArg(appsList, "getApps") as? List<*>
            ?: error("getApps 为 null")

        val byPackage = HashMap<String, Any>()
        allApps.filterNotNull().forEach { appInfo ->
            val pkg = packageOf(appInfo)
            if (pkg.isNotEmpty()) byPackage[pkg] = appInfo
        }
        // 按最近顺序挑出存在的 App,只取一行
        val ordered = recentPkgs.mapNotNull { byPackage[it] }.take(HISTORY_MAX)
        ordered.toAdapterItems(container)
    }.onFailure { YLog.error("buildHistoryItems 失败:${it.message}") }
        .getOrDefault(ArrayList())

    /** 从 AppInfo 取包名:先试 getIntent(),再退回 componentName 字段。 */
    private fun packageOf(appInfo: Any): String {
        runCatching {
            val intent = invokeNoArg(appInfo, "getIntent") as? Intent
            val pkg = intent?.component?.packageName ?: intent?.`package`
            if (!pkg.isNullOrEmpty()) return pkg
        }
        runCatching {
            val cn = readField(appInfo, "componentName") as? ComponentName
            if (cn != null) return cn.packageName
        }
        return ""
    }

    /** List<AppInfo> -> ArrayList<AdapterItem>(经 AdapterItem.asApp 工厂)。 */
    private fun List<Any>.toAdapterItems(container: Any): ArrayList<Any> {
        val cl = container.javaClass.classLoader
        val appInfoClass = Class.forName(APP_INFO_CLASS, true, cl)
        val adapterItemClass = Class.forName(ADAPTER_ITEM_CLASS, true, cl)
        val asApp = adapterItemClass.getMethod("asApp", appInfoClass)
        val result = ArrayList<Any>(size)
        forEach { appInfo ->
            runCatching { asApp.invoke(null, appInfo) }.getOrNull()?.let { result.add(it) }
        }
        return result
    }

    // ============================ 公共反射helper ============================

    /** 读字段(沿父类链查找)。 */
    private fun readField(target: Any, name: String): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            runCatching {
                val f = cls!!.getDeclaredField(name)
                f.isAccessible = true
                return f.get(target)
            }
            cls = cls.superclass
        }
        return null
    }

    /** 反射调用无参方法(自动检索父类/接口的 public 方法)。 */
    private fun invokeNoArg(target: Any, name: String): Any? {
        val m = target.javaClass.methods.first {
            it.name == name && it.parameterTypes.isEmpty()
        }
        m.isAccessible = true
        return m.invoke(target)
    }
}
