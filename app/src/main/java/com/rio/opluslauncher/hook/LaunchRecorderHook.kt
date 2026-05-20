package com.rio.opluslauncher.hook

import android.content.Context
import android.content.Intent
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.factory.toClass
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.PackageParam

/**
 * 功能 5 的「记录」部分:Hook Launcher.startActivitySafely,
 * 把「搜索活跃时间窗内启动的 App」包名记进 SearchHistoryManager。
 *
 * 展示部分(空查询时把历史塞进结果)在 SearchResultHook 里 ——
 * 因为 onSearchResult 只能被 hook 一次,功能 3/4/5 的 onSearchResult 逻辑全合并了。
 *
 * 反编译依据:com.android.launcher.Launcher#startActivitySafely(View, Intent, ItemInfo)
 */
object LaunchRecorderHook {

    private const val LAUNCHER_CLASS = "com.android.launcher.Launcher"

    fun hook(param: PackageParam) = with(param) {
        runCatching {
            LAUNCHER_CLASS.toClass()
                .method {
                    name = "startActivitySafely"
                    paramCount = 3 // (View, Intent, ItemInfo)
                }
                .hook {
                    before {
                        // 只记录「最近有过搜索/历史展示」时间窗内的启动
                        if (!SearchHistoryManager.isWithinSearchWindow()) return@before
                        val intent = args.getOrNull(1) as? Intent ?: return@before
                        val pkg = intent.component?.packageName ?: intent.`package`
                        ?: return@before
                        val ctx = instance as? Context ?: return@before
                        SearchHistoryManager.record(ctx, pkg)
                        YLog.info("记录搜索启动:$pkg")
                    }
                }
            YLog.info("LaunchRecorderHook 已挂载:Launcher.startActivitySafely")
        }.onFailure {
            YLog.error("LaunchRecorderHook 挂载失败:${it.message}")
        }
    }
}
