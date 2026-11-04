package com.fongmi.android.tv.ui.hero

import android.content.Context
import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.fongmi.android.tv.net.HeroHttpDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Hero UI 的 Coil 图片加载入口。
 *
 * 独立的 OkHttp 客户端只服务 Compose 图片加载，DNS 使用 [HeroHttpDns] 的多路 DoH 并发解析，
 * 用来给 TMDB 海报/背景图提供防污染直连；既有 Glide 链路完全不受影响。
 */
object HeroImageLoader {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(HeroHttpDns)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun install() {
        SingletonImageLoader.setSafe { context -> newImageLoader(context) }
    }

    fun newImageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .build()

    /**
     * 取一张**真正模糊过**的小图，给搜索页做磨砂背景。
     *
     * 做法分三步，全程只在一张 [width]×[height] 的小图上算：
     * 1. 交给 Coil 解码（走 Dispatcher.IO，命中磁盘/内存缓存，不会重复下载大图）；
     * 2. 缩到 [width]×[height]（Coil 的 `size` 已按比例收过，这里只是兜底对齐）；
     * 3. 分离式盒式模糊：[BLUR_PASSES] 趟横向 + 纵向，等价于一次高斯模糊。
     *
     * 为什么不用 `RenderEffect`（API 31+）或 RenderScript：
     * 电视盒子版本跨度大，前者在旧系统上直接无效、后者已废弃；
     * 160×90 的小图上跑两趟盒式模糊只有几万次整数运算，一次算完就缓存成 Bitmap，
     * 绘制阶段只是画一张小图，所有 Android 版本表现完全一致。
     *
     * 失败（无网 / URL 为空 / 解码失败）时返回 null，调用方退回纯色渐变底。
     */
    suspend fun blurredBackdrop(
        context: Context,
        url: String,
        width: Int,
        height: Int,
        blurRadius: Int,
    ): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(width, height)
                // 必须拿软件位图：Coil 在 API 26+ 默认解码成 HARDWARE 位图，
                // 而硬件位图不允许 getPixels()，模糊读取像素会直接抛异常。
                .allowHardware(false)
                .build()
            val image = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image
                ?: return@runCatching null
            // createScaledBitmap 一次性把尺寸对齐到采样尺寸，避免后面模糊时按原始大图跑。
            val sample = Bitmap.createScaledBitmap(image.toBitmap(), width, height, true)
            boxBlur(sample, blurRadius)
        }.getOrNull()
    }

    /** 两趟盒式模糊（横一趟 + 纵一趟），两趟下来包络已经非常接近高斯。 */
    private const val BLUR_PASSES = 2

    /**
     * 分离式盒式模糊：先横后竖各做一遍，共 [BLUR_PASSES] 轮。
     *
     * 用「滑动窗口累加」而不是逐像素卷积：每移动一格只加减一个像素，
     * 复杂度 O(w×h)，和半径无关。边缘用 clamp（重复最外侧像素），不会出现黑边。
     */
    private fun boxBlur(src: Bitmap, radius: Int): Bitmap {
        val width = src.width
        val height = src.height
        if (radius <= 0 || width <= 0 || height <= 0) return src
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)
        val temp = IntArray(width * height)
        repeat(BLUR_PASSES) {
            blurHorizontal(pixels, temp, width, height, radius)
            blurVertical(temp, pixels, width, height, radius)
        }
        // 输出一张新的可变 Bitmap：绝不写回 Coil 内存缓存里的那张（可能是共享的）。
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun blurHorizontal(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
        val div = radius * 2 + 1
        for (y in 0 until height) {
            val row = y * width
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val p = src[row + i.coerceIn(0, width - 1)]
                a += (p ushr 24) and 0xFF
                r += (p ushr 16) and 0xFF
                g += (p ushr 8) and 0xFF
                b += p and 0xFF
            }
            for (x in 0 until width) {
                dst[row + x] = ((a / div) shl 24) or ((r / div) shl 16) or ((g / div) shl 8) or (b / div)
                val out = src[row + (x - radius).coerceIn(0, width - 1)]
                val inP = src[row + (x + radius + 1).coerceIn(0, width - 1)]
                a += ((inP ushr 24) and 0xFF) - ((out ushr 24) and 0xFF)
                r += ((inP ushr 16) and 0xFF) - ((out ushr 16) and 0xFF)
                g += ((inP ushr 8) and 0xFF) - ((out ushr 8) and 0xFF)
                b += (inP and 0xFF) - (out and 0xFF)
            }
        }
    }

    private fun blurVertical(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
        val div = radius * 2 + 1
        for (x in 0 until width) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val p = src[i.coerceIn(0, height - 1) * width + x]
                a += (p ushr 24) and 0xFF
                r += (p ushr 16) and 0xFF
                g += (p ushr 8) and 0xFF
                b += p and 0xFF
            }
            for (y in 0 until height) {
                dst[y * width + x] = ((a / div) shl 24) or ((r / div) shl 16) or ((g / div) shl 8) or (b / div)
                val out = src[(y - radius).coerceIn(0, height - 1) * width + x]
                val inP = src[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                a += ((inP ushr 24) and 0xFF) - ((out ushr 24) and 0xFF)
                r += ((inP ushr 16) and 0xFF) - ((out ushr 16) and 0xFF)
                g += ((inP ushr 8) and 0xFF) - ((out ushr 8) and 0xFF)
                b += (inP and 0xFF) - (out and 0xFF)
            }
        }
    }
}
