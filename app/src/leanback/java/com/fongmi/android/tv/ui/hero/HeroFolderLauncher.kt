package com.fongmi.android.tv.ui.hero

import android.app.Activity
import com.fongmi.android.tv.bean.Result
import com.fongmi.android.tv.bean.Vod
import com.fongmi.android.tv.ui.activity.VodActivity

/**
 * TV 端 folder 条目的落点：原版 `CollectActivity.onItemClick` 就是
 * `VodActivity.start(activity, siteKey, Result.folder(item))`，分类页再由 `FolderFragment` 展开目录。
 */
internal fun openHeroFolder(activity: Activity, siteKey: String, vod: Vod) {
    VodActivity.start(activity, siteKey, Result.folder(vod))
}
