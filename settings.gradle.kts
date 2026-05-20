pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Xposed API 仓库
        maven("https://api.xposed.info/")
        // JitPack:YukiHookAPI 的传递依赖 FreeReflection 在此托管
        maven("https://jitpack.io")
    }
}

rootProject.name = "OPlusLauncherMod"
include(":app")
