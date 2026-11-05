package com.fongmi.android.tv.net

import android.net.Uri
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
            return if (referer.isEmpty()) null else Resolved(raw, mapOf(HEADER_REFERER to referer))
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

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val current = request.url.toString()
        val resolved = HeroImageHeaders.resolve(current) ?: return chain.proceed(request)
        val builder = request.newBuilder()
        if (resolved.url != current) {
            val url = resolved.url.toHttpUrlOrNull() ?: return chain.proceed(request)
            builder.url(url)
        }
        resolved.headers.forEach { (name, value) -> builder.header(name, value) }
        return chain.proceed(builder.build())
    }
}
