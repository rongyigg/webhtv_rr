package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.TmdbConfig;
import com.fongmi.android.tv.bean.TmdbItem;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.bean.Word;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.service.TmdbService;
import com.fongmi.android.tv.ui.helper.TmdbMatcher;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.SearchSuggest;
import com.github.catvod.net.OkHttp;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Response;

/**
 * 手动 TMDB 匹配弹窗（纯 View 实现，禁止 Compose）。
 *
 * <p>版式与行为 100% 对齐逆向 {@code dialog_tmdb_match.xml} / {@code TmdbMatchDialog(pc3)}：
 * <ul>
 *     <li>顶部联想词：{@link #buildSuggestions(String)} 完整复刻 {@code k0()} 的正则清洗链；</li>
 *     <li>左侧 217dp：输入框 + 放大镜 + 6 列虚拟键盘（{@link TmdbKeyboardAdapter}）；</li>
 *     <li>右侧：210dp 高的海报结果区，数据来自 {@code TmdbMatcher.search(keyword, vod)}；</li>
 *     <li>焦点：打开即停在输入框；输入框↓进键盘第一排；键盘最右列→右侧海报；
 *         海报←回放大镜、海报↑回键盘（{@code g0()} 的 {@code setNextFocusLeftId(R.id.searchBtn)}）。</li>
 * </ul>
 *
 * <p><b>输入框永不弹系统键盘</b>：布局里 {@code showSoftInputOnFocus=false}，
 * 窗口再加 {@link WindowManager.LayoutParams#FLAG_ALT_FOCUSABLE_IM} 并强制隐藏输入法。
 */
public class TmdbMatchViewDialog {

    public interface Listener {

        void onItemClick(TmdbItem item);
    }

    /** 关闭回调（取消 / 确认都会走），调用方用它把焦点还给原来的海报卡片。 */
    public interface DismissListener {

        void onDismiss();
    }

    private static final String TAG = "TmdbMatchViewDialog";
    private static final int KEYWORD_MAX_LENGTH = 40;
    private static final int SEARCH_COLUMNS = 6;
    private static final int RESULT_CARD_SCALE_DP = 5;
    /** 顶部联想词最多展示条数（本地 k0 结果置顶，其余给在线建议）。 */
    private static final int SUGGEST_LIMIT = 10;
    /** 输入停顿多久再去拉在线建议，避免每个字母打一次网络请求。 */
    private static final long SUGGEST_DEBOUNCE_MS = 260;
    private static final Pattern YEAR_PATTERN = Pattern.compile("(19|20)\\d{2}");
    private static final Pattern SEASON_PATTERN = Pattern.compile("第?\\s*([一二三四五六七八九十百0-9]+)\\s*季|(?i)\\bseason\\s*(\\d{1,2})\\b|(?i)\\bs(\\d{1,2})\\b");

    private final Activity activity;
    private final List<String> suggestions = new ArrayList<>();
    /** k0() 清洗出的本地候选（置顶，永远先于在线建议）。 */
    private final List<String> localSuggestions = new ArrayList<>();
    private final List<Word.Data> iqiyiWords = new ArrayList<>();
    private final List<Word.Data> tencentWords = new ArrayList<>();
    private final Handler suggestHandler = new Handler(Looper.getMainLooper());
    private final List<TmdbItem> results = new ArrayList<>();

    private AlertDialog dialog;
    private EditText keyword;
    private AppCompatImageView searchBtn;
    private RecyclerView suggestionView;
    private RecyclerView keyboard;
    private RecyclerView candidate;
    private TextView resultCount;
    private TextView emptyView;
    private TextView seasonTag;
    private TmdbKeyboardAdapter keyboardAdapter;
    private SuggestionAdapter suggestionAdapter;
    private CandidateAdapter candidateAdapter;
    private Vod vod;
    private String initialKeyword;
    private Listener listener;
    private DismissListener dismissListener;
    private Runnable pendingSuggest;
    private int suggestSeq;
    private boolean searching;

    public static TmdbMatchViewDialog create(Activity activity) {
        return new TmdbMatchViewDialog(activity);
    }

    public TmdbMatchViewDialog(Activity activity) {
        this.activity = activity;
    }

    public TmdbMatchViewDialog vod(Vod vod) {
        this.vod = vod;
        return this;
    }

    public TmdbMatchViewDialog keyword(String keyword) {
        this.initialKeyword = keyword;
        return this;
    }

    public TmdbMatchViewDialog listener(Listener listener) {
        this.listener = listener;
        return this;
    }

    public TmdbMatchViewDialog dismissListener(DismissListener dismissListener) {
        this.dismissListener = dismissListener;
        return this;
    }

    public void show() {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        View root = LayoutInflater.from(activity).inflate(R.layout.dialog_tmdb_match_view, null, false);
        bindViews(root);
        dialog = new MaterialAlertDialogBuilder(activity, R.style.Theme_WebHTV_LightDialog).setView(root).create();
        dialog.setOnDismissListener(item -> {
            // 关窗即作废在途的联想词请求，避免回调打到已销毁的 View 上。
            suggestSeq++;
            if (pendingSuggest != null) suggestHandler.removeCallbacks(pendingSuggest);
            if (dismissListener != null) dismissListener.onDismiss();
        });
        dialog.show();
        configureWindow();
        setupSuggestions();
        setupKeyboard();
        setupCandidate();
        setupKeyword();
        setupSeasonTag();
        keyword.post(() -> {
            if (keyword == null) return;
            keyword.requestFocus();
            if (keyword.getText() != null && keyword.getText().length() > 0) search();
        });
    }

    public void dismiss() {
        if (dialog != null) dialog.dismiss();
    }

    private void bindViews(View root) {
        keyword = root.findViewById(R.id.keyword);
        searchBtn = root.findViewById(R.id.searchBtn);
        suggestionView = root.findViewById(R.id.suggestion);
        keyboard = root.findViewById(R.id.keyboard);
        candidate = root.findViewById(R.id.candidate);
        resultCount = root.findViewById(R.id.resultCount);
        emptyView = root.findViewById(R.id.empty);
        seasonTag = root.findViewById(R.id.seasonTag);
    }

    private void configureWindow() {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        // 弹窗外围保持原样明亮：彻底关掉全屏遮罩 —— 不透明度归零，并移除 DIM_BEHIND。
        // 弹窗自身的可读性由 @drawable/shape_tmdb_match_shell（#F2222222）负责垫底。
        window.setDimAmount(0f);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        // 虚拟键盘由页面自己实现，窗口必须屏蔽系统输入法。
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int preferred = Math.round(metrics.widthPixels * 0.82f);
        int available = Math.max(1, metrics.widthPixels - dp(48));
        window.setLayout(Math.max(dp(320), Math.min(preferred, available)), WindowManager.LayoutParams.WRAP_CONTENT);
    }

    // ------------------------------------------------------------------ 顶部联想词（k0）

    /**
     * 完整复刻逆向 {@code TmdbMatchDialog.k0()}` 的清洗链：去括号注释 → 季数 → 英文季集 →
     * 压制标记 → 完结/语言/花絮词 → 集数 → 年份 → 标点 → 合并空白。
     */
    static String cleanName(String name) {
        if (name == null) return "";
        return name
                .replaceAll("[（(【\\[][\\s\\S]*?[)）】\\]]", " ")
                .replaceAll("第?[一二三四五六七八九十百0-9]+\\s*季", " ")
                .replaceAll("(?i)\\b(?:s|season)\\s*\\d{1,2}\\b", " ")
                .replaceAll("(?i)\\b(4k|2160p|1080p|1080i|720p|hdtv|bluray|bd|web-dl|h26[45]|x26[45]|hevc|dts|ac3|aac|hdr|60fps|remux)\\b", " ")
                .replaceAll("(?i)(全集|完结季|最终季|终季|完结篇|完结|已完结|花絮|预告|台版|港版|国语|粤语|中字|修复版|未删减|精校版|CCTV\\d*)", " ")
                .replaceAll("(\\d{1,4}集|第\\d{1,4}集|更新至\\d+集|共\\d+集)", " ")
                .replaceAll("\\b(19|20)\\d{2}\\b", " ")
                .replaceAll("[：:·\\-_+.,、|/\\\\\"'`~！!@#=|{}&]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** 逆向 {@code f0()}：trim 后按完全相等去重。 */
    static void addSuggestion(List<String> target, String value) {
        if (target == null || value == null) return;
        String text = value.trim();
        if (TextUtils.isEmpty(text)) return;
        for (String item : target) if (item.equals(text)) return;
        target.add(text);
    }

    static List<String> buildSuggestionValues(String rawName) {
        List<String> values = new ArrayList<>();
        if (TextUtils.isEmpty(rawName)) return values;
        String cleaned = cleanName(rawName);
        if (TextUtils.isEmpty(cleaned)) cleaned = rawName.trim();
        addSuggestion(values, cleaned);
        Matcher yearMatcher = YEAR_PATTERN.matcher(rawName);
        if (yearMatcher.find() && !values.isEmpty()) addSuggestion(values, values.get(0) + " " + yearMatcher.group());
        if (values.isEmpty()) addSuggestion(values, rawName);
        return values;
    }

    /**
     * 本地 k0() 结果：清洗名 + 「清洗名 年份」。先本地出词，保证断网 / 输入第一帧也有内容。
     */
    private void buildSuggestions(String rawName) {
        localSuggestions.clear();
        localSuggestions.addAll(buildSuggestionValues(rawName));
        refreshSuggestions();
    }

    /**
     * 顶部胶囊 = 本地 k0() 结果（置顶）+ 搜索页同款在线建议。
     *
     * <p>在线部分直接复用搜索页 {@code SearchActivity.getSuggest()} 的那套数据源与解析：
     * {@link SearchSuggest#iqiyiUrl}/{@link SearchSuggest#tencentUrl} 发请求、
     * {@link SearchSuggest#parseIqiyi}/{@link SearchSuggest#parseTencent} 解析、
     * {@link SearchSuggest#merge} 合并去重。所以输入 {@code jjc} 也会出现「嫁金钗」这类拼音首字母结果。
     */
    private void refreshSuggestions() {
        if (suggestionAdapter == null) return;
        suggestions.clear();
        for (String value : localSuggestions) {
            if (suggestions.size() >= SUGGEST_LIMIT) break;
            addSuggestion(suggestions, value);
        }
        for (Word.Data data : SearchSuggest.merge(iqiyiWords, tencentWords)) {
            if (suggestions.size() >= SUGGEST_LIMIT) break;
            addSuggestion(suggestions, data.getTitle());
        }
        suggestionAdapter.notifyDataSetChanged();
        // 顶栏高度固定 36dp，这里隐藏 / 显示都不会改变弹窗高度。
        suggestionView.setVisibility(suggestions.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** 输入停顿 {@link #SUGGEST_DEBOUNCE_MS} 后拉一次在线建议；空串直接清空在线结果。 */
    private void scheduleSuggestions(String text) {
        if (pendingSuggest != null) suggestHandler.removeCallbacks(pendingSuggest);
        String query = text == null ? "" : text.trim();
        if (TextUtils.isEmpty(query)) {
            suggestSeq++;
            iqiyiWords.clear();
            tencentWords.clear();
            refreshSuggestions();
            return;
        }
        pendingSuggest = () -> requestSuggestions(query);
        suggestHandler.postDelayed(pendingSuggest, SUGGEST_DEBOUNCE_MS);
    }

    private void requestSuggestions(String query) {
        final int seq = ++suggestSeq;
        iqiyiWords.clear();
        tencentWords.clear();
        OkHttp.newCall(SearchSuggest.iqiyiUrl(query)).enqueue(suggestCallback(seq, false));
        OkHttp.newCall(SearchSuggest.tencentUrl(query)).enqueue(suggestCallback(seq, true));
    }

    private Callback suggestCallback(int seq, boolean tencent) {
        return new Callback() {

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String result = response.body() == null ? "" : response.body().string();
                if (TextUtils.isEmpty(result)) return;
                // 网络线程不许碰 View，统一回主线程渲染。
                App.post(() -> applySuggestions(seq, result, tencent));
            }
        };
    }

    private void applySuggestions(int seq, String result, boolean tencent) {
        if (seq != suggestSeq || dialog == null || !dialog.isShowing()) return;
        List<Word.Data> parsed = tencent ? SearchSuggest.parseTencent(result) : SearchSuggest.parseIqiyi(result);
        if (tencent) tencentWords.addAll(parsed);
        else iqiyiWords.addAll(parsed);
        refreshSuggestions();
    }

    private void setupSuggestions() {
        suggestionAdapter = new SuggestionAdapter();
        suggestionView.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false));
        suggestionView.setItemAnimator(null);
        suggestionView.setAdapter(suggestionAdapter);
        String name = vod == null ? "" : vod.getName();
        buildSuggestions(TextUtils.isEmpty(initialKeyword) ? name : initialKeyword);
    }

    // ------------------------------------------------------------------ 虚拟键盘

    private void setupKeyboard() {
        GridLayoutManager manager = new GridLayoutManager(activity, SEARCH_COLUMNS);
        keyboard.setLayoutManager(manager);
        keyboard.setItemAnimator(null);
        keyboard.addItemDecoration(new GridSpacing(dp(5)));
        keyboardAdapter = new TmdbKeyboardAdapter(new TmdbKeyboardAdapter.Listener() {

            @Override
            public void onText(String text) {
                insertText(text);
            }

            @Override
            public void onBackspace() {
                backspace();
            }

            @Override
            public void onMoveCaret(int delta) {
                moveCaret(delta);
            }

            @Override
            public void onConfirm() {
                search();
            }

            @Override
            public void onRequestResultFocus() {
                focusFirstResult();
            }

            @Override
            public void onRequestInputFocus() {
                if (keyword != null) keyword.requestFocus();
            }
        });
        keyboard.setAdapter(keyboardAdapter);
    }

    private void insertText(String text) {
        if (keyword == null || keyword.getText() == null || TextUtils.isEmpty(text)) return;
        int start = Math.min(keyword.getSelectionStart(), keyword.getSelectionEnd());
        int end = Math.max(keyword.getSelectionStart(), keyword.getSelectionEnd());
        start = Math.max(0, start);
        end = Math.max(0, end);
        Editable editable = keyword.getText();
        String merged = editable.toString().substring(0, start) + text + editable.toString().substring(end);
        if (merged.length() > KEYWORD_MAX_LENGTH) return;
        keyword.setText(merged);
        keyword.setSelection(Math.min(merged.length(), start + text.length()));
    }

    private void backspace() {
        if (keyword == null || keyword.getText() == null) return;
        int start = Math.min(keyword.getSelectionStart(), keyword.getSelectionEnd());
        int end = Math.max(keyword.getSelectionStart(), keyword.getSelectionEnd());
        start = Math.max(0, start);
        end = Math.max(0, end);
        String value = keyword.getText().toString();
        String merged;
        int caret;
        if (start != end) {
            merged = value.substring(0, start) + value.substring(end);
            caret = start;
        } else {
            if (start == 0) return;
            merged = value.substring(0, start - 1) + value.substring(start);
            caret = start - 1;
        }
        keyword.setText(merged);
        keyword.setSelection(Math.max(0, Math.min(merged.length(), caret)));
    }

    private void moveCaret(int delta) {
        if (keyword == null || keyword.getText() == null) return;
        int caret = keyword.getSelectionStart() + delta;
        caret = Math.max(0, Math.min(keyword.getText().length(), caret));
        keyword.setSelection(caret);
    }

    // ------------------------------------------------------------------ 输入框

    private void setupKeyword() {
        String value = TextUtils.isEmpty(initialKeyword) ? (vod == null ? "" : vod.getName()) : initialKeyword;
        if (value == null) value = "";
        keyword.setText(value);
        keyword.setSelection(value.length());
        keyword.setShowSoftInputOnFocus(false);
        keyword.addTextChangedListener(new TextWatcher() {

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                String text = editable == null ? "" : editable.toString();
                if (text.length() > KEYWORD_MAX_LENGTH) {
                    keyword.setText(text.substring(0, KEYWORD_MAX_LENGTH));
                    keyword.setSelection(KEYWORD_MAX_LENGTH);
                    return;
                }
                buildSuggestions(text);
                // 复用搜索页那套在线建议：停顿 260ms 拉一次，输入 jjc 也能出「嫁金钗」。
                scheduleSuggestions(text);
            }
        });
        keyword.setOnKeyListener((view, keyCode, event) -> {
            if (event == null || event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusKeyboard();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && isCaretAtEnd()) {
                searchBtn.requestFocus();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                search();
                return true;
            }
            return false;
        });
        searchBtn.setOnClickListener(view -> search());
        searchBtn.setOnLongClickListener(view -> {
            keyword.setText("");
            return true;
        });
    }

    private boolean isCaretAtEnd() {
        return keyword.getText() != null && keyword.getSelectionEnd() >= keyword.getText().length();
    }

    private void focusKeyboard() {
        keyboard.post(() -> {
            RecyclerView.ViewHolder holder = keyboard.findViewHolderForAdapterPosition(0);
            if (holder != null) holder.itemView.requestFocus();
            else keyboard.requestFocus();
        });
    }

    // ------------------------------------------------------------------ 右侧结果区

    private void setupCandidate() {
        candidateAdapter = new CandidateAdapter();
        candidate.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false));
        candidate.setItemAnimator(null);
        // 卡片间距 12dp：卡片 108dp + 间距 12dp = 每张占 120dp。
        // 960dp 逻辑宽度下右侧可视区约 512dp → 4×120 = 480，正好完整显示 4 张，
        // 第 5 张从 480dp 开始，只露出 32dp ≈ 三分之一张（卡片自带 4dp 内边距，视觉间隙实为 20dp）。
        candidate.addItemDecoration(new CandidateSpacing(dp(12)));
        candidate.setAdapter(candidateAdapter);
        // 严格照抄逆向 g0()：每张海报卡片的左焦点固定回放大镜按钮。
        candidate.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {

            @Override
            public void onChildViewAttachedToWindow(@NonNull View view) {
                view.setNextFocusLeftId(R.id.searchBtn);
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View view) {
            }
        });
    }

    private void focusFirstResult() {
        if (results.isEmpty()) return;
        candidate.post(() -> {
            RecyclerView.ViewHolder holder = candidate.findViewHolderForAdapterPosition(0);
            if (holder != null) holder.itemView.requestFocus();
            else candidate.requestFocus();
        });
    }

    private void renderResults() {
        resultCount.setText(String.valueOf(results.size()));
        emptyView.setVisibility(results.isEmpty() ? View.VISIBLE : View.GONE);
        if (emptyView.getVisibility() == View.VISIBLE && TextUtils.isEmpty(emptyView.getText())) {
            emptyView.setText(R.string.home_tmdb_match_empty);
        }
        candidateAdapter.notifyDataSetChanged();
        if (!results.isEmpty()) candidate.scrollToPosition(0);
    }

    private void search() {
        if (keyword == null) return;
        String query = keyword.getText() == null ? "" : keyword.getText().toString().trim();
        if (TextUtils.isEmpty(query) || searching) return;
        TmdbConfig config = TmdbConfig.effectiveCurrent();
        if (config == null || !config.isReady()) {
            results.clear();
            resultCount.setText("");
            emptyView.setText(R.string.detail_tmdb_need_key);
            emptyView.setVisibility(View.VISIBLE);
            candidateAdapter.notifyDataSetChanged();
            return;
        }
        searching = true;
        resultCount.setText(R.string.detail_tmdb_searching);
        final Vod sourceVod = vod;
        new Thread(() -> {
            List<TmdbItem> found = new ArrayList<>();
            try {
                found.addAll(new TmdbMatcher(new TmdbService(), config).search(query, sourceVod));
            } catch (Throwable e) {
                Log.w(TAG, "tmdb manual search failed: " + query, e);
            }
            final List<TmdbItem> values = found;
            if (activity.isFinishing() || activity.isDestroyed()) return;
            activity.runOnUiThread(() -> {
                searching = false;
                results.clear();
                results.addAll(values);
                emptyView.setText(R.string.home_tmdb_match_empty);
                renderResults();
            });
        }, "tmdb-match-search").start();
    }

    // ------------------------------------------------------------------ 季数标签

    private void setupSeasonTag() {
        if (vod == null) return;
        int season = extractSeason(vod.getName());
        if (season <= 0) return;
        seasonTag.setText(activity.getString(R.string.home_tmdb_match_season, season));
        seasonTag.setVisibility(View.VISIBLE);
    }

    static int extractSeason(String name) {
        if (TextUtils.isEmpty(name)) return 0;
        Matcher matcher = SEASON_PATTERN.matcher(name);
        while (matcher.find()) {
            for (int group = 1; group <= matcher.groupCount(); group++) {
                String value = matcher.group(group);
                if (TextUtils.isEmpty(value)) continue;
                int number = parseNumber(value);
                if (number > 0) return number;
            }
        }
        return 0;
    }

    private static int parseNumber(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
        }
        int result = 0;
        String digits = "零一二三四五六七八九";
        String units = "十百";
        int section = 0;
        int number = 0;
        for (char c : value.toCharArray()) {
            int digit = digits.indexOf(c);
            if (digit >= 0) {
                number = digit;
                continue;
            }
            int unit = units.indexOf(c);
            if (unit == 0) {
                section += (number == 0 ? 1 : number) * 10;
                number = 0;
            } else if (unit == 1) {
                section += (number == 0 ? 1 : number) * 100;
                number = 0;
            }
        }
        result = section + number;
        return result;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ 适配器

    private class SuggestionAdapter extends RecyclerView.Adapter<SuggestionAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.adapter_tmdb_suggestion, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            String value = suggestions.get(position);
            holder.text.setText(value);
            holder.text.setOnClickListener(view -> {
                keyword.setText(value);
                keyword.setSelection(value.length());
                search();
            });
        }

        @Override
        public int getItemCount() {
            return suggestions.size();
        }

        class Holder extends RecyclerView.ViewHolder {

            final TextView text;

            Holder(@NonNull View itemView) {
                super(itemView);
                text = itemView.findViewById(R.id.text);
            }
        }
    }

    private class CandidateAdapter extends RecyclerView.Adapter<CandidateAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.adapter_tmdb_match_card, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            TmdbItem item = results.get(position);
            holder.title.setText(item.getTitle());
            holder.meta.setText(item.getSubtitle());
            ImgUtil.load(item.getTitle(), item.getPosterUrl(), holder.image);
        }

        @Override
        public int getItemCount() {
            return results.size();
        }

        class Holder extends RecyclerView.ViewHolder {

            final ImageView image;
            final TextView title;
            final TextView meta;

            Holder(@NonNull View itemView) {
                super(itemView);
                image = itemView.findViewById(R.id.image);
                title = itemView.findViewById(R.id.title);
                meta = itemView.findViewById(R.id.meta);
                itemView.setOnClickListener(view -> {
                    int position = getBindingAdapterPosition();
                    if (position == RecyclerView.NO_POSITION || position >= results.size()) return;
                    if (listener != null) listener.onItemClick(results.get(position));
                    dismiss();
                });
                itemView.setOnFocusChangeListener((view, hasFocus) -> {
                    view.animate()
                            .scaleX(hasFocus ? 1.08f : 1f)
                            .scaleY(hasFocus ? 1.08f : 1f)
                            .translationZ(hasFocus ? dp(RESULT_CARD_SCALE_DP) : 0)
                            .setDuration(120)
                            .start();
                });
            }
        }
    }

    /** 6 列键盘的格子间距：右 5dp、下 5dp，最后一列不加右边距，保证 30dp 方键不错位。 */
    private static class GridSpacing extends RecyclerView.ItemDecoration {

        private final int space;

        GridSpacing(int space) {
            this.space = space;
        }

        @Override
        public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) view.getLayoutParams();
            int column = params instanceof GridLayoutManager.LayoutParams grid ? grid.getSpanIndex() : 0;
            outRect.set(0, 0, column == SEARCH_COLUMNS - 1 ? 0 : space, space);
        }
    }

    /**
     * 右侧海报列表的卡片间距：只在每张卡片右边留 {@code space}，最后一张不留，
     * 这样 4 张完整卡片 + 第 5 张露边的手感完全由固定步长决定，不会随最后一张多出一截空白。
     */
    private static class CandidateSpacing extends RecyclerView.ItemDecoration {

        private final int space;

        CandidateSpacing(int space) {
            this.space = space;
        }

        @Override
        public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            int position = parent.getChildAdapterPosition(view);
            int last = state.getItemCount() - 1;
            outRect.set(0, 0, position == last ? 0 : space, 0);
        }
    }
}
