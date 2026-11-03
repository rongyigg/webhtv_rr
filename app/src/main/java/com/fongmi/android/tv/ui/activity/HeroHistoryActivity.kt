package com.fongmi.android.tv.ui.activity

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import com.fongmi.android.tv.BuildConfig
import com.fongmi.android.tv.event.RefreshEvent
import com.fongmi.android.tv.ui.hero.HeroImageLoader
import com.fongmi.android.tv.ui.hero.HistoryScreen
import com.fongmi.android.tv.ui.hero.HistoryViewModel
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/**
 * HeroUI 历史页宿主（Compose）。
 *
 * 页面本体在 [HistoryScreen]，数据与业务在 [HistoryViewModel]；本类只负责：
 * 1. 窗口设置（纯黑背景 + 全屏沉浸，与原 View 版一致）；
 * 2. 安装 Coil（[HeroImageLoader.install]）并 `setContent`；
 * 3. 注册 `RefreshEvent`：`Type.HISTORY` → `viewModel.reload()`，对应 `o11.onRefreshEvent`
 *    L413-455（原版 `zj2.a == 3` 分支；本工程 `RefreshEvent` 不带 payload，因此只做重载）。
 *
 * 与上一版（View + RecyclerView + Mock 数据）的差异：页面结构、卡片、焦点、进度条全部
 * 迁到 Compose + 全局 [com.fongmi.android.tv.ui.hero.HeroPosterCard]，本文件不再持有任何 View 引用。
 *
 * 保留 [start] 签名：`HeroHomeActivity` 顶栏「历史」直接调用，调用方无需改动。
 */
class HeroHistoryActivity : ComponentActivity() {

    private val viewModel: HistoryViewModel by lazy {
        ViewModelProvider(this)[HistoryViewModel::class.java]
    }

    /**
     * Mock 开关：Intent extra [EXTRA_FORCE_MOCK_HISTORY] 优先，缺省取 [BuildConfig.DEBUG]。
     *
     * - Release 包 `BuildConfig.DEBUG == false` → 永远走真实数据；
     * - Debug 包默认开启，但只在「真实历史为空」时才真的显示 Mock（见 `HistoryViewModel.loadInternal`）。
     */
    private val forceMockHistory: Boolean by lazy {
        intent?.getBooleanExtra(EXTRA_FORCE_MOCK_HISTORY, BuildConfig.DEBUG) ?: BuildConfig.DEBUG
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        HeroImageLoader.install()
        setContent { HistoryScreen(viewModel = viewModel, forceMock = forceMockHistory) }
    }

    override fun onStart() {
        super.onStart()
        if (!EventBus.getDefault().isRegistered(this)) EventBus.getDefault().register(this)
    }

    override fun onStop() {
        if (EventBus.getDefault().isRegistered(this)) EventBus.getDefault().unregister(this)
        super.onStop()
    }

    /**
     * 历史变更（播放进度回写、删除等）→ 重新读库。
     *
     * 原版 `o11.onRefreshEvent` L423-451 会先把 payload 里的
     * `position/duration/vodRemarks/episodeUrl/vodFlag` 合并到内存列表，再 `a0()` 重载；
     * 本工程 `RefreshEvent` 只带 `Type`（`RefreshEvent.java` L9-11），没有 payload，
     * 因此等价行为就是直接 [HistoryViewModel.reload]（重载后 p0/r0 自然按新数据刷新）。
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRefreshEvent(event: RefreshEvent) {
        if (event.type == RefreshEvent.Type.HISTORY) viewModel.reload()
    }

    companion object {

        /** Intent extra：`--ez force_mock_history true/false`。缺省取 `BuildConfig.DEBUG`。 */
        const val EXTRA_FORCE_MOCK_HISTORY = "force_mock_history"

        fun start(context: Context) {
            context.startActivity(Intent(context, HeroHistoryActivity::class.java))
        }

        /** 显式指定 Mock 开关的入口（仅调试用；[start] 的默认行为不变）。 */
        fun start(context: Context, forceMock: Boolean) {
            context.startActivity(
                Intent(context, HeroHistoryActivity::class.java)
                    .putExtra(EXTRA_FORCE_MOCK_HISTORY, forceMock),
            )
        }
    }
}

/**
 * Hero 页面遮罩基类：把首页 Compose 的 `Brush.horizontalGradient / verticalGradient`
 * 原样搬到 View 侧。`GradientDrawable` 最多只支持 3 个色标，而首页纵向遮罩有 4 个，
 * 所以这里直接用 [LinearGradient] + [Paint] 自绘，色标数量、位置、透明度与 Compose 完全一致。
 *
 * 说明：本类仍被 `res/layout/activity_hero_history.xml` 引用（该布局保留未删），
 * 页面本体已迁到 Compose，这里的遮罩只服务旧布局，不要删除。
 */
internal abstract class HeroMaskView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        // 纯自绘 View，必须显式允许 onDraw。
        setWillNotDraw(false)
    }

    /** 由子类给出方向与色标；尺寸变化后重建（等比拉伸到整屏）。 */
    protected abstract fun createShader(width: Float, height: Float): Shader

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        paint.shader = createShader(w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}

/**
 * 横向遮罩（首页 `HeroMaskLeft`，XML `maskLeft` / `bg_hero_mask_h`）：
 * 左侧 78% 黑 → 52% 处 16% 黑 → 右侧完全透明，保证左侧剧名/简介在浅色背景上仍可读。
 */
internal class HeroMaskLeftView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : HeroMaskView(context, attrs, defStyleAttr) {

    override fun createShader(width: Float, height: Float): Shader = LinearGradient(
        0f, 0f, width, 0f,
        intArrayOf(0xC7000000.toInt(), 0x29000000, 0x00000000),
        floatArrayOf(0f, 0.52f, 1f),
        Shader.TileMode.CLAMP,
    )
}

/**
 * 纵向遮罩（首页 `HeroMaskBottom`，XML `maskBottom` / `bg_hero_mask_v`）：
 * 顶部 86% 黑 → 26% 处 30% → 58% 处 62% → 底部 97%，四个色标一个不少。
 */
internal class HeroMaskBottomView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : HeroMaskView(context, attrs, defStyleAttr) {

    override fun createShader(width: Float, height: Float): Shader = LinearGradient(
        0f, 0f, 0f, height,
        intArrayOf(0xDB000000.toInt(), 0x4D000000, 0x9E000000.toInt(), 0xF7000000.toInt()),
        floatArrayOf(0f, 0.26f, 0.58f, 1f),
        Shader.TileMode.CLAMP,
    )
}
