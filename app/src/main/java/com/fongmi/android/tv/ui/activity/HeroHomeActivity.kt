package com.fongmi.android.tv.ui.activity

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.fongmi.android.tv.api.SiteApi
import com.fongmi.android.tv.ui.hero.HeroImageLoader
import com.fongmi.android.tv.utils.PushParser

/**
 * HeroUI（Compose）骨架页：全屏纯黑，中心一个"测试起播"按钮，用来验证
 * Compose 基建、遥控器焦点以及 UI 层拉起原有播放页的能力。
 *
 * 这是重写阶段的临时测试入口，正式首页就绪后整体替换即可。
 */
class HeroHomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.BLACK))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        HeroImageLoader.install()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HeroScaffold()
            }
        }
    }

    @Composable
    private fun HeroScaffold() {
        val focusRequester = remember { FocusRequester() }
        var focused by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Button(
                    onClick = { launchPlayback(this@HeroHomeActivity, DEMO_VOD_NAME) },
                    modifier = Modifier
                        .width(320.dp)
                        .height(72.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged { focused = it.isFocused },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        width = if (focused) 3.dp else 1.dp,
                        color = if (focused) FOCUS_COLOR else IDLE_COLOR,
                    ),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1A1A1A),
                        contentColor = Color.White,
                    ),
                ) {
                    Text(text = "测试起播", fontSize = 24.sp, fontWeight = FontWeight.Medium)
                }
                Text(
                    text = "HeroUI 基建自检 · 遥控器按 OK 拉起原有播放页",
                    color = Color(0xFF8A8A8A),
                    fontSize = 14.sp,
                )
            }
        }
    }

    /**
     * 播放桥接：复用项目现有的 [VideoActivity] + [PushParser] 入口。
     * Intent 与 Bundle 全部由 VideoActivity 内部封装，这里不拼任何 EXTRA key。
     */
    fun launchPlayback(context: Context, vodName: String) {
        val activity = context.findActivity() ?: return
        val push = PushParser.of(DEMO_STREAM_URL, vodName)
        VideoActivity.startDirect(activity, SiteApi.PUSH, push.getId(), push.getName(), null, null)
    }

    private fun Context.findActivity(): Activity? {
        var current: Context = this
        while (true) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext ?: return null
        }
    }

    private companion object {

        // 公开测试流，仅用于确认"点击 → 原有 PlaybackActivity"这条链路；可随时替换为任意可访问直链。
        private const val DEMO_STREAM_URL = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        private const val DEMO_VOD_NAME = "测试影片"

        private val FOCUS_COLOR = Color(0xFF4DA3FF)
        private val IDLE_COLOR = Color(0xFF2A2A2A)
    }
}
