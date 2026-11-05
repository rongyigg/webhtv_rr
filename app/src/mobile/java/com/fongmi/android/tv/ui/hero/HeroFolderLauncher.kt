package com.fongmi.android.tv.ui.hero

import android.app.Activity
import com.fongmi.android.tv.bean.Result
import com.fongmi.android.tv.bean.Vod
import com.fongmi.android.tv.ui.activity.FolderActivity

/**
 * 手机端 folder 条目的落点：原版 `CollectFragment` 是
 * `FolderActivity.start(activity, siteKey, Result.folder(item))`，由分类页展开目录。
 */
internal fun openHeroFolder(activity: Activity, siteKey: String, vod: Vod) {
    FolderActivity.start(activity, siteKey, Result.folder(vod))
}
