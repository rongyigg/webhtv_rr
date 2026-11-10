package com.fongmi.android.tv.ui.dialog;

import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;

/**
 * 手动 TMDB 匹配弹窗的虚拟键盘适配器（照抄逆向 {@code ub2.java} 的 TmdbKeyboardAdapter）。
 *
 * <p>键盘是 6 列 {@code GridLayoutManager}，共 30 个键 = 4 个功能键 + 26 个字母键，
 * 正好 5 行，和逆向 {@code dialog_tmdb_match.xml} 的 {@code app:spanCount="6"} 一致。
 *
 * <p>功能键顺序（逆向是「扫码 / 左移 / 右移 / 退格」，本工程没有扫码资源，换成「确认 / 左移 / 右移 / 退格」）：
 * <ul>
 *     <li>0 = 确认（等同放大镜按钮，触发 TMDB 搜索）</li>
 *     <li>1 = 光标左移</li>
 *     <li>2 = 光标右移</li>
 *     <li>3 = 退格</li>
 * </ul>
 *
 * <p>跨区焦点拦截（红线）：每行最后一个键（第 6 列）按右键时不交给 RecyclerView 默认焦点搜索，
 * 直接回调 {@link Listener#onRequestResultFocus()} 让弹窗把焦点送到右侧海报卡片；
 * 最后一行再按↓同理。这条规则与逆向「键盘最右列按右 → 结果区」完全一致。
 */
public class TmdbKeyboardAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface Listener {

        void onText(String text);

        void onBackspace();

        void onMoveCaret(int delta);

        void onConfirm();

        /** 键盘最右列按右 / 最后一行按下：焦点交给右侧海报卡片。 */
        void onRequestResultFocus();

        /** 第一排再按上：焦点回到输入框（对应逆向「键盘首行 ↑ 回输入框」）。 */
        void onRequestInputFocus();
    }

    public static final int SPAN_COUNT = 6;

    private static final int[] ICONS = {
            R.drawable.ic_keyboard_search,
            R.drawable.ic_keyboard_left,
            R.drawable.ic_keyboard_right,
            R.drawable.ic_keyboard_back,
    };

    private static final String[] LETTERS = {
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
            "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
    };

    private static final int TYPE_TEXT = 0;
    private static final int TYPE_ICON = 1;

    private final Listener listener;

    public TmdbKeyboardAdapter(Listener listener) {
        this.listener = listener;
    }

    @Override
    public int getItemCount() {
        return ICONS.length + LETTERS.length;
    }

    @Override
    public int getItemViewType(int position) {
        return position < ICONS.length ? TYPE_ICON : TYPE_TEXT;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_ICON) return new IconHolder(inflater.inflate(R.layout.adapter_tmdb_key_icon, parent, false));
        return new TextHolder(inflater.inflate(R.layout.adapter_tmdb_key_text, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof IconHolder icon) {
            icon.icon.setImageResource(ICONS[position]);
            bind(icon.itemView, position);
        } else if (holder instanceof TextHolder text) {
            text.text.setText(LETTERS[position - ICONS.length]);
            bind(text.itemView, position);
        }
    }

    private void bind(@NonNull View view, int position) {
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setOnClickListener(v -> dispatchClick(position));
        view.setOnKeyListener((v, keyCode, event) -> dispatchKey(position, keyCode, event));
    }

    private void dispatchClick(int position) {
        switch (position) {
            case 0 -> listener.onConfirm();
            case 1 -> listener.onMoveCaret(-1);
            case 2 -> listener.onMoveCaret(1);
            case 3 -> listener.onBackspace();
            default -> listener.onText(LETTERS[position - ICONS.length]);
        }
    }

    private boolean dispatchKey(int position, int keyCode, KeyEvent event) {
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN) return false;
        int column = position % SPAN_COUNT;
        // 每行最后一个键按右：交给右侧结果区，不参与 RecyclerView 默认焦点搜索。
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && column == SPAN_COUNT - 1) {
            listener.onRequestResultFocus();
            return true;
        }
        // 第一排再按↑：回到输入框，避免焦点跑到对话框外面。
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && position < SPAN_COUNT) {
            listener.onRequestInputFocus();
            return true;
        }
        // 最后一行再按↓：同样落到结果区第一张海报（XML 里的 keyboard nextFocusDown 兜底）。
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && position >= getItemCount() - SPAN_COUNT) {
            listener.onRequestResultFocus();
            return true;
        }
        return false;
    }

    static class TextHolder extends RecyclerView.ViewHolder {

        final TextView text;

        TextHolder(@NonNull View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.text);
        }
    }

    static class IconHolder extends RecyclerView.ViewHolder {

        final ImageView icon;

        IconHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.icon);
        }
    }
}
