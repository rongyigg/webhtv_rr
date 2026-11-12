package com.fongmi.android.tv.ui.activity

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.fongmi.android.tv.R

/**
 * 临时预览壳（阶段 A 第一步专用）。
 *
 * 只做一件事：把 `activity_hero_detail_view.xml` 铺到屏幕上，方便电视核对排版和尺寸。
 * 不接数据、不处理焦点、不参与正式导航；排版确认后删除本文件与 Manifest 节点。
 */
class HeroDetailViewPreviewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContentView(R.layout.activity_hero_detail_view)
    }
}
