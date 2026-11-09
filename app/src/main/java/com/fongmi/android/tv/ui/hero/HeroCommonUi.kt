package com.fongmi.android.tv.ui.hero

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.R

// ---------------------------------------------------------------------------
// 全局主题色，分两支：
//
// 1. [HeroAccentColor]：**描边 / 下划线 / 小竖条**用纯红 #FF0000
//    （与海报卡 2dp 焦点框、选集格 2dp 焦点框同色）。
// 2. [HeroFocusBrush]：**实心红块**（分类 Tab、搜索胶囊、站点 Tab、播放线路、分组按钮）
//    **直接读首页 Tab 栏的同一组资源** `home_tab_focus_start → home_tab_focus_end`
//    （见 `HomeViewActivity.tabBackground`：2 段 LEFT_RIGHT 渐变 + 28dp 胶囊），
//    不再单独维护色值，杜绝各页面各调一支红。
//
// 以后新增任何「实心焦点块」都直接用 [HeroFocusBrush]，不要再写纯红 solid。
// ---------------------------------------------------------------------------
internal val HeroAccentColor = Color(0xFFFF0000)

/**
 * 实心焦点块专用渐变：与首页 Tab 栏焦点背景**同源、同向、同两段色标**。
 *
 * 直接读取 `colors.xml` 的 `home_tab_focus_start`（@color/red = #E50914）与
 * `home_tab_focus_end`（#FF7A85），由 [colorResource] 在组合期解析 ——
 * 首页（View 侧 `GradientDrawable`）与 Compose 侧永远同色，不存在第二份硬编码。
 */
internal val HeroFocusBrush: Brush
    @Composable
    @ReadOnlyComposable
    get() = Brush.horizontalGradient(
        0.0f to colorResource(R.color.home_tab_focus_start),
        1.0f to colorResource(R.color.home_tab_focus_end),
    )

// ---------------------------------------------------------------------------
// 统一弹窗外壳（ref_docs/dialog_source.xml）：深灰蓝半透明 #9914171F + 16dp 圆角 + 18dp 内边距。
// 选集弹窗、演员详情弹窗、换源弹窗全部套这一层，保证所有弹窗视觉一致。
//
// 【色值变更】原来 #E6000000（90% 纯黑）叠上窗口 dim 0.5 后只剩 5% 背景光，整屏像蒙了黑布；
// 现在降到 60%（#99）+ 逆向 ref_docs/shape_dialog_source.xml 的色相 #14171F，
// 面板能透出约 20% 背景，与实机原版一致。与 res/drawable/shape_dialog_source_shell.xml 同值。
// ---------------------------------------------------------------------------
private val DIALOG_SHELL_BACKGROUND = Color(0x9914171F)
private val DIALOG_SHELL_SHAPE = RoundedCornerShape(16.dp)
private val DIALOG_SHELL_PADDING = 18.dp

@Composable
internal fun HeroDialogShell(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(DIALOG_SHELL_SHAPE)
            .background(DIALOG_SHELL_BACKGROUND)
            .padding(DIALOG_SHELL_PADDING),
        content = content,
    )
}

// ---------------------------------------------------------------------------
// 区块标题：4dp × 16dp 红色小竖条 + 8dp 间距 + 加粗文字（不可聚焦）。
// ---------------------------------------------------------------------------
private val SECTION_BAR_WIDTH = 4.dp
private val SECTION_BAR_HEIGHT = 16.dp
private val SECTION_BAR_SHAPE = RoundedCornerShape(2.dp)
private val SECTION_BAR_GAP = 8.dp
private const val SECTION_TITLE_SP = 15f

/** 区块标题：前方 4dp × 16dp 红色小竖条 + [fontSize] 加粗文字。 */
@Composable
internal fun HeroSectionTitle(text: String, fontSize: TextUnit = SECTION_TITLE_SP.sp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(SECTION_BAR_WIDTH)
                .height(SECTION_BAR_HEIGHT)
                .background(HeroAccentColor, SECTION_BAR_SHAPE),
        )
        Spacer(modifier = Modifier.width(SECTION_BAR_GAP))
        Text(
            text = text,
            color = Color.White,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// 全局公共「小房子」按钮：严格照抄 ref_docs/activity_home_v2.xml 的 homeBtn 层级。
//
// <FrameLayout homeBtn        28dp  background=@drawable/selector_home_logo
//   <FrameLayout homeBtnClip  24dp  background=@drawable/shape_oval_clip clipToOutline
//     <ImageView homeBtnHome  16dp  src=@drawable/ic_home_logo layout_marginBottom=1dp
//
// 状态与 ref_docs/selector_home_logo 一致：常态 @color/white_10 椭圆，焦点渐变红椭圆；
// 原版没有放大、没有白描边，这里也不加。
// 焦点红与首页 Tab 栏同源（HeroFocusBrush），不再使用单独的实心 #E50914。
// 方向键交给调用方按上下文处理（首页 ↓ 进 Tab，弹窗 ↓ 进结果卡片）。
// ---------------------------------------------------------------------------
private val HOME_LOGO_FRAME = 28.dp                 // homeBtn layout_width / layout_height
private val HOME_LOGO_CLIP = 24.dp                  // homeBtnClip layout_width / layout_height
private val HOME_LOGO_ICON = 16.dp                  // homeBtnHome layout_width / layout_height
private val HOME_LOGO_ICON_BOTTOM_MARGIN = 1.dp     // homeBtnHome layout_marginBottom
private val HOME_LOGO_NORMAL = Color(0x1AFFFFFF)    // selector_home_logo 常态 = @color/white_10

@Composable
internal fun HeroHomeLogoButton(
    requester: FocusRequester,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onDirection: (Key) -> Boolean = { false },
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .size(HOME_LOGO_FRAME)
            // 焦点态 = 首页 Tab 同源渐变红圆；常态仍是 white_10 椭圆。
            .background(if (isFocused) HeroFocusBrush else SolidColor(HOME_LOGO_NORMAL), CircleShape)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { event ->
                event.type == KeyEventType.KeyDown && onDirection(event.key)
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onConfirm),
        contentAlignment = Alignment.Center,
    ) {
        // homeBtnClip：24dp 圆 + clipToOutline，把 16dp 图标裁进这个圆；
        // marginBottom=1dp 在 Compose 里等价于给这块内容加 1dp 下内边距，图标因此上移 0.5dp。
        Box(
            modifier = Modifier
                .size(HOME_LOGO_CLIP)
                .clip(CircleShape)
                .padding(bottom = HOME_LOGO_ICON_BOTTOM_MARGIN),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_home_logo),
                contentDescription = "Home",
                // vector 自带 fillColor=@color/white，这里再统一 tint 一次，保证任何主题下都是纯白。
                colorFilter = ColorFilter.tint(Color.White),
                modifier = Modifier.size(HOME_LOGO_ICON),
            )
        }
    }
}
