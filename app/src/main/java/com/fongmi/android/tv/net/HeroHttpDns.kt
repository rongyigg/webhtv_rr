package com.fongmi.android.tv.net

import com.fongmi.android.tv.setting.Setting
import com.github.catvod.net.OkHttp
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 多路解析竞速，**只用于 TMDB 域名**（`tmdb.org` / `themoviedb.org`）的防污染直连。
 *
 * 严格对标原版 `defpackage/x52`（`TmdbDns`），顺序逐条一致：
 * 1. **先查接口配置的 `hosts` 映射**（`OkHttp.dns().target(...)`，只读表不发请求）；
 * 2. 映射到纯 IP → 直接返回（系统解析对 IP 字面量不发查询）；
 * 3. **用户自选 DoH 优先**（设置页 / 接口配置里的 `doh`）：配了就用它解析；
 * 4. 其余 TMDB 域名：系统 DNS + [PROVIDERS] 里的每一路 DoH **同时**开跑，
 *    用 [CountDownLatch] 等 [RACE_TIMEOUT_MS]，**取最先返回的非空结果**；
 * 5. 全部超时则回落系统 DNS（原版同样行为），再不行才交给 [OkHttp.dns]。
 * 成功结果按 [TTL_MS] 缓存，避免每次图片请求都重新解析（原版不缓存，这里更强）。
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
        // 缓存线程池（而非固定 3 个线程）：一个 lookup 最长要占 2s，固定池会让并发的图片请求
        // 排在它后面一起等满 2s；这里每次查询各开线程，空闲 60s 自动回收，全部是 daemon。
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "hero-doh").apply { isDaemon = true }
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        // 【原版第一步】先查接口配置里的 `hosts` 映射（`TmdbDns.lookup` 开头就是这张表）。
        // 只读表、不发请求：命中说明用户明确把域名钉到了某个地址，竞速绝不能覆盖它。
        val target = runCatching { OkHttp.dns().target(hostname) }.getOrDefault(hostname)
        // 纯 IP（映射到 IP，或本来就是个 IP）：系统解析对 IP 字面量不发查询，直接返回。
        if (IPV4.matches(target)) return Dns.SYSTEM.lookup(target)
        // 【兼容性红线】只有 TMDB 域名走多路竞速，其余域名一律交回 catvod 的 [OkHttp.dns]。
        // 接口配置里的 `hosts` 映射（奇葩源常用：把图片/接口域名钉到指定 IP）和用户自选的 DoH
        // 都只在 OkDns 里生效；如果对所有域名都强上 DoH，就会绕过它们，出现「原版有图、我们没有」。
        if (!needsDoh(target)) return OkHttp.dns().lookup(hostname)
        // 【原版第二步】用户自选 DoH 优先（`TmdbDns.d`）：它自己带 hosts 映射，成功即用。
        // 比原版多一层兜底 —— 解析失败 / 返回空时继续走竞速，不会因为一个坏 DoH 把 TMDB 图全卡死。
        userDoh(hostname)?.let { return it }
        val cached = cache[target]
        if (cached != null && cached.expireAt > System.currentTimeMillis()) return cached.addresses
        val raced = race(target)
        if (raced.isNotEmpty()) {
            cache[target] = Entry(raced, System.currentTimeMillis() + TTL_MS)
            return raced
        }
        // 原版行为：全部超时 → 回落系统 DNS；系统 DNS 也解析不出来才交给 catvod
        // （那里还有 hosts 映射与用户自选 DoH 兜底）。
        return runCatching { Dns.SYSTEM.lookup(target) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: OkHttp.dns().lookup(hostname)
    }

    /**
     * 用户自选的 DoH（设置页 / 接口配置里的 `doh`）。
     *
     * 对齐原版 `TmdbDns.a(vf0)`：只要用户配了，就用它解析，连 TMDB 域名也优先走它。
     * 未配置时返回 null（直接进入三路竞速）。
     */
    private fun userDoh(hostname: String): List<InetAddress>? {
        if (Setting.getDoh().isNullOrBlank()) return null
        return runCatching { OkHttp.dns().lookup(hostname) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    /** 原版 `str2.matches("^[0-9.]+$")`：IPv4 字面量。 */
    private val IPV4 = Regex("^[0-9.]+$")

    /**
     * TMDB 官方域名：图床 `image.tmdb.org` / `images.tmdb.org`、接口 `api.themoviedb.org`，
     * 以及用户自定义的其它 `*.tmdb.org`。
     */
    private fun needsDoh(hostname: String): Boolean {
        val host = hostname.lowercase()
        return host == "tmdb.org" || host.endsWith(".tmdb.org") ||
            host == "themoviedb.org" || host.endsWith(".themoviedb.org")
    }

    /**
     * 原版 `TmdbDns` 的核心：**系统 DNS 与两路 DoH 同时发**，[CountDownLatch] 等 2s，
     * 第一个拿到的非空结果直接胜出并唤醒调用方；全部超时返回空列表（由 [lookup] 回落系统 DNS）。
     *
     * 用 [CountDownLatch] 而不是 `invokeAny`：`invokeAny` 只等第一个**成功返回**的任务，
     * 空结果也算成功、会立刻结束竞速；这里要的是「第一个**非空**结果」，空结果必须被忽略。
     */
    private fun race(hostname: String): List<InetAddress> {
        val tasks = ArrayList<Callable<List<InetAddress>>>(resolvers.size + 1)
        tasks += Callable { Dns.SYSTEM.lookup(hostname) }
        resolvers.forEach { resolver -> tasks += Callable { resolver.lookup(hostname) } }

        val winner = AtomicReference<List<InetAddress>?>(null)
        val latch = CountDownLatch(1)
        val futures = tasks.map { task ->
            // 显式 Runnable：`submit { }` 在 Runnable / Callable 两个重载之间会有二义性。
            executor.submit(Runnable {
                // 已经有结果时直接放弃：后面排队的解析不再浪费一次 DNS 往返。
                if (winner.get() != null) return@Runnable
                val result = runCatching { task.call() }.getOrNull().orEmpty()
                if (result.isNotEmpty() && winner.compareAndSet(null, result)) latch.countDown()
            })
        }
        return try {
            latch.await(RACE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            winner.get().orEmpty()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            emptyList()
        } finally {
            // 2s 是硬上限：落败 / 卡住的 DoH 请求直接打断，别占着线程拖住后面的图片请求。
            // `Future.cancel(true)` 不保证立刻中断底层 OkHttp 调用，但能让还在排队的任务不再开跑。
            futures.forEach { it.cancel(true) }
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

    /**
     * 两路 DoH，与原版 `TmdbDns` 的两路一致：阿里 + 腾讯。
     *
     * 不把 `dns.google` 之类加进来：国内网络下它经常直接超时，多一路只是多占一个线程 / 一条
     * TLS 连接，并不会提高「最先拿到结果」的概率。
     */
    private val PROVIDERS = listOf(
        "dns.alidns.com" to listOf("223.5.5.5", "223.6.6.6"),
        "doh.pub" to listOf("1.12.12.12", "120.53.53.53"),
    )
}
