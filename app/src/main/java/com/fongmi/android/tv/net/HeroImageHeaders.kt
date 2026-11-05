package com.fongmi.android.tv.net

import android.net.Uri
import android.util.Log
import com.fongmi.android.tv.utils.ImageHeaderPolicy
import com.fongmi.android.tv.utils.UrlUtil
import com.github.catvod.utils.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 图片请求头解析：把原版 `ImgUtil.getUrl()`（Glide 链路）的规则原样搬到 Coil/OkHttp 链路上。
 *
 * 原版海报能正常显示，靠的是两层：
 * 1. 接口配置 JSON 顶部的全局 `headers`（按 host 匹配）—— 由 catvod 的 `ResponseInterceptor`
 *    在**全局 OkHttp 客户端**上注入，Glide 通过 `OkGlideModule` 复用了这个客户端，所以自动带上；
 * 2. `ImgUtil.getUrl()` 对单条图片地址的解析：`@Headers=` / `@Cookie=` / `@Referer=` /
 *    `@User-Agent=` 指令，以及 [ImageHeaderPolicy] 的豆瓣图片 Referer 规则。
 *
 * HeroUI 的 Coil 客户端此前是裸建的 `OkHttpClient.Builder()`，两层都没有 → 豆瓣全黑。
 * 这里补齐第 2 层（第 1 层由 [HeroImageLoader] 继承 `OkHttp.client()` 解决）。
 *
 * 注意：本类只处理 **http(s)** 地址；`assets://` / `file://` 由 Coil 自己的 fetcher 处理，
 * 不会走到 OkHttp 拦截器里。
 */
internal object HeroImageHeaders {

    private const val MARK_HEADERS = "@Headers="
    private const val MARK_COOKIE = "@Cookie="
    private const val MARK_REFERER = "@Referer="
    private const val MARK_USER_AGENT = "@User-Agent="

    /**
     * @param url 去掉 `@xxx=` 指令后的真实地址（可以直接交给 OkHttp 重新解析）。
     * @param headers 这条地址需要额外携带的请求头。
     */
    class Resolved(val url: String, val headers: Map<String, String>)

    /**
     * 解析一条图片地址。
     *
     * @return `null` 表示这条地址不需要任何额外处理（绝大多数普通图片），调用方直接放行即可。
     */
    fun resolve(raw: String): Resolved? {
        if (!raw.startsWith("http")) return null
        val marker = firstMarker(raw)
        if (marker < 0) {
            // 没有指令：只剩豆瓣防盗链这一条全局规则。
            val referer = ImageHeaderPolicy.doubanImageReferer(raw, false)
            if (referer.isEmpty()) {
                // 诊断：豆瓣域名但没命中 `img\d+\.doubanio\.com` 规则（例如 `qnmob3.doubanio.com`、
                // 老图床 `img1.douban.com`、`s3.doubanio.com`）。这类地址缺 Referer 基本必 403，
                // 这条 W 日志能直接把「没图」和「规则漏判」对上号。
                if (raw.contains("douban", ignoreCase = true)) {
                    Log.w("HeroImageError", "豆瓣图片未命中 Referer 规则: $raw")
                }
                return null
            }
            return Resolved(raw, mapOf(HEADER_REFERER to referer))
        }
        val url = raw.substring(0, marker)
        // OkHttp 会把指令里的 `{` `"` 等字符百分号编码，先还原再按原版规则解析。
        val tail = Uri.decode(raw.substring(marker))
        val headers = LinkedHashMap<String, String>()
        var hasReferer = false
        // 与原版一致：@Headers={"Referer":"..."} 这种整段 JSON。
        directive(tail, MARK_HEADERS)?.let { json ->
            runCatching {
                Json.toMap(Json.parse(json)).forEach { (name, value) ->
                    val key = UrlUtil.fixHeader(name)
                    if (ImageHeaderPolicy.isReferer(key)) hasReferer = true
                    headers[key] = value
                }
            }
        }
        directive(tail, MARK_COOKIE)?.let { headers[HEADER_COOKIE] = it }
        directive(tail, MARK_REFERER)?.let {
            headers[HEADER_REFERER] = it
            hasReferer = true
        }
        directive(tail, MARK_USER_AGENT)?.let { headers[HEADER_USER_AGENT] = it }
        // 与原版一致：只有没显式给 Referer 时才补豆瓣规则，不覆盖站点自己指定的值。
        val referer = ImageHeaderPolicy.doubanImageReferer(url, hasReferer)
        if (referer.isNotEmpty()) headers[HEADER_REFERER] = referer
        return Resolved(url, headers)
    }

    /** 指令必须按**出现位置**取最小下标：一条地址里可能同时带 `@Referer=` 和 `@User-Agent=`。 */
    private fun firstMarker(raw: String): Int = listOf(
        MARK_HEADERS,
        MARK_COOKIE,
        MARK_REFERER,
        MARK_USER_AGENT,
    ).map { raw.indexOf(it) }
        .filter { it >= 0 }
        .minOrNull() ?: -1

    /** 取 `@xxx=` 之后、下一个 `@` 之前的内容，等价于原版的 `split("@")[0]`。 */
    private fun directive(text: String, mark: String): String? {
        val index = text.indexOf(mark)
        if (index < 0) return null
        val rest = text.substring(index + mark.length)
        val end = rest.indexOf('@')
        return if (end < 0) rest else rest.substring(0, end)
    }

    private const val HEADER_REFERER = "Referer"
    private const val HEADER_COOKIE = "Cookie"
    private const val HEADER_USER_AGENT = "User-Agent"
}

/**
 * 把 [HeroImageHeaders] 应用到 Coil 的每一条图片请求上。
 *
 * 放在**应用拦截器**位置（而不是网络拦截器）：这样后续 catvod 的 `ResponseInterceptor`
 * 仍能按 host 用接口配置里的全局 headers 覆盖同名头，优先级与原版 Glide 链路完全一致。
 */
internal object HeroImageHeaderInterceptor : Interceptor {

    /**
     * 原版 Glide 链路**真的带 UA**，但**不是**伪造的 Chrome UA。
     *
     * `ImgUtil.getUrl()` 无论有没有 `@xxx=` 指令，最后都会把地址包成
     * `GlideUrl(url, LazyHeaders.Builder().build())`；而 Glide 的 `LazyHeaders` 默认头里就有
     * `User-Agent = System.getProperty("http.agent")`（反编译 `LazyHeaders$Builder` 可见
     * `User-Agent` + `http.agent` 两个常量），也就是 Android 系统的
     * `Dalvik/2.1.0 (Linux; U; Android xx; ...)`。
     *
     * Coil 走的是裸 OkHttp，**一个 UA 都不发**。图床若按 UA 做风控，
     * 就正好是「原版能出图、HeroUI 不出图」。这里补的是原版那个系统 UA，不是编造的浏览器 UA。
     */
    private val androidUserAgent: String by lazy {
        System.getProperty("http.agent") ?: "Dalvik/2.1.0 (Linux; U; Android)"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val current = request.url.toString()
        val resolved = HeroImageHeaders.resolve(current)
        val builder = request.newBuilder()
        if (resolved != null) {
            if (resolved.url != current) {
                val url = resolved.url.toHttpUrlOrNull() ?: return chain.proceed(request)
                builder.url(url)
            }
            resolved.headers.forEach { (name, value) -> builder.header(name, value) }
        }
        // 站点自己给了 UA（`@User-Agent=` 指令，或接口配置的全局 headers）就绝不覆盖：
        // 那才是站点真正想要的值，系统 UA 只是兜底。`ResponseInterceptor` 在网络层仍可再覆盖。
        if (request.header("User-Agent") == null) builder.header("User-Agent", androidUserAgent)
        return chain.proceed(builder.build())
    }
}
