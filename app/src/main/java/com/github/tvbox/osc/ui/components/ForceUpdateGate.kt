package com.github.tvbox.osc.ui.components

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.github.tvbox.osc.BuildConfig
import com.github.tvbox.osc.R
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.UpdateChecker

/**
 * 启动时的强制更新闸门。
 *
 * <p><b>为什么是"强制"</b>:检测到新版本时对话框**不可关闭** —— 点遮罩、按返回键都不关
 * (走 [MediaBoxAlertDialog] 的 `dismissible = false`),也不提供"稍后再说"。只留
 * "立即更新"一个出口。用户拉起系统安装器后本 App 退到后台,装完回来就是新版本。
 *
 * <p><b>失败不拦人</b>:检测请求失败(网络不通、GitHub 抽风)一律静默放过 —— 拿不到版本信息
 * 就当作"没有新版",绝不能让网络问题把用户挡在旧版里出不来。只有**确实查到了更新、
 * 且下载失败**时才提示,这时用户已经知道有新版本了。
 *
 * <p>挂在首页([MainScreen])根部。放在这里而不是 Application:更新对话框要有 Activity 上下文
 * 才能拉起安装器,也才有关闭时机。
 */
@Composable
fun ForceUpdateGate() {
    // 三层闸门的第一、二层(第三层是 GitHub 的 prerelease 标记,见 release.yml):
    // ① DEBUG 包:Android Studio 直接跑的,永不检查 —— 编译期常量,分支固定;
    // ② FORCE_UPDATE_ENABLED = false:CI 构建的自用调试包(-PforceUpdate=false)。
    // 两者都是编译期常量,早退不会造成"分支不稳定"的重组问题。
    if (BuildConfig.DEBUG || !BuildConfig.FORCE_UPDATE_ENABLED) return

    val context = LocalContext.current
    var state by remember { mutableStateOf<ForceUpdateState?>(null) }
    // 网络回调在 OkHttp 线程,Compose 状态只能回主线程改
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val currentVersion = remember { DefaultConfig.getAppVersionName(context).orEmpty() }

    LaunchedEffect(Unit) {
        UpdateChecker.check(
            onResult = { release ->
                mainHandler.post { if (release != null) state = ForceUpdateState.Found(release) }
            },
            onError = {
                // 启动检查失败静默:拿不到版本信息就当没有新版,不能用网络问题拦住用户
            },
        )
    }

    val current = state ?: return

    MediaBoxAlertDialog(
        // dismissible = false:点遮罩/返回键都不关闭。这个空实现是刻意的 ——
        // 关闭动作只由"立即更新"驱动,不给任何"逃逸"路径。
        onDismissRequest = { },
        dismissible = false,
        title = {
            Text(
                text = stringResource(R.string.force_update_title),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Text(
                text = when (current) {
                    is ForceUpdateState.Found -> stringResource(
                        R.string.force_update_message,
                        currentVersion,
                        current.release.version,
                    )

                    is ForceUpdateState.Downloading ->
                        stringResource(R.string.update_downloading, current.percent)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            val downloading = current is ForceUpdateState.Downloading
            TextButton(
                onClick = {
                    val found = current as? ForceUpdateState.Found ?: return@TextButton
                    if (found.release.apkUrl.isBlank()) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.update_no_apk),
                            Toast.LENGTH_LONG,
                        ).show()
                        return@TextButton
                    }
                    state = ForceUpdateState.Downloading(0)
                    UpdateChecker.download(
                        context = context,
                        url = found.release.apkUrl,
                        onProgress = { percent ->
                            mainHandler.post { state = ForceUpdateState.Downloading(percent) }
                        },
                        onFailed = {
                            mainHandler.post {
                                // 退回"发现新版本"态,用户可以再点一次重试(仍然关不掉)
                                state = ForceUpdateState.Found(found.release)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.update_download_failed),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                        onReady = { file ->
                            mainHandler.post { UpdateChecker.install(context, file) }
                        },
                    )
                },
                enabled = !downloading,
            ) {
                Text(
                    text = if (downloading) {
                        stringResource(R.string.update_downloading, current.percent)
                    } else {
                        stringResource(R.string.force_update_now)
                    },
                )
            }
        },
        // 无"稍后"/"关闭":强制更新的出口只有安装
        dismissButton = null,
    )
}

private sealed interface ForceUpdateState {
    data class Found(val release: UpdateChecker.Release) : ForceUpdateState

    data class Downloading(val percent: Int) : ForceUpdateState
}
