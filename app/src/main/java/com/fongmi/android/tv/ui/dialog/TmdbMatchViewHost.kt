package com.fongmi.android.tv.ui.dialog

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.fongmi.android.tv.bean.TmdbItem
import com.fongmi.android.tv.bean.Vod

/**
 * Compose 页面（首页 / Hero 首页）启动「纯 View」手动 TMDB 匹配弹窗的极薄桥接层。
 *
 * 这里**不渲染任何 Compose UI**：只在本 composition 挂载时把 [TmdbMatchViewDialog]（标准
 * `AlertDialog` + XML 布局）弹出来，弹窗内部结构、虚拟键盘、焦点流转全部是纯 View 实现，
 * 和 Compose 的焦点系统完全解耦。确认 / 关闭仍然通过原有回调回到页面逻辑，调用方无需改动。
 *
 * 之所以保留这一层：两个首页都是 Compose 页面，弹窗只能在 composition 里按状态触发；
 * 保留它可以把「弹窗本身」和「怎么被打开」分开，后续页面若改成 View 只需删掉这个文件。
 */
@Composable
internal fun TmdbMatchViewHost(
    initialKeyword: String,
    vod: Vod?,
    onConfirm: (TmdbItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(initialKeyword, vod) {
        val activity = context.findActivity() ?: return@LaunchedEffect
        TmdbMatchViewDialog.create(activity)
            .keyword(initialKeyword)
            .vod(vod)
            .listener { item -> onConfirm(item) }
            .dismissListener { onDismiss() }
            .show()
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
