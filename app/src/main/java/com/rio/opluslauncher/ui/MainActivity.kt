package com.rio.opluslauncher.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import com.highcapable.yukihookapi.YukiHookAPI

/**
 * 模块自身的状态页。
 *
 * 这个 Activity 运行在「模块自己的进程」里,不是桌面进程。
 * 它唯一的作用:让你确认模块有没有被 LSPosed 成功激活。
 *
 * YukiHookAPI.Status.isModuleActive 的判定依赖于模块自身被 Hook,
 * 所以记得在 LSPosed 里把作用域 **也勾上本模块自己**,判定才准确。
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val active = YukiHookAPI.Status.isModuleActive

        val tv = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(64, 96, 64, 96)
            text = if (active) {
                setTextColor(Color.parseColor("#2E7D32"))
                "✓ 模块已激活\n\n" +
                    "请在 LSPosed 中确认作用域勾选了:\n" +
                    "com.android.launcher\n\n" +
                    "然后重启桌面进程(或重启手机)使其生效。"
            } else {
                setTextColor(Color.parseColor("#C62828"))
                "✗ 模块未激活\n\n" +
                    "请在 LSPosed 管理器中启用本模块,\n" +
                    "并勾选作用域后重启。"
            }
        }
        setContentView(tv)
    }
}
