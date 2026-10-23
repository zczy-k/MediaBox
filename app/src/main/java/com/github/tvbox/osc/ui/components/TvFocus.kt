package com.github.tvbox.osc.ui.components

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 当前是否运行在电视（Android TV / Leanback）上。
 *
 * <p>判据与 [com.github.tvbox.osc.util.ScreenUtils.isTv] 一致，都读 `uiMode`，
 * 只是 Compose 侧无法直接拿 `Context`，所以要单独写一份能随配置变化重组的版本。
 *
 * <p>⚠️ 别把它换成 `Build.MODEL` 或屏幕尺寸之类的推断：那类判据在"电视盒子接显示器"
 * "平板横屏"上都会误判，而 uiMode 是系统自己声明的，唯一可靠。
 */
@Composable
fun rememberIsTelevision(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
        Configuration.UI_MODE_TYPE_TELEVISION
}
