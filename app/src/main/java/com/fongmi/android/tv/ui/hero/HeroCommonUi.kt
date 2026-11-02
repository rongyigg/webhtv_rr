package com.fongmi.android.tv.ui.hero

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.R

// ---------------------------------------------------------------------------
// 全局主题色：首页顶栏下划线 / Tab 选中底 / 海报焦点边框 / 弹窗焦点底统一使用这一个红。
// ---------------------------------------------------------------------------
internal val HeroAccentColor = Color(0xFFFF0000)

// ---------------------------------------------------------------------------
// 统一弹窗外壳（ref_docs/dialog_source.xml）：深色半透明 #E6000000 + 16dp 圆角 + 18dp 内边距。
// 选集弹窗、演员详情弹窗、换源弹窗全部套这一层，保证所有弹窗视觉一致。
// ---------------------------------------------------------------------------
private val DIALOG_SHELL_BACKGROUND = Color(0xE6000000)
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
// 全局公共「小房子」按钮：首页顶栏与换源弹窗「站点栏」直接共用同一份实现。
//
// 状态（与首页定稿完全一致）：
// - 常态：透明底、无描边、纯白 ic_home_logo；
// - 焦点：HeroAccentColor 红底 + 2dp 纯白外圈 + 1.1 倍放大；
// - 放大只走 graphicsLayer，不参与布局，不会顶动整排。
//
// 尺寸：首页 32dp 容器 / 28dp 圆 / 20dp 图标；换源弹窗 26dp 容器 / 26dp 圆 / 16dp 图标。
// 方向键交给调用方按上下文处理（首页 ↓ 进 Tab，弹窗 ↓ 进结果卡片）。
// ---------------------------------------------------------------------------
private val HOME_LOGO_DEFAULT_CONTAINER = 32.dp
private val HOME_LOGO_DEFAULT_CIRCLE = 28.dp
private val HOME_LOGO_DEFAULT_ICON = 20.dp
private val HOME_LOGO_BORDER = 2.dp
private const val HOME_LOGO_FOCUS_SCALE = 1.1f
private const val HOME_LOGO_ANIM_MS = 150

@Composable
internal fun HeroHomeLogoButton(
    requester: FocusRequester,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    containerSize: Dp = HOME_LOGO_DEFAULT_CONTAINER,
    circleSize: Dp = HOME_LOGO_DEFAULT_CIRCLE,
    iconSize: Dp = HOME_LOGO_DEFAULT_ICON,
    onFocused: () -> Unit = {},
    onDirection: (Key) -> Boolean = { false },
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    val focusScale by animateFloatAsState(
        targetValue = if (isFocused) HOME_LOGO_FOCUS_SCALE else 1f,
        animationSpec = tween(durationMillis = HOME_LOGO_ANIM_MS),
        label = "homeLogoScale",
    )

    Box(
        modifier = modifier
            .size(containerSize)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { event ->
                event.type == KeyEventType.KeyDown && onDirection(event.key)
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onConfirm),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(circleSize)
                .graphicsLayer {
                    scaleX = focusScale
                    scaleY = focusScale
                }
                .background(
                    color = if (isFocused) HeroAccentColor else Color.Transparent,
                    shape = CircleShape,
                )
                .then(
                    if (isFocused) Modifier.border(HOME_LOGO_BORDER, Color.White, CircleShape) else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_home_logo),
                contentDescription = "Home",
                // vector 自带 fillColor=@color/white，这里再统一 tint 一次，保证任何主题下都是纯白。
                colorFilter = ColorFilter.tint(Color.White),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
