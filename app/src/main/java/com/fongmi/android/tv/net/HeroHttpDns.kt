package com.fongmi.android.tv.net

import com.github.catvod.net.OkHttp
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 多路 DoH 并发解析，**只用于 TMDB 图床**（`*.tmdb.org`）的防污染直连。
 *
 * 与 catvod 既有的 [com.github.catvod.net.OkDns] 相比，这里同时对多个公共 DoH 发起查询，
 * 谁先返回有效结果就采用谁；全部失败或超时后回落到 [OkHttp.dns]。
 * 成功结果按 [TTL_MS] 缓存，避免每次图片请求都重新解析。
 *
 * 非 TMDB 域名直接转交 [OkHttp.dns]，保证接口配置的 `hosts` 映射与用户自选 DoH 仍然生效 ——
 * 这是「原版能出图、HeroUI 出不来」最容易被忽略的一环。
 *
 * 目前只接入 HeroUI 的 Coil 图片链路；全局 OkHttp 仍走 catvod 的 [com.github.catvod.net.OkDns]
 * （单路 DoH），要替换全局解析必须修改 catvod 核心层，留待单独评审后再做。
 */
object HeroHttpDns : Dns {

    private const val TTL_MS = 10 * 60 * 1000L
    private const val RACE_TIMEOUT_MS = 2000L

    private val cache = ConcurrentHashMap<String, Entry>()

    private val resolvers: List<DnsOverHttps> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { buildResolvers() }

    private val executor: ExecutorService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Executors.newFixedThreadPool(resolvers.size.coerceAtLeast(1)) { runnable ->
            Thread(runnable, "hero-doh").apply { isDaemon = true }
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        // 【兼容性红线】只有 TMDB 图床走多路 DoH，其余域名一律交回 catvod 的 [OkHttp.dns]。
        // 接口配置里的 `hosts` 映射（奇葩源常用：把图片/接口域名钉到指定 IP）和用户自选的 DoH
        // 都只在 OkDns 里生效；如果对所有域名都强上 DoH，就会绕过它们，出现「原版有图、我们没有」。
        if (!needsDoh(hostname)) return OkHttp.dns().lookup(hostname)
        val cached = cache[hostname]
        if (cached != null && cached.expireAt > System.currentTimeMillis()) return cached.addresses
        val raced = race(hostname)
        if (raced.isNotEmpty()) {
            cache[hostname] = Entry(raced, System.currentTimeMillis() + TTL_MS)
            return raced
        }
        return OkHttp.dns().lookup(hostname)
    }

    /** TMDB 官方图床：`image.tmdb.org` / `images.tmdb.org`，以及用户自定义的其它 `*.tmdb.org`。 */
    private fun needsDoh(hostname: String): Boolean {
        val host = hostname.lowercase()
        return host == "tmdb.org" || host.endsWith(".tmdb.org")
    }

    private fun race(hostname: String): List<InetAddress> {
        val targets = resolvers
        if (targets.isEmpty()) return emptyList()
        val tasks: List<Callable<List<InetAddress>>> = targets.map { resolver ->
            Callable { resolver.lookup(hostname) }
        }
        return try {
            executor.invokeAny(tasks, RACE_TIMEOUT_MS, TimeUnit.MILLISECONDS).orEmpty()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun buildResolvers(): List<DnsOverHttps> {
        // 独立的引导客户端：它只负责访问 DoH 服务本身，绝不回落到本对象的 lookup，避免递归解析。
        val bootstrap = OkHttpClient.Builder()
            .connectTimeout(RACE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(RACE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        return PROVIDERS.mapNotNull { provider -> resolver(bootstrap, provider.first, provider.second) }
    }

    private fun resolver(bootstrap: OkHttpClient, host: String, ips: List<String>): DnsOverHttps? {
        val url = "https://$host/dns-query".toHttpUrlOrNull() ?: return null
        val addresses = ips.mapNotNull { ip -> runCatching { InetAddress.getByName(ip) }.getOrNull() }
        if (addresses.isEmpty()) return null
        return DnsOverHttps.Builder()
            .client(bootstrap)
            .url(url)
            .bootstrapDnsHosts(addresses)
            .build()
    }

    private data class Entry(val addresses: List<InetAddress>, val expireAt: Long)

    private val PROVIDERS = listOf(
        "dns.alidns.com" to listOf("223.5.5.5", "223.6.6.6"),
        "doh.pub" to listOf("1.12.12.12", "120.53.53.53"),
        "dns.google" to listOf("8.8.8.8", "8.8.4.4"),
    )
}
