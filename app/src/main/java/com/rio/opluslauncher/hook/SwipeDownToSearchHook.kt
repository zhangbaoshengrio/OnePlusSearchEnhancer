package com.rio.opluslauncher.hook

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.View
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.factory.toClass
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.PackageParam

/**
 * 功能 1:主屏幕下拉手势 -> 直接进入应用抽屉的搜索界面。
 *
 * 一加桌面默认:下拉走 WorkspacePullDownDetectController.startTargetApp,
 * 最终打开**外部的** HeyTap 全局搜索 App。本 Hook 把它改成打开桌面自己的
 * 抽屉(ALL_APPS 状态)并直接进入搜索模式。
 *
 * 踩过的坑(System Launcher 16.4.15 实测):
 *  1. 下拉手势期间 PullDownAnimator 会拉下一层带虚化的遮罩。直接接管
 *     startTargetApp 而不清理它,抽屉会被遮罩盖住、整个桌面卡死。
 *     => 必须先调 controller.pullCancel() 销毁下拉动画。
 *  2. 不能用 Launcher.showAllAppsFromIntent():它在「标准模式」下会触发
 *     整个桌面模式切换(切到抽屉模式、重启 Launcher),非常重且会卡。
 *     => 直接用 StateManager.goToState(LauncherState.ALL_APPS) 切状态。
 *  3. goToState 后到进入搜索模式之间有个极短窗口,会瞥见一下应用网格。
 *     遮挡这一闪的几条路都试过:
 *       - 改 appsView.visibility:进搜索模式时桌面会设回 VISIBLE,无效。
 *       - 往 content 加遮罩 View:OnePlus 抽屉渲染层级更高,盖不住。
 *       - 唯一够得着抽屉的是 RenderEffect —— 它作用在抽屉视图自身的渲染上,
 *         与窗口层级无关。但它只能糊到 appsView 自身的内容(图标);抽屉那层
 *         虚化壁纸背景是 OnePlus blur 子系统画的独立图层,够不到。
 *     => 过渡期给 appsView 套强模糊 RenderEffect 把图标糊掉;进搜索模式后清除。
 *        (那层虚化背景本就是搜索界面自己的背景,过渡期看到的就是它,可接受。)
 *  4. 完全同步(0 延迟)进搜索偶尔失败(此刻搜索栏视图还没就绪)。
 *     => 进搜索模式用「极小延迟 + 校验 + 重试」。
 *
 * 反编译依据:
 *  - com.android.launcher.touch.WorkspacePullDownDetectController
 *      private boolean startTargetApp(Launcher)   下拉手势总分发点
 *      public  void    pullCancel()               销毁下拉动画/遮罩
 *  - com.android.launcher3.statemanager.StateManager
 *      public void goToState(STATE_TYPE)           单参数版,唯一,默认动画
 *  - com.android.launcher3.LauncherState.ALL_APPS  (public static 字段)
 *  - com.android.launcher3.Launcher#getStateManager() / getAppsView()
 *  - com.android.launcher3.allapps.OplusLauncherAllAppsContainerView
 *      public boolean hasEnterSearchMode()         是否已进入搜索模式
 *  - com.android.launcher3.allapps.search.LauncherAppsSearchContainerLayout
 *      public void onSearchBarClick()  等价于点击抽屉搜索栏:进入搜索模式 + 弹输入法
 */
object SwipeDownToSearchHook {

    private const val PULL_DOWN_CONTROLLER =
        "com.android.launcher.touch.WorkspacePullDownDetectController"

    /**
     * 进搜索模式的延迟与重试参数(毫秒)。
     * 这两个值砍太短会让「确认进搜索模式」过早 —— 此刻搜索 UI 还没就绪,
     * 注入历史会失败。保持在实测可靠的值,真正能压的是「确认之后」的等待。
     */
    private const val FIRST_DELAY = 32L
    private const val RETRY_DELAY = 90L
    private const val MAX_ATTEMPTS = 3

    /** 过渡期模糊掉应用图标的强度(像素;越大越糊) */
    private const val BLUR_RADIUS = 900f

    /** 过渡期把抽屉压暗到的亮度比例(0~1,越小越黑;模糊+压暗一起遮挡) */
    private const val DIM_SCALE = 0.22f

    /** 过渡期被藏起来的 all-apps 网格 RV,过渡结束时恢复 alpha */
    @Volatile
    private var hiddenGridView: View? = null

    /**
     * 进搜索模式后、撤掉模糊前的延迟(毫秒)。
     * 历史是一行、不做上移定位,所以只需给搜索结果渲染留一两帧即可,不必久等。
     */
    private const val BLUR_CLEAR_DELAY = 64L

    private val mainHandler = Handler(Looper.getMainLooper())

    fun hook(param: PackageParam) = with(param) {
        runCatching {
            PULL_DOWN_CONTROLLER.toClass()
                .method {
                    name = "startTargetApp"
                    paramCount = 1 // 唯一参数为 com.android.launcher.Launcher
                }
                .hook {
                    replaceAny {
                        val controller = instance
                        val launcher = args.getOrNull(0)
                        if (controller == null || launcher == null) {
                            YLog.warn("startTargetApp 缺少实例/参数,放弃重定向")
                            return@replaceAny false
                        }
                        YLog.info("下拉手势 -> 重定向到抽屉搜索")
                        // 1) 先销毁下拉手势的动画与虚化遮罩
                        runCatching { invokeNoArg(controller, "pullCancel") }
                            .onFailure { YLog.error("pullCancel 失败:${it.message}") }
                        // 2) 打开抽屉(并立即套上模糊压暗效果,遮住一闪而过的网格)
                        if (goToAllApps(launcher)) {
                            // 3) 极小延迟后进搜索模式(带校验重试,成功后清除效果)
                            enterSearchMode(launcher, attempt = 1)
                        }
                        true // startTargetApp 返回 boolean,true 表示手势已消费
                    }
                }
            YLog.info("SwipeDownToSearchHook 已挂载:WorkspacePullDownDetectController.startTargetApp")
        }.onFailure {
            YLog.error("SwipeDownToSearchHook 挂载失败:${it.message}")
        }
    }

    /**
     * 切到 ALL_APPS 抽屉状态(**无动画、瞬间切换**),并立即套上过渡模糊。成功返回 true。
     *
     * 用 goToState(STATE, animated=false):抽屉瞬间出现,没有滑入动画 ——
     * 既消除了「透过模糊看到东西在滑动」,坐标也立刻稳定,定位无需等动画,更快。
     */
    private fun goToAllApps(launcher: Any): Boolean = runCatching {
        val stateManager = invokeNoArg(launcher, "getStateManager")
            ?: error("getStateManager 返回 null")
        val allAppsState = Class
            .forName("com.android.launcher3.LauncherState", true, launcher.javaClass.classLoader)
            .getField("ALL_APPS")
            .get(null)
        // goToState(STATE_TYPE, boolean animated):取 boolean 重载,传 false = 不带动画
        val goToState = stateManager.javaClass.methods.first {
            it.name == "goToState" && it.parameterTypes.size == 2 &&
                it.parameterTypes[1] == java.lang.Boolean.TYPE
        }
        goToState.isAccessible = true
        goToState.invoke(stateManager, allAppsState, false)
        // 立即捕获本次抽屉会话的 baseline(SearchResultHook 在空查询时还原用)
        runCatching {
            val appsViewForBaseline = invokeNoArg(launcher, "getAppsView")
            val searchRv = appsViewForBaseline?.let {
                invokeNoArg(it, "getActiveSearchRecyclerView")
            } as? View
            if (searchRv != null) {
                SearchResultHook.sessionBaselineTy = searchRv.translationY
            }
        }
        // **同步**进搜索模式 —— 抽屉是 instant 切换的、状态已稳定,
        // 此刻 onSearchBarClick 能直接生效,让搜索模式从第一帧就接管,
        // 桌面会自动隐藏 all-apps 图标网格 —— 没有「网格闪一下」可言。
        // 后面的 enterSearchMode 重试循环作为兜底:万一同步没成,会再试。
        runCatching {
            val appsViewForSync = invokeNoArg(launcher, "getAppsView")
            val searchUi = appsViewForSync?.let { invokeNoArg(it, "getSearchUiManager") }
            if (searchUi != null) invokeNoArg(searchUi, "onSearchBarClick")
        }
        // 立即套效果:模糊+压暗 + 网格 alpha=0 兜底
        setTransitionEffect(launcher, on = true)
        true
    }.onFailure { YLog.error("打开抽屉失败:${it.message}") }.getOrDefault(false)

    /**
     * 进入搜索模式:延迟一点点再触发。
     *
     * onSearchBarClick() 生效后 hasEnterSearchMode() 标志位是**异步**更新的,
     * 不能紧接着校验。所以「执行」与「校验」错开 —— 本轮执行,下一轮确认:
     * 下一轮若发现已在搜索模式即收工(清除过渡效果),否则再点一次。
     */
    private fun enterSearchMode(launcher: Any, attempt: Int) {
        val delay = if (attempt == 1) FIRST_DELAY else RETRY_DELAY
        mainHandler.postDelayed({
            runCatching {
                val appsView = invokeNoArg(launcher, "getAppsView")
                    ?: error("getAppsView 返回 null")
                // 已在搜索模式 -> 此刻数据就绪:同步注入历史 + 撤模糊(同一消息内完成)。
                // 这之前默认推荐 App 一直被模糊盖着,确认这一刻同步换成历史再撤模糊,
                // 下一帧直接是清晰的历史,不会出现「清晰的默认 App -> 历史」的跳变。
                if (isInSearchMode(appsView)) {
                    invokeNoArg(appsView, "getSearchUiManager")?.let { injectHistoryNow(it) }
                    YLog.info("已进入抽屉搜索模式(第 ${attempt - 1} 轮生效)")
                    // 延迟撤模糊:等 SearchResultHook 把结果定位好(它注入后 ~260ms 才定位),
                    // 定位过程全程在模糊下,用户看不到位置跳动。
                    mainHandler.postDelayed(
                        { setTransitionEffect(launcher, on = false) },
                        BLUR_CLEAR_DELAY
                    )
                    return@runCatching
                }
                if (attempt > MAX_ATTEMPTS) {
                    setTransitionEffect(launcher, on = false) // 放弃也要清效果,别留糊屏
                    YLog.warn("尝试 $MAX_ATTEMPTS 轮仍未进入搜索模式,放弃")
                    return@runCatching
                }
                val searchUi = invokeNoArg(appsView, "getSearchUiManager")
                    ?: error("getSearchUiManager 返回 null")
                // 等价于点击抽屉底部搜索栏:进入搜索模式 + 弹输入法
                invokeNoArg(searchUi, "onSearchBarClick")
                // 下一轮负责确认(或在真未生效时重试)
                enterSearchMode(launcher, attempt + 1)
            }.onFailure {
                YLog.error("进入搜索模式失败(第 $attempt 轮):${it.message}")
                if (attempt <= MAX_ATTEMPTS) {
                    enterSearchMode(launcher, attempt + 1)
                } else {
                    setTransitionEffect(launcher, on = false)
                }
            }
        }, delay)
    }

    /**
     * 过渡期双保险:① 把抽屉内容容器 alpha=0(藏图标/Tab/A-Z),
     * ② 给 appsView 套强模糊+压暗 RenderEffect(兜底糊掉任何 alpha 没盖住的像素)。
     *
     * `b_level_apps_view_translate` 是除壁纸虚化外的所有抽屉内容容器;它 alpha=0
     * 后,壁纸虚化(它的兄弟节点 `all_apps_bg_layer`)继续显示,那本就是搜索界面
     * 的常态背景,视觉上是「壁纸糊一下,搜索框带历史出现」。模糊作保险,防止极少数
     * alpha 还没应用就被绘制了一帧的情况。
     */
    private fun setTransitionEffect(launcher: Any, on: Boolean) {
        runCatching {
            val appsView = invokeNoArg(launcher, "getAppsView") as? View ?: return
            // ① 内容容器隐身
            if (on) {
                val resId = appsView.context.resources.getIdentifier(
                    "b_level_apps_view_translate", "id", "com.android.launcher"
                )
                if (resId != 0) {
                    val container = appsView.findViewById<View>(resId)
                    if (container != null) {
                        container.alpha = 0f
                        hiddenGridView = container
                    }
                }
            } else {
                hiddenGridView?.alpha = 1f
                hiddenGridView = null
            }
            // ② 模糊 + 压暗(兜底)
            appsView.setRenderEffect(
                if (on) {
                    val blur = RenderEffect.createBlurEffect(
                        BLUR_RADIUS, BLUR_RADIUS, Shader.TileMode.CLAMP
                    )
                    val dim = RenderEffect.createColorFilterEffect(
                        ColorMatrixColorFilter(
                            ColorMatrix(
                                floatArrayOf(
                                    DIM_SCALE, 0f, 0f, 0f, 0f,
                                    0f, DIM_SCALE, 0f, 0f, 0f,
                                    0f, 0f, DIM_SCALE, 0f, 0f,
                                    0f, 0f, 0f, 1f, 0f
                                )
                            )
                        )
                    )
                    RenderEffect.createChainEffect(dim, blur)
                } else {
                    null
                }
            )
        }.onFailure { YLog.error("设置过渡效果失败(on=$on):${it.message}") }
    }

    /** 抽屉是否已处于搜索模式(OplusLauncherAllAppsContainerView.hasEnterSearchMode)。 */
    private fun isInSearchMode(appsView: Any): Boolean = runCatching {
        invokeNoArg(appsView, "hasEnterSearchMode") as? Boolean ?: false
    }.getOrDefault(false)

    /**
     * 同步调一次空查询的 onSearchResult —— 桌面打开抽屉不会自然触发它。
     * 在「搜索模式已确认」的那一刻同步调用(此时搜索 UI 与数据都已就绪),
     * 与撤模糊在同一主线程消息内完成 —— 下一帧直接是历史,不会闪默认 App。
     *
     * (不能在 onSearchBarClick() 刚调用、搜索模式还在进入中时调,
     *  那会打乱状态机,抽屉会停在非搜索的全部应用列表。)
     */
    private fun injectHistoryNow(searchUi: Any) {
        runCatching {
            val m = searchUi.javaClass.methods.firstOrNull {
                it.name == "onSearchResult" && it.parameterTypes.size == 2
            } ?: return
            m.isAccessible = true
            m.invoke(searchUi, "", ArrayList<Any>())
        }.onFailure { YLog.error("同步注入搜索历史失败:${it.message}") }
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
