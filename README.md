# OnePlus Search Enhancer

> 一个 LSPosed 模块,增强一加系统桌面(OxygenOS 16)的应用抽屉搜索体验。

[English](README.en.md) | **简体中文**

---

## ✨ 功能

| # | 功能 | 说明 |
|---|---|---|
| 1 | **下拉直达搜索** | 主屏幕下拉手势 → 直接进入抽屉搜索界面(代替默认的外部 HeyTap 全局搜索) |
| 2 | **回车启动第一个结果** | 在搜索框按键盘上的「搜索」键 → 直接打开第一个匹配的 App |
| 3 | **结果两行显示** | 输入查询时,搜索结果以 2 行 × 4 列(最多 8 个)显示在搜索框上方 |
| 4 | **模糊匹配** | 替换默认搜索算法,按「前缀 > 子串 > 子序列」打分排序;**不走拼音** —— 搜 `you` 直接命中 `YouTube`,不会被「邮储银行」抢前(后者只是拼音首字母匹配) |
| 5 | **搜索历史** | 记录从搜索里启动过的 App;再次下拉打开搜索时,在搜索框上方显示一行最近启动的 App,点击直达 |

---

## 📱 系统要求

- **设备**:一加手机,运行 OxygenOS 16
- **桌面**:System Launcher 16.4.15(`com.android.launcher`,build 160040015)
- **环境**:已 root + LSPosed 框架(基于 Magisk/KernelSU 等)
- **Android**:31+(Android 12 或更高)

> ⚠️ 仅在 **OOS 16 / Launcher 16.4.15** 上实测通过。其它系统版本可能因桌面类名/方法名变动失效(详见下方「兼容性」)。

---

## 📥 安装

1. 从 [Releases](../../releases) 下载最新 `app-release.apk`
2. 安装到手机
3. 打开 **LSPosed 管理器** → **模块**
4. 找到 **OPlus Launcher Mod**,启用
5. 进入它的「作用域」,勾选:
   - ✅ `一加系统桌面 / com.android.launcher`
   - ✅ `OPlus Launcher Mod` 自己(状态页判定需要)
6. 重启桌面进程(或重启手机)
7. 打开模块 App,确认显示「✓ 模块已激活」

---

## 🚀 使用

| 操作 | 效果 |
|---|---|
| 在主屏幕**下拉** | 进入抽屉搜索 + 弹出键盘,搜索框上方显示一行最近启动过的 App |
| 输入查询(如 `you`) | 搜索结果以 2 行 × 4 列显示在搜索框上方,按相关度排序 |
| 点击键盘**「搜索」键** | 启动第一个结果(无需手动点) |
| 不输入查询 | 直接显示历史 App 列表(空搜索框时) |
| 从搜索启动 App | 自动加入历史;之后下拉立刻可见 |

---

## 🛠️ 从源码构建

需要 **JDK 17** 和 Android SDK(compileSdk 34)。

```bash
git clone https://github.com/zhangbaoshengrio/OnePlusSearchEnhancer.git
cd OnePlusSearchEnhancer

# 命令行构建(指定 JDK 17;系统 Java 太新会报版本不兼容)
JAVA_HOME=/path/to/jdk17 ./gradlew assembleRelease

# 或在 Android Studio 打开(它自带合适 JDK,无需手动指定)
```

产物路径:`app/build/outputs/apk/release/app-release.apk`

---

## 🏗️ 实现概要

模块用 [YukiHookAPI](https://github.com/HighCapable/YukiHookAPI) 框架编写,共 5 个 Hook 文件:

| Hook | 目标 | 干什么 |
|---|---|---|
| `SwipeDownToSearchHook` | `WorkspacePullDownDetectController.startTargetApp` | 接管下拉手势,改打开抽屉搜索 |
| `EnterKeyLaunchHook` | `OplusAllAppsSearchBarController$2.onEditorAction` | 接管回车键,启动第一个结果 |
| `SearchResultHook` | `AppsSearchContainerLayout.onSearchResult` | 合并实现:模糊重排 / 截断成两行 / 抬升定位 / 注入历史 |
| `LaunchRecorderHook` | `Launcher.startActivitySafely` | 记录从搜索启动过的 App |
| `SearchHistoryManager` | (非 Hook,工具类) | 历史存储到 SharedPreferences |

> 📝 三个功能(两行 / 模糊 / 历史)都改 `onSearchResult` —— 因为 YukiHookAPI 同一个方法只能 hook 一次,必须合并。

---

## ⚠️ 兼容性

桌面是闭源的,本模块依赖反编译得到的具体类名 / 方法名:

- `com.android.launcher.touch.WorkspacePullDownDetectController`
- `com.android.launcher3.allapps.search.OplusAllAppsSearchBarController$2`
- `com.android.launcher3.allapps.search.AppsSearchContainerLayout`
- `com.android.launcher3.allapps.search.LauncherTaskbarAppsSearchContainerLayout`
- `com.android.launcher3.allapps.OplusLauncherAllAppsContainerView`

**桌面版本更新后这些类名/签名很可能失效**。如果某个功能不工作,最稳妥的办法是:

```bash
adb shell pm path com.android.launcher  # 找 APK
adb pull <上一步路径>
jadx-gui ./<apk>                          # 反编译核对类名
```

每个 Hook 文件顶部都写了反编译依据 + 踩过的坑,可以对照修改。

---

## 📜 许可证

[MIT](LICENSE)

---

## 🙏 致谢

- [YukiHookAPI](https://github.com/HighCapable/YukiHookAPI) —— Hook 框架
- [LSPosed](https://github.com/LSPosed/LSPosed) —— 运行时
- [jadx](https://github.com/skylot/jadx) —— 反编译器
- 灵感参考 [wizpizz/OnePlusPlusLauncher](https://github.com/wizpizz/OnePlusPlusLauncher)
