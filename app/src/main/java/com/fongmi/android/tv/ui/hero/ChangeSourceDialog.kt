package com.fongmi.android.tv.ui.hero

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fongmi.android.tv.R

/**
 * 换源弹窗（View 体系，独立 Dialog 窗口，不使用 Compose 外壳）。
 *
 * 结构直接 inflate `R.layout.dialog_source`（逆向 `ref_docs/dialog_source.xml` 的最小 diff 版）：
 * - 背景 `shape_dialog_source`：12dp 圆角 + #f214171f，内边距 18dp / paddingBottom 0。
 * - 徽章 `shape_tmdb_badge`：4dp 圆角 + #e50914，文字 `@string/play_change`。
 * - 站点栏：26dp 小房子（`selector_home_logo_source` + 16dp `ic_home_logo`）+ 横向站点标签。
 * - 结果区：横向 RecyclerView（架构约束：main 源集不能引用 Leanback，用横向 LinearLayoutManager 平替）。
 * - 三态：progress / empty / recycler 与逆向布局一致。
 *
 * 焦点：
 * - 默认落在当前站点标签；↓ 进结果卡片；↑ 回站点栏。
 * - 小房子与站点栏左右互通；小房子确定键拉起全局 [SourceSidebar]，层级在弹窗之上。
 * - 关闭后通过 [onClosed] 把焦点交还给详情页「换源」按钮。
 *
 * 组件内零网络、零数据层写入；数据全部由外部传入。
 * TODO(数据层对接)：站点切换后的结果刷新、以及确定键的换源写库由调用方实现。
 */
internal class ChangeSourceDialog private constructor(
    context: Context,
    private val sites: List<String>,
    currentSite: Int,
    private val results: List<HeroSourceResult>,
    private val total: Int,
    private val onSourceSelected: (HeroSourceResult) -> Unit,
    private val onClosed: () -> Unit,
) : Dialog(context, R.style.SourceDialogTheme) {

    private var selectedSite: Int = currentSite.coerceIn(0, (sites.size - 1).coerceAtLeast(0))

    private lateinit var homeBtn: FrameLayout
    private lateinit var siteRecycler: RecyclerView
    private lateinit var resultRecycler: RecyclerView
    private lateinit var empty: TextView
    private lateinit var siteAdapter: SiteAdapter
    private lateinit var resultAdapter: ResultAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_source)
        setupWindow()
        bindHeader()
        bindHome()
        bindSiteList()
        bindResultList()
        setOnDismissListener { onClosed() }
    }

    override fun onStart() {
        super.onStart()
        siteRecycler.post {
            val holder = siteRecycler.findViewHolderForAdapterPosition(selectedSite)
            if (holder != null && holder.itemView.requestFocus()) return@post
            siteRecycler.requestFocus()
        }
    }

    private fun setupWindow() {
        val metrics = context.resources.displayMetrics
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((metrics.widthPixels * WIDTH_FRACTION).toInt(), (metrics.heightPixels * HEIGHT_FRACTION).toInt())
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(DIM_AMOUNT)
        }
    }

    private fun bindHeader() {
        findViewById<TextView>(R.id.badge).setText(R.string.play_change)
        findViewById<TextView>(R.id.resultCount).text = context.getString(R.string.play_change_count, total)
        // 关键词行按定稿保持 gone，只保留 id。
        findViewById<View>(R.id.keywordRow).visibility = View.GONE
    }

    private fun bindHome() {
        homeBtn = findViewById(R.id.homeBtn)
        homeBtn.setOnClickListener { openSidebar() }
    }

    private fun bindSiteList() {
        siteRecycler = findViewById(R.id.siteRecycler)
        siteRecycler.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        siteRecycler.itemAnimator = null
        siteAdapter = SiteAdapter()
        siteRecycler.adapter = siteAdapter
    }

    private fun bindResultList() {
        resultRecycler = findViewById(R.id.recycler)
        empty = findViewById(R.id.empty)
        resultRecycler.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        resultRecycler.itemAnimator = null
        resultAdapter = ResultAdapter()
        resultRecycler.adapter = resultAdapter
        recycleState()
    }

    private fun recycleState() {
        val hasResult = results.isNotEmpty()
        findViewById<View>(R.id.progress).visibility = View.GONE
        resultRecycler.visibility = if (hasResult) View.VISIBLE else View.GONE
        empty.visibility = if (hasResult) View.GONE else View.VISIBLE
    }

    private fun selectSite(position: Int) {
        if (position == selectedSite) return
        selectedSite = position
        siteAdapter.notifyDataSetChanged()
        refreshResults()
    }

    /** TODO(数据层对接)：按选中站点重新检索；假数据阶段只重排同一批卡片。 */
    private fun refreshResults() {
        resultAdapter.notifyDataSetChanged()
        recycleState()
    }

    /** 弹窗内小房子：拉起全局接口源侧边栏，层级盖在换源弹窗之上。 */
    private fun openSidebar() {
        SourceSidebar.show(
            context = context,
            entries = HERO_SOURCE_ENTRIES,
            initialChecked = HERO_SOURCE_INITIAL_CHECKED,
            onClosed = { homeBtn.post { homeBtn.requestFocus() } },
        )
    }

    private inner class SiteAdapter : RecyclerView.Adapter<SiteAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = layoutInflater.inflate(R.layout.adapter_source_site, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = sites.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val itemView = holder.itemView
            holder.name.text = sites[position]
            itemView.isSelected = position == selectedSite
            // 第一个站点标签向左回到小房子，其余保持列表内滚动。
            itemView.nextFocusLeftId = if (position == 0) R.id.homeBtn else View.NO_ID
            itemView.setOnClickListener {
                selectSite(holder.bindingAdapterPosition)
            }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.sourceSiteName)
        }
    }

    private inner class ResultAdapter : RecyclerView.Adapter<ResultAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = layoutInflater.inflate(R.layout.adapter_source_result, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = results.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val result = results[position]
            holder.name.text = "${result.site} · ${result.date}"
            // TODO(数据层对接)：真实数据接入后在这里加载 poster。
            holder.itemView.setOnClickListener {
                onSourceSelected(result)
                dismiss()
            }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.name)
        }
    }

    companion object {

        private const val WIDTH_FRACTION = 0.82f
        private const val HEIGHT_FRACTION = 0.78f
        private const val DIM_AMOUNT = 0.6f

        /**
         * 打开换源弹窗。
         *
         * @param sites 站点栏标签，顺序即显示顺序。
         * @param currentSite 当前站点下标，越界时收敛到 0。
         * @param results 换源结果卡片。
         * @param total 顶部统计数字（与卡片数量解耦）；默认等于结果数量。
         * @param onSourceSelected 结果卡片确定键回调。
         * @param onClosed 关闭回调，调用方在这里把焦点交还给详情页「换源」按钮。
         */
        fun show(
            context: Context,
            sites: List<String>,
            currentSite: Int,
            results: List<HeroSourceResult>,
            total: Int = results.size,
            onSourceSelected: (HeroSourceResult) -> Unit = {},
            onClosed: () -> Unit = {},
        ): ChangeSourceDialog {
            val dialog = ChangeSourceDialog(context, sites, currentSite, results, total, onSourceSelected, onClosed)
            dialog.show()
            return dialog
        }
    }
}
