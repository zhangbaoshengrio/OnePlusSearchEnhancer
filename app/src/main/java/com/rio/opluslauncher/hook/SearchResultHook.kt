package com.rio.opluslauncher.hook

import android.content.ComponentName
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.factory.toClass
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.PackageParam

/**
 * 抽屉搜索结果总 Hook —— 功能 3 + 4 + 5 合在一起。
 *
 * 为什么合并:YukiHookAPI 对「同一个方法」只允许 hook 一次,后注册的会被
 * 「Already Hooked Member, this will be ignored」忽略。功能 3/4/5 都要改
 * onSearchResult,所以必须在同一个 hook 里处理。
 *
 * onSearchResult(query, results) 的处理:
 *  - before:
 *      query 为空  -> 功能 5:注入「最近启动过的 App」,并把 query 改成 " "
 *      query 非空  -> 功能 4:对全部 App 模糊匹配 + 排序,截到 8 个(2 行)
 *  - after:
 *      功能 3:把搜索结果 RecyclerView 上移到搜索栏上方,让 2 行都露出来
 *
 * 反编译依据(System Launcher 16.4.15):
 *  - AppsSearchContainerLayout / LauncherTaskbarAppsSearchContainerLayout
 *      void onSearchResult(String, ArrayList<AdapterItem>)
 *  - 容器字段 mAppsView -> 抽屉 AllAppsContainerView
 *  - BaseAllAppsContainerView#getAlphabeticalAppsList() / getActiveSearchRecyclerView()
 *      / getSearchUiManager()
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

    /** 输入查询时的结果上限 = 2 行 × 4 列 */
    private const val MAX_RESULTS = 8

    /**
     * 搜索历史(空查询)的显示上限 = 1 行 × 4 列。
     * 只显示一行:桌面默认就把一行结果摆在搜索框正上方,无需我们上移定位,
     * 因此不受键盘动画影响 —— 出现得又快、位置又准。
     */
    private const val HISTORY_MAX = 4

    /** 结果内容底边与搜索框之间的间距(像素) */
    private const val CONTENT_BOTTOM_MARGIN = 24

    /**
     * 定位前的延迟(毫秒)。只对「输入查询」的两行结果生效;此时键盘/抽屉早已
     * 稳定(用户已在搜索界面里打字),坐标即时可用,只留一帧余量。
     * (搜索历史是一行、不走上移定位,不受这个延迟影响。)
     */
    private const val POSITION_SETTLE_DELAY = 48L

    /**
     * 本次抽屉会话的 baseline translationY —— 由 SwipeDownToSearchHook 在抽屉
     * 打开瞬间(goToState 之后)记下桌面给 RV 设的初始值。
     *
     * 历史展示(空查询)时恢复到这个值。每次开抽屉都重新捕获,所以不会被上次
     * 会话搜过的查询留下的位移污染。
     */
    @Volatile
    var sessionBaselineTy: Float? = null

    /**
     * 当前待执行的定位 Runnable。每次新调用 moveSearchRvAboveBar 都会先 removeCallbacks
     * 把旧的取消掉,只保留最新一个 —— 这样:
     *  a) 多次 onSearchResult 不会累积多个定位;
     *  b) 空查询(历史)能把之前残留查询(如「te」)排进队列的定位取消掉,
     *     不会出现「搜过 te 之后再下拉,历史跑到两行上方」的位置错乱。
     */
    @Volatile
    private var pendingPositionRunnable: Runnable? = null

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

                after {
                    // ===== 功能 3:把结果 RecyclerView 上移到搜索栏上方 =====
                    val query = (args.getOrNull(0) as? String).orEmpty()
                    val container = instance as? View ?: return@after
                    moveSearchRvAboveBar(container, query)
                }
            }
        YLog.info("SearchResultHook 已挂载:$className.onSearchResult")
    }

    // ============================ 功能 4:模糊搜索 ============================

    /** 对全部 App 做模糊匹配 + 排序,返回 AdapterItem 列表(已按分数降序,截到 8 个)。 */
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

    // ====================== 功能 3:结果上移(两行可见)======================

    /** 把搜索 RecyclerView 移到搜索栏上方;真·空查询时还原。 */
    private fun moveSearchRvAboveBar(container: View, query: String) {
        runCatching {
            val appsView = readField(container, "mAppsView") as? View
                ?: error("找不到 mAppsView")
            val searchRv = invokeNoArg(appsView, "getActiveSearchRecyclerView") as? View
                ?: error("getActiveSearchRecyclerView 返回 null")
            val searchBar = invokeNoArg(appsView, "getSearchUiManager") as? View
                ?: error("getSearchUiManager 返回 null")

            // 空查询 + 历史(" ")都走这里:不做上移定位。
            // 历史只有一行,桌面默认就摆在搜索框正上方,无需上移 —— 这样不受
            // 键盘动画影响,出现又快又准。只有「输入查询」(非空非空白)才上移成两行。
            if (query.isBlank()) {
                // 取消任何还排队中的上移定位(避免上次残留查询的定位迟到一步把 RV 顶歪)
                pendingPositionRunnable?.let { searchRv.removeCallbacks(it) }
                pendingPositionRunnable = null
                // 恢复到本次抽屉会话开打开瞬间记下的 baseline —— 总是这次会话的真值,
                // 不会被上次会话搜过的查询留下的位移污染
                sessionBaselineTy?.let { searchRv.translationY = it }
                return@runCatching
            }
            // 排队定位 —— 先取消上一个未执行的(去重 / 也方便空查询时清场)。
            pendingPositionRunnable?.let { searchRv.removeCallbacks(it) }
            val r = Runnable { applyTranslation(searchRv, searchBar) }
            pendingPositionRunnable = r
            searchRv.postDelayed(r, POSITION_SETTLE_DELAY)
        }.onFailure { YLog.error("移动搜索结果 RV 失败:${it.message}") }
    }

    /**
     * 用屏幕坐标定位:让搜索结果**内容的底边**贴在搜索框上方。
     *
     * 关键:不用固定高度 —— 量出 RV 里所有子 View 的最低 bottom 作为「内容底边」,
     * 把它对齐到「搜索框顶 - [CONTENT_BOTTOM_MARGIN]」。这样不论 1 行还是 2 行,
     * 结果都贴着搜索框、向上生长,不会少量结果时飘在半空。
     * idempotent:重复调用收敛到同一目标。
     */
    private fun applyTranslation(searchRv: View, searchBar: View) {
        runCatching {
            disableClipping(searchRv, maxDepth = 3)
            val rv = searchRv as? ViewGroup ?: return@runCatching
            val barTop = IntArray(2).also { searchBar.getLocationOnScreen(it) }[1]
            if (barTop <= 0 || rv.childCount == 0) {
                // 搜索栏没量到位 / 结果还没布局 -> 稍后再试
                searchRv.postDelayed({
                    runCatching { applyTranslation(searchRv, searchBar) }
                }, 100L)
                return@runCatching
            }
            // 内容底边 = 最低的「图标格子」底边。
            // RV 的子 View 里:图标格子是窄的(width≈275),header/footer 是整宽
            // 的 LinearLayout(width≈1180);footer 还可能高达一行,会把对齐顶歪。
            // 所以只统计「宽度 < 半个 RV」的窄子 View。
            val halfWidth = rv.width / 2
            var contentBottom = 0
            for (i in 0 until rv.childCount) {
                val c = rv.getChildAt(i)
                if (c.width in 1 until halfWidth && c.bottom > contentBottom) {
                    contentBottom = c.bottom
                }
            }
            if (contentBottom == 0) {
                // 没有窄子 View(异常情况)-> 退回用所有子 View
                for (i in 0 until rv.childCount) {
                    val b = rv.getChildAt(i).bottom
                    if (b > contentBottom) contentBottom = b
                }
            }
            val rvTop = IntArray(2).also { searchRv.getLocationOnScreen(it) }[1]
            val contentBottomScreen = rvTop + contentBottom
            val desiredBottom = barTop - CONTENT_BOTTOM_MARGIN
            val shift = (desiredBottom - contentBottomScreen).toFloat()
            searchRv.translationY += shift
        }.onFailure { YLog.error("applyTranslation 失败:${it.message}") }
    }

    /** 沿父链关闭 clipChildren/clipToPadding,限制深度避免影响 window 根。 */
    private fun disableClipping(view: View, maxDepth: Int) {
        var cur: View? = view
        var depth = 0
        while (cur != null && depth < maxDepth) {
            (cur as? ViewGroup)?.let {
                it.clipChildren = false
                it.clipToPadding = false
            }
            cur = cur.parent as? View
            depth++
        }
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
