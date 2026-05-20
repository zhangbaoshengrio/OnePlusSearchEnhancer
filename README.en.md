# OnePlus Search Enhancer

> An LSPosed module that enhances the app-drawer search of the OnePlus stock launcher on OxygenOS 16.

**English** | [简体中文](README.md)

---

## ✨ Features

| # | Feature | Description |
|---|---|---|
| 1 | **Pull-down → drawer search** | Pulling down on the home screen opens the in-launcher app search (replacing the default external HeyTap global search) |
| 2 | **Enter to launch top result** | Tapping the IME "Search" key launches the top matching app directly |
| 3 | **2-row results layout** | Search results show as 2 rows × 4 columns (up to 8 items), pinned just above the search bar |
| 4 | **Fuzzy matching** | Replaces the default algorithm with prefix > substring > subsequence scoring. **No pinyin** — typing `you` correctly returns YouTube instead of pinyin-matched apps like 邮储银行 (which only matches by initial-letter pinyin `you-chu-yin-hang`) |
| 5 | **Search history** | Tracks apps launched from search; the next time you pull down, a row of recently-launched apps appears just above the search bar for one-tap access |

---

## 📱 Requirements

- **Device**: OnePlus phone running OxygenOS 16
- **Launcher**: System Launcher 16.4.15 (`com.android.launcher`, build 160040015)
- **Environment**: Rooted + LSPosed (via Magisk / KernelSU / etc.)
- **Android**: 31+ (Android 12 or higher)

> ⚠️ Verified only on **OxygenOS 16 / Launcher 16.4.15**. Other versions may break due to changed launcher class names — see [Compatibility](#-compatibility).

---

## 📥 Installation

1. Download the latest `app-release.apk` from [Releases](../../releases)
2. Install it on your phone
3. Open **LSPosed Manager** → **Modules**
4. Find **OPlus Launcher Mod** and enable it
5. Open its **Scope** and check:
   - ✅ `OnePlus System Launcher / com.android.launcher`
   - ✅ `OPlus Launcher Mod` itself (needed for the status check)
6. Restart the launcher process (or reboot)
7. Open the module app and confirm "✓ Module Active"

---

## 🚀 Usage

| Action | Result |
|---|---|
| **Pull down** on home screen | Opens drawer search with keyboard up, history row above the search bar |
| Type a query (e.g. `you`) | Results show as 2 rows × 4 columns above the search bar, sorted by relevance |
| Tap the keyboard's **Search key** | Launches the top result immediately |
| Empty search box | Shows recently launched apps as history |
| Launch an app from search | Auto-added to history, visible on the next pull-down |

---

## 🛠️ Build from Source

Requires **JDK 17** and Android SDK (compileSdk 34).

```bash
git clone https://github.com/zhangbaoshengrio/OnePlusSearchEnhancer.git
cd OnePlusSearchEnhancer

# Command line build (must specify JDK 17; newer Java causes version mismatch)
JAVA_HOME=/path/to/jdk17 ./gradlew assembleRelease

# Or open in Android Studio (bundled JDK works out of the box)
```

Output: `app/build/outputs/apk/release/app-release.apk`

---

## 🏗️ Architecture

Written with the [YukiHookAPI](https://github.com/HighCapable/YukiHookAPI) framework, the module consists of 5 hook files:

| Hook | Target | Purpose |
|---|---|---|
| `SwipeDownToSearchHook` | `WorkspacePullDownDetectController.startTargetApp` | Intercept pull-down gesture, open drawer search instead |
| `EnterKeyLaunchHook` | `OplusAllAppsSearchBarController$2.onEditorAction` | Intercept IME Search key, launch top result |
| `SearchResultHook` | `AppsSearchContainerLayout.onSearchResult` | Combined: fuzzy re-rank / cap to 2 rows / reposition / inject history |
| `LaunchRecorderHook` | `Launcher.startActivitySafely` | Record apps launched from search |
| `SearchHistoryManager` | (utility, not a hook) | SharedPreferences-backed history storage |

> 📝 Three features (2-row / fuzzy / history) all modify `onSearchResult`. YukiHookAPI allows only one hook per method, so they're merged into a single hook.

---

## ⚠️ Compatibility

The launcher is closed-source; this module depends on specific reverse-engineered class names:

- `com.android.launcher.touch.WorkspacePullDownDetectController`
- `com.android.launcher3.allapps.search.OplusAllAppsSearchBarController$2`
- `com.android.launcher3.allapps.search.AppsSearchContainerLayout`
- `com.android.launcher3.allapps.search.LauncherTaskbarAppsSearchContainerLayout`
- `com.android.launcher3.allapps.OplusLauncherAllAppsContainerView`

**These class names/signatures may break after a launcher update.** If a feature stops working:

```bash
adb shell pm path com.android.launcher  # locate the APK
adb pull <path-from-above>
jadx-gui ./<apk>                          # decompile and verify class names
```

Each hook file has detailed comments documenting the reverse-engineering basis and known pitfalls.

---

## 📜 License

[MIT](LICENSE)

---

## 🙏 Credits

- [YukiHookAPI](https://github.com/HighCapable/YukiHookAPI) — Hook framework
- [LSPosed](https://github.com/LSPosed/LSPosed) — Runtime
- [jadx](https://github.com/skylot/jadx) — Decompiler
- Inspired by [wizpizz/OnePlusPlusLauncher](https://github.com/wizpizz/OnePlusPlusLauncher)
