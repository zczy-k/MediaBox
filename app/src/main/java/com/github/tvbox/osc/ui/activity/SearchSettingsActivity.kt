package com.github.tvbox.osc.ui.activity

import android.content.Context
import android.content.Intent
import androidx.compose.ui.platform.ComposeView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.ui.components.SearchSettingsScreen
import com.github.tvbox.osc.ui.components.SheetHostScaffold
import com.github.tvbox.osc.ui.theme.MediaBoxTheme
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge

/**
 * 「搜索设置」子页 —— 与主题/播放/偏好/配置管理并列,从设置页进入。
 *
 * <p>2026-10-21 从首页三点菜单的弹层搬来:它是低频配置,不该占首页的高频入口。
 */
class SearchSettingsActivity : BaseActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, SearchSettingsActivity::class.java))
        }
    }

    override fun getLayoutResID(): Int = R.layout.activity_main

    override fun hideSysBar() {
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        findViewById<ComposeView>(R.id.compose_view).setContent {
            MediaBoxTheme {
                SheetHostScaffold {
                    SearchSettingsScreen(onNavigateBack = { finish() })
                }
            }
        }
    }
}
