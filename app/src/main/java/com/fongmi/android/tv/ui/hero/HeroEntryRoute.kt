package com.fongmi.android.tv.ui.hero

import android.app.Activity
import com.fongmi.android.tv.bean.Site
import com.fongmi.android.tv.bean.Vod
import com.fongmi.android.tv.ui.activity.HeroDetailActivity

/**
 * 海报卡 / 搜索结果 / 换源结果的统一入口：**folder 条目绝不能进详情页**。
 *
 * 原版对 folder 的处理在点击处，不在 `VideoActivity.checkId()` 里：
 * - `CollectActivity.onItemClick`：`if (item.isFolder()) VodActivity.start(..., Result.folder(item))`
 * - `TypeFragment.onItemClick`：`else if (item.isFolder()) getParent().openFolder(item.getId(), ...)`
 *
 * 网盘聚合插件的 `atvp_detail:` 就是这种「folder 壳」：正确动作是用分类接口 `t=` 展开目录，
 * 而不是拿它去请求 `ids=` 详情（站源会判成业务错误，返回空 id / 0 线路）。
 * 搜索结果 / 历史记录可能丢掉 `vod_tag=folder`，所以这里连前缀一起认。
 */
internal fun openHeroDetailOrFolder(activity: Activity, vod: Vod, site: Site) {
    if (vod.isFolder || vod.id.startsWith(ATVP_FOLDER_PREFIX, ignoreCase = true)) {
        openHeroFolder(activity, site.key, vod)
    } else {
        HeroDetailActivity.start(activity, vod, site)
    }
}

/** 网盘聚合插件的 folder 壳前缀（与 `Vod.isFolder()` 的 `vod_tag=folder` 等价）。 */
private const val ATVP_FOLDER_PREFIX = "atvp_detail:"
