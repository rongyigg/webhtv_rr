package com.fongmi.android.tv.ui.hero

import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.fongmi.android.tv.net.HeroHttpDns
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
}
