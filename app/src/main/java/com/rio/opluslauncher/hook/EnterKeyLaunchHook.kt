package com.rio.opluslauncher.hook

import android.view.KeyEvent
import android.widget.TextView
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.factory.toClass
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.PackageParam

/**
 * 功能 2:抽屉搜索框按回车 -> 直接启动第一个(置顶)结果。
 *
 * 背景:基类 AllAppsSearchBarController.onEditorAction 本来就有
 *   mLauncher.getAppsView().getMainAdapterProvider().launchHighlightedItem()
 * 的逻辑(启动置顶结果)。但 OnePlus 的 OplusAllAppsSearchBarController 在
 * initialize() 里给搜索框换了个自己的 OnEditorActionListener(匿名内部类 $2),
 * 它对 IME 搜索键只做 hideKeyboard(),把启动逻辑绕过了。
 *
 * 本 Hook:接管那个 $2 监听器的 onEditorAction,IME 搜索/前往键按下且
 * 搜索词非空时,调 getMainAdapterProvider().launchHighlightedItem() 启动置顶结果。
 *
 * 反编译依据(System Launcher 16.4.15):
 *  - com.android.launcher3.allapps.search.OplusAllAppsSearchBarController$2
 *      boolean onEditorAction(TextView, int, KeyEvent)
 *      ⚠️ 匿名内部类编号 $2 对版本敏感,桌面升级后需重新确认。
 *  - AllAppsSearchBarController.mLauncher  (protected ActivityContext 字段)
 *  - ActivityContext#getAppsView()
 *  - BaseAllAppsContainerView#getMainAdapterProvider()
 *  - SearchAdapterProvider#launchHighlightedItem()  启动置顶结果,成功返回 true
 *  - actionId:3 = IME_ACTION_SEARCH,2 = IME_ACTION_GO
 */
object EnterKeyLaunchHook {

    /** ⚠️ 版本敏感:匿名内部类编号 $2 桌面升级后需重新确认 */
    private const val EDITOR_ACTION_LISTENER =
        "com.android.launcher3.allapps.search.OplusAllAppsSearchBarController\$2"

    fun hook(param: PackageParam) = with(param) {
        runCatching {
            EDITOR_ACTION_LISTENER.toClass()
                .method {
                    name = "onEditorAction"
                    paramCount = 3 // (TextView, int, KeyEvent)
                }
                .hook {
                    before {
                        val textView = args.getOrNull(0) as? TextView ?: return@before
                        val actionId = args.getOrNull(1) as? Int ?: return@before
                        val keyEvent = args.getOrNull(2) as? KeyEvent

                        // 两条触发路径:
                        //   ① IME 搜索/前往键(标准输入法的「搜索」按钮)
                        //   ② 硬件/部分第三方输入法直接发的 KEYCODE_ENTER(只取 ACTION_DOWN 一次)
                        val isImeAction = actionId == 2 || actionId == 3
                        val isHardwareEnter = keyEvent != null &&
                            keyEvent.keyCode == KeyEvent.KEYCODE_ENTER &&
                            keyEvent.action == KeyEvent.ACTION_DOWN
                        if (!isImeAction && !isHardwareEnter) return@before
                        if (textView.text.isNullOrEmpty()) return@before

                        if (launchFirstResult(instance ?: return@before)) {
                            YLog.info("回车启动第一个搜索结果(actionId=$actionId, hwEnter=$isHardwareEnter)")
                            result = true // 已消费,跳过原逻辑(原逻辑只收键盘)
                        } else {
                            YLog.warn("无可启动的置顶结果,交回原逻辑")
                        }
                    }
                }
            YLog.info("EnterKeyLaunchHook 已挂载:OplusAllAppsSearchBarController\$2.onEditorAction")
        }.onFailure {
            YLog.error("EnterKeyLaunchHook 挂载失败:${it.message}")
        }
    }

    /**
     * 从 $2 监听器出发,启动抽屉搜索的第一个结果。成功返回 true。
     *
     * 两条路径:
     *  A. 原生:getMainAdapterProvider().launchHighlightedItem()
     *     OnePlus 上 mHighlightedView 经常没被设置(isViewSupported 返回 false,
     *     onBindView 不被基类调用),所以这条经常失败。
     *  B. 兜底:从搜索结果数据直拿第一个 App 的 itemInfo,调 launcher.startActivitySafely。
     *     这是 OnePlus 搜索结果实际能用的路径。
     *
     * 链路:$2 --this$0--> Controller --mLauncher--> Launcher --getAppsView()--> 抽屉容器
     *       --getSearchAdapterHolder()--> AdapterHolder --getAppsListInHolder()-->
     *       AlphabeticalAppsList --getAdapterItems()--> List<AdapterItem>
     *       --首个 .itemInfo 非空--> AppInfo --getIntent()--> startActivitySafely
     */
    private fun launchFirstResult(listener: Any): Boolean = runCatching {
        val controller = readField(listener, "this\$0")
            ?: error("拿不到外部 controller(this\$0)")
        val launcher = readField(controller, "mLauncher")
            ?: error("拿不到 mLauncher")
        val appsView = invokeNoArg(launcher, "getAppsView")
            ?: error("getAppsView 返回 null")

        // ---- 方案 A:原生 launchHighlightedItem ----
        val methodA = runCatching {
            val provider = invokeNoArg(appsView, "getMainAdapterProvider")
                ?: return@runCatching false
            invokeNoArg(provider, "launchHighlightedItem") as? Boolean ?: false
        }.onFailure { YLog.warn("方案 A 失败:${it.message}") }.getOrDefault(false)
        if (methodA) return@runCatching true

        // ---- 方案 B:从搜索结果数据直拿第一个 App,直接 startActivitySafely ----
        val searchHolder = invokeNoArg(appsView, "getSearchAdapterHolder")
            ?: error("getSearchAdapterHolder 返回 null")
        val appsList = invokeNoArg(searchHolder, "getAppsListInHolder")
            ?: error("getAppsListInHolder 返回 null")
        val adapterItems = invokeNoArg(appsList, "getAdapterItems") as? List<*>
            ?: error("getAdapterItems 返回 null")

        val firstAppItem = adapterItems.firstOrNull { item ->
            item != null && runCatching { readField(item, "itemInfo") }.getOrNull() != null
        } ?: error("搜索结果列表里没有可启动的 App 项")

        val itemInfo = readField(firstAppItem!!, "itemInfo")!!
        val intent = invokeNoArg(itemInfo, "getIntent")
            ?: error("itemInfo.getIntent() 返回 null")

        // launcher.startActivitySafely(View view, Intent intent, ItemInfo itemInfo)
        val m = launcher.javaClass.methods.first {
            it.name == "startActivitySafely" && it.parameterTypes.size == 3
        }
        m.isAccessible = true
        val ok = m.invoke(launcher, null, intent, itemInfo) as? Boolean ?: false
        if (!ok) error("startActivitySafely 返回 false")
        true
    }.onFailure { YLog.error("启动第一个结果失败:${it.message}") }.getOrDefault(false)

    /** 读取字段值,沿父类链向上查找(字段可能声明在父类)。 */
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
