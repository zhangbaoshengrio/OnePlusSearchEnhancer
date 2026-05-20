# 当前 release 未开启 minify;若以后开启,保留 Hook 入口与功能类
-keep class com.rio.opluslauncher.hook.** { *; }

# YukiHookAPI 自身的混淆规则由其依赖携带,这里无需重复
