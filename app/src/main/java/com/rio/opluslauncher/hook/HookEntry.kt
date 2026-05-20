package com.rio.opluslauncher.hook

import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import com.rio.opluslauncher.BuildConfig

/**
 * 模块的 Xposed 入口。
 *
 * @InjectYukiHookWithXposed 会让 KSP 在编译期自动生成 xposed_init 等样板代码,
 * 不需要手写 assets/xposed_init。
 */
@InjectYukiHookWithXposed
object HookEntry : IYukiHookXposedInit {

    /** 目标桌面包名 —— 模块作用域也只勾这一个 */
    private const val LAUNCHER_PACKAGE = "com.android.launcher"

    override fun onInit() = YukiHookAPI.configs {
        debugLog {
            tag = "OPlusLauncherMod"
            isEnable = BuildConfig.DEBUG
        }
        isDebug = BuildConfig.DEBUG
    }

    override fun onHook() = YukiHookAPI.encase {
        loadApp(name = LAUNCHER_PACKAGE) {
            // 第一步:确认模块确实被注入了桌面进程。
            // 装好后用  adb logcat | grep OPlusLauncherMod  能看到这行日志。
            onAppLifecycle {
                onCreate {
                    YLog.info("模块已注入桌面进程:$packageName")
                }
            }

            // 功能 1:主屏幕下拉 -> 进入抽屉搜索
            SwipeDownToSearchHook.hook(this)

            // 功能 2:抽屉搜索框回车 -> 启动第一个结果
            EnterKeyLaunchHook.hook(this)

            // 功能 3 + 4 + 5:抽屉搜索结果(两行显示 / 模糊匹配 / 历史)
            // —— 三者都改 onSearchResult,YukiHook 同方法只能 hook 一次,故合并
            SearchResultHook.hook(this)

            // 功能 5 的「记录」部分:Hook startActivitySafely 记录启动过的 App
            LaunchRecorderHook.hook(this)
        }
    }
}
