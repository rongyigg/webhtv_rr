package com.fongmi.android.tv.ui.hero

import com.fongmi.android.tv.bean.Site

/**
 * 接口源侧边栏条目：一条**真实的** [Site]。
 *
 * 第二阶段起不再有任何假数据：[SourceSidebar] 与 [ChangeSourceDialog] 直接吃 [Site]，
 * 选中后回调给调用方的也是同一个对象，因此「换源 → 拿到 Vod + Site」全程不丢身份。
 *
 * 为什么包一层而不是直接用 `List<Site>`：
 * - 侧边栏只需要「稳定 key + 显示名」两件事，包一层后适配器不用关心 [Site] 的其它字段；
 * - 以后要加「源健康度角标」「是否当前源」这类展示字段，只改这里，不动两个弹窗。
 */
internal data class HeroSourceEntry(val site: Site) {

    /** 稳定标识：`Site.getKey()`，与 `Vod.getSiteKey()` 同一套取值，用于排除当前源 / 坏源。 */
    val key: String get() = site.key

    /** 显示名：`Site.getDisplayName()`（尊重用户在站点管理里的改名，不是裸 `getName()`）。 */
    val label: String get() = site.displayName

    companion object {

        /** 批量包装，供首页小房子与换源弹窗共用。 */
        fun of(sites: List<Site>): List<HeroSourceEntry> = sites.map(::HeroSourceEntry)
    }
}
