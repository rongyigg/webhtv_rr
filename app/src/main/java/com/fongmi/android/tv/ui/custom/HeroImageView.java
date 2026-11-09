package com.fongmi.android.tv.ui.custom;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.ui.hero.HeroImageLoader;

import coil3.ImageLoader;
import coil3.Image_androidKt;
import coil3.SingletonImageLoader;
import coil3.request.Disposable;
import coil3.request.ErrorResult;
import coil3.request.ImageRequest;
import coil3.request.SuccessResult;

/**
 * 首页 Hero 全屏背景图：两层 ImageView 交叉淡入。
 *
 * <p>从 ref_docs/HeroImageView.java（JADX 反编译的原版）搬运并清理而来，职责与视觉行为不变：
 * <ul>
 *   <li>本类（布局里的 {@code heroBg}）永远只负责「新图」；旧图在加载成功的那一瞬间被压到
 *       同级兄弟 {@code heroBgUnder} 上垫底，随后新图做
 *       1.03→1.0 缩放 + alpha 0→1 的 450ms 淡入，于是换标题时不是硬切，而是叠化。</li>
 *   <li>首次展示 / 从详情页返回时，{@link #setPendingEntrance(boolean)} 标记过的入场动画
 *       会在下一次可见时补播，并带 220ms 起始延迟（原版 onAttachedToWindow / onVisibilityChanged）。</li>
 *   <li>固定按 1920×1080 解码（原版 {@code p / q}），既够铺满 4K 屏，又不会把原图整张读进内存。</li>
 * </ul>
 *
 * <p>与原版的差异（仅实现层，视觉与调用语义一致）：
 * <ol>
 *   <li>原版用 Glide（{@code a.e(...)}）加载，这里改用项目统一的 Coil 单例
 *       （{@link HeroImageLoader}），网络栈、代理、防盗链 header、3GB 磁盘缓存都与首页 Compose 共用。</li>
 *   <li>原版 {@code f(String, int, j31)} → {@link #loadImage(String, int, Runnable)}；
 *       原版 {@code a()} → {@link #reset()}，{@code c(String)} → {@link #setSharedUrl(String)}。</li>
 *   <li>原版每次换图会先清空 {@code heroBgUnder}、再用旧地址重新请求一次来铺底；这里直接把
 *       当前已经解码好的 Drawable 下压，省掉一次 Coil 往返，视觉结果相同（旧图全程不消失）。</li>
 *   <li>原版被 R8 抹掉的类（je2 / gd3 / j31 / df2 / xe0 / ll2 / wl2 / ul2 / nm3 / r01 / e3 等）
 *       全部删除，只保留与背景图相关的逻辑。</li>
 * </ol>
 *
 * <p>本类不碰 PlayerManager / PlaybackActivity / catvod 数据层，只负责「给地址 → 显示背景」。
 */
public class HeroImageView extends ImageView {

    private static final String TAG = "HeroImageView";

    /** 原版 {@code p / q}：背景图固定解码尺寸（预取 {@link #prefetch} 必须与它完全一致）。 */
    public static final int TARGET_WIDTH = 1920;
    public static final int TARGET_HEIGHT = 1080;

    /** 原版构造里的 AnimatorSet 时长。 */
    private static final long FADE_DURATION = 450L;

    /** 原版 onAttachedToWindow / onVisibilityChanged 里补播入场动画时的起始延迟。 */
    private static final long ENTRANCE_DELAY = 220L;

    /** 原版 {@code HeroImageView.r}：跨页面共享的最后一张背景地址，返回首页时用来判断要不要重播淡入。 */
    private static volatile String sharedUrl;

    /** 原版 {@code c}：1.03→1.0 缩放 + alpha 0→1，450ms，DecelerateInterpolator。 */
    private final AnimatorSet fadeIn;

    /** 原版 {@code h}：当前地址；同一地址重复调用直接回调，不重播动画。 */
    private String lastUrl;

    /** 原版 {@code i}：原版是本地缓存槽位，这里当请求序号，用来丢弃「翻页之后才回来」的过期回包。 */
    private int requestPos;

    /** 原版 {@code l}：布局里的 heroBgUnder，旧图垫在这一层。 */
    private ImageView underView;

    /** 原版 {@code k}：下一次可见时补播带 220ms 延迟的入场动画。 */
    private boolean pendingEntrance;

    /** 原版 {@code j}：换源后不要拿上一源的背景垫底。 */
    private boolean skipUnderImage;

    /** 当前 Coil 请求；新请求或重置时取消，避免回包打到已经翻页的标题上。 */
    private Disposable request;

    public HeroImageView(Context context) {
        this(context, null);
    }

    public HeroImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        // 【必须保留】原版的 450ms AnimatorSet：scaleX/Y 1.03→1.0，alpha 0→1。
        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(
                ObjectAnimator.ofFloat(this, "scaleX", 1.03f, 1.0f),
                ObjectAnimator.ofFloat(this, "scaleY", 1.03f, 1.0f),
                ObjectAnimator.ofFloat(this, "alpha", 0.0f, 1.0f));
        animatorSet.setDuration(FADE_DURATION);
        animatorSet.setInterpolator(new DecelerateInterpolator());
        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // 220ms 只属于「重新可见」那一次入场；普通换图必须立刻淡入。
                animatorSet.setStartDelay(0L);
            }
        });
        this.fadeIn = animatorSet;
    }

    /**
     * 记录「最后一张背景地址」（原版 {@code HeroImageView.c(String)}）。
     * 空串按 null 处理，避免把空地址当成有效画面。
     */
    public static void setSharedUrl(String url) {
        sharedUrl = TextUtils.isEmpty(url) ? null : url;
    }

    public static String getSharedUrl() {
        return sharedUrl;
    }

    /**
     * 加载一张背景图（原版 {@code f(String, int, j31)}）。
     *
     * <p>成功时：旧图下压到 {@code heroBgUnder}，新图做 450ms 淡入，然后回调。
     * <p>失败时：当前画面保持不动（不会变成黑屏），同样回调，保证 UI 不会卡在 loading。
     * <p>空地址：只回调、不动画面 —— 与原名一致；「这张没有背景」由调用方先 {@link #reset()}。
     *
     * @param url      图片地址
     * @param pos      Hero 位置。原版用作本地缓存槽位，这里作为请求序号，用于丢弃过期回包
     * @param callback 加载结束（成功或失败）后的回调，可为 null
     */
    public void loadImage(String url, int pos, Runnable callback) {
        if (TextUtils.isEmpty(url)) {
            runCallback(callback);
            return;
        }
        if (url.equals(lastUrl)) {
            runCallback(callback);
            return;
        }
        lastUrl = url;
        requestPos = pos;
        // 原版在铺底之前就把 j 消费掉：这次换源不保留上一源的背景。
        boolean keepUnder = !skipUnderImage;
        skipUnderImage = false;

        // 旧图在整个加载期间保持完全不透明，加载成功后再由新图盖上去淡入。
        cancelFadeIn();
        disposeRequest();

        final ImageLoader loader = imageLoader(getContext());
        if (loader == null) {
            lastUrl = null;
            runCallback(callback);
            return;
        }

        ImageRequest imageRequest = new ImageRequest.Builder(getContext())
                .data(url)
                .size(TARGET_WIDTH, TARGET_HEIGHT)
                .listener(new ImageRequest.Listener() {
                    @Override
                    public void onSuccess(ImageRequest req, SuccessResult result) {
                        if (!isCurrent(url, pos)) return;
                        request = null;
                        onImageLoaded(result, keepUnder, callback);
                    }

                    @Override
                    public void onError(ImageRequest req, ErrorResult result) {
                        if (!isCurrent(url, pos)) return;
                        request = null;
                        // 失败后允许同地址重试，否则一次网络抖动就会把这张背景钉死。
                        lastUrl = null;
                        runCallback(callback);
                    }
                })
                .build();
        // enqueue 的回包在 Main 线程，可以直接操作 View。内存缓存命中时也可能同步回调，
        // 此时这里赋值的是一个已经结束的 Disposable，下次 dispose 是空操作，无副作用。
        request = loader.enqueue(imageRequest);
    }

    /**
     * 静默预取一张背景大图（原版 `je2.e(...)` 的等价机制）：**只把图下进 Coil 缓存，不碰任何 View**。
     *
     * <p>与 {@link #loadImage} 用**完全相同**的 data + size 构造请求，这是内存缓存能命中的前提：
     * Coil 的内存缓存键默认就是 `request.data`（即 URL），再叠加解码尺寸；两边尺寸一致，
     * 焦点移过来时 `loadImage` 才能直接拿到同一份已解码位图。磁盘缓存键只认 URL，同样一定命中。
     *
     * <p>故意只 {@code enqueue} 不保存 {@link Disposable}：预取是「替缓存干活」，
     * 用户焦点已经移走也必须让它写完，否则这张图下次还要重下。
     *
     * @param url 背景大图地址；空地址直接忽略
     */
    public static void prefetch(Context context, String url) {
        if (TextUtils.isEmpty(url)) return;
        try {
            ImageLoader loader = imageLoader(context);
            if (loader == null) return;
            ImageRequest request = new ImageRequest.Builder(context)
                    .data(url)
                    .size(TARGET_WIDTH, TARGET_HEIGHT)
                    .build();
            loader.enqueue(request);
        } catch (Throwable e) {
            // 预取失败绝不能影响首页：真正的显示请求还会再走一次同一条链路。
            Log.w(TAG, "prefetch failed: " + url, e);
        }
    }

    /**
     * 清空当前画面（原版 {@code a()}），无横图 / 换源 / 空态时调用。
     *
     * <p><b>严格照抄逆向出来的调用链</b>（{@code ref_docs/HeroImageView.java}）：
     * <pre>
     * a()  this.h = null; this.i = false; this.j = false; b();
     * b()  d();                       // ① 先清底图 heroBgUnder
     *      this.m = null;
     *      if (fadeIn.isRunning()) fadeIn.cancel();   // ② 取消淡入
     *      Glide.clear(this);          // ③ 取消主图在途请求
     *      setImageDrawable(null);     // ④ 清空主图
     * d()  Glide.clear(heroBgUnder);
     *      heroBgUnder.setImageDrawable(null);
     *      heroBgUnder.setVisibility(8);              // ⑤ 隐藏底图
     * </pre>
     *
     * <p>顺序本身也是逆向代码的一部分：**先清底图，再清主图**。只清主图、或者只把
     * {@code heroBg} 设成 GONE 而不动 {@code heroBgUnder} 时，上一部剧的整屏位图会从垫底层
     * 露出来 —— 这就是「没有横图却残留上一张图」的根因。
     *
     * <p>本类用 Coil，{@code Glide.clear(...)} 的等价物是 {@link #disposeRequest()}；
     * 底图这一层从不发起 Coil 请求（旧图是直接 {@code setImageDrawable} 压下去的），
     * 所以 {@link #clearUnderImage()} 只需要清 Drawable + 隐藏，没有请求可取消。
     */
    public void reset() {
        // a()：三个状态位（这里的 requestPos / pendingEntrance 是 Coil 请求序号与入场动画的等价物）
        lastUrl = null;
        requestPos = 0;
        pendingEntrance = false;
        skipUnderImage = false;
        // b()：① 先清底图（原版 d() 在 b() 的最前面）
        clearUnderImage();
        // b()：② 取消淡入并把 alpha / scale 拉回正常值
        cancelFadeIn();
        // b()：③ 取消主图在途请求 + ④ 清空主图
        disposeRequest();
        setImageDrawable(null);
    }

    /**
     * 清掉垫底层（原版 {@code d()}）：清 Drawable → 隐藏。
     *
     * <p>与原版的唯一差别是**每次重新定位**底图：原版 {@code d()} 只读字段 {@code l}，
     * 而 {@code l} 只在第一次成功铺底（原版 {@code f()}）时才被赋值。一旦遇到
     * <ul>
     *   <li>首次进首页就没有横图、</li>
     *   <li>父容器当时还不是 {@code heroBgUnder} 的宿主（inflate 顺序 / 复用）、</li>
     *   <li>View 被重新 inflate、旧引用已经脱离视图树</li>
     * </ul>
     * 字段就永远是 null，清理会被静默跳过，底图残留。
     * {@link #findUnderView()} 内部仍然缓存结果，命中后重复调用没有查找开销。
     */
    public void clearUnderImage() {
        ImageView under = findUnderView();
        if (under == null) return;
        under.setImageDrawable(null);
        under.setVisibility(INVISIBLE);
    }

    /**
     * 原版 {@code k}：标记「下一次可见时补播入场动画」。
     * 首次进入首页、从详情页返回首页时置 true。
     */
    public void setPendingEntrance(boolean pending) {
        this.pendingEntrance = pending;
    }

    public boolean isPendingEntrance() {
        return pendingEntrance;
    }

    /**
     * 原版 {@code j}：下一次换图不要拿上一张背景垫底（换源时上一源的画面已经无意义）。
     */
    public void setSkipUnderImage(boolean skip) {
        this.skipUnderImage = skip;
    }

    /**
     * 立刻结束任何正在进行的淡入并把画面恢复为完全不透明。
     * 对应原版 HomeActivityV2 返回首页时手工做的那段
     * {@code cancel() + setAlpha(1) + setScaleX(1) + setScaleY(1)}。
     */
    public void showImmediately() {
        pendingEntrance = false;
        cancelFadeIn();
    }

    public String getLastUrl() {
        return lastUrl;
    }

    /** 原版 {@code getHeroBitmap()}：给详情页/高斯模糊取当前背景的软件位图副本。 */
    public Bitmap getHeroBitmap() {
        Drawable drawable = getDrawable();
        if (!(drawable instanceof BitmapDrawable)) return null;
        Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
        if (bitmap == null || bitmap.isRecycled()) return null;
        try {
            // Coil 在 API 26+ 默认解码成 HARDWARE 位图，copy() 会直接抛异常，
            // 这种情况用「画进软件位图」的方式拿一份等价副本。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && bitmap.getConfig() == Bitmap.Config.HARDWARE) {
                Bitmap copy = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
                new Canvas(copy).drawBitmap(bitmap, 0f, 0f, null);
                return copy;
            }
            return bitmap.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Throwable e) {
            Log.w(TAG, "getHeroBitmap failed", e);
            return null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (pendingEntrance && isShown()) startEntrance();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && pendingEntrance && isShown()) startEntrance();
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        // 原版：不可见就释放垫底层，避免整屏位图在后台白占内存。
        if (visibility != VISIBLE) clearUnderImage();
    }

    private void startEntrance() {
        pendingEntrance = false;
        setAlpha(0.0f);
        setScaleX(1.03f);
        setScaleY(1.03f);
        startFadeIn(ENTRANCE_DELAY);
    }

    private void startFadeIn(long delay) {
        fadeIn.setStartDelay(delay);
        fadeIn.start();
    }

    private void cancelFadeIn() {
        if (fadeIn.isRunning()) fadeIn.cancel();
        // 动画被取消在半透明/缩放中间态时，把当前图恢复成正常显示，避免留下半张残影。
        setAlpha(1.0f);
        setScaleX(1.0f);
        setScaleY(1.0f);
    }

    private void onImageLoaded(SuccessResult result, boolean keepUnder, Runnable callback) {
        if (!isAlive(getContext())) {
            runCallback(callback);
            return;
        }
        Drawable drawable;
        try {
            drawable = Image_androidKt.asDrawable(result.getImage(), getResources());
        } catch (Throwable e) {
            Log.w(TAG, "asDrawable failed: " + lastUrl, e);
            lastUrl = null;
            runCallback(callback);
            return;
        }
        if (keepUnder) moveCurrentToUnder();
        else clearUnderImage();
        setImageDrawable(drawable);
        startFadeIn(0L);
        runCallback(callback);
    }

    /** 把当前显示的这张图压到 heroBgUnder 上垫底，随后新图就在它上面淡入。 */
    private void moveCurrentToUnder() {
        Drawable current = getDrawable();
        ImageView under = findUnderView();
        if (current == null || under == null) return;
        under.setImageDrawable(current);
        under.setScaleX(1.0f);
        under.setScaleY(1.0f);
        under.setAlpha(1.0f);
        under.setVisibility(VISIBLE);
    }

    /** 原版从父容器里找 {@code heroBgUnder}；父容器找不到时再退到根 View 找一次。 */
    private ImageView findUnderView() {
        // 缓存命中也要确认它仍挂在视图树上：Activity 重建 / 重新 inflate 之后旧引用会脱离，
        // 继续往那个已经不在屏幕上的旧 View 写「清空」，真正的底图就永远清不掉。
        if (underView != null && underView.isAttachedToWindow()) return underView;
        underView = null;
        View target = null;
        ViewParent parent = getParent();
        if (parent instanceof ViewGroup) target = ((ViewGroup) parent).findViewById(R.id.heroBgUnder);
        if (target == null) target = getRootView().findViewById(R.id.heroBgUnder);
        if (target instanceof ImageView) underView = (ImageView) target;
        return underView;
    }

    private boolean isCurrent(String url, int pos) {
        return pos == requestPos && url != null && url.equals(lastUrl);
    }

    private void disposeRequest() {
        if (request == null) return;
        request.dispose();
        request = null;
    }

    /** 原版 {@code e()}：Activity 正在销毁时不要再往 View 上贴图。 */
    private static boolean isAlive(Context context) {
        Activity activity = findActivity(context);
        return activity == null || (!activity.isFinishing() && !activity.isDestroyed());
    }

    private static Activity findActivity(Context context) {
        Context current = context;
        while (current != null) {
            if (current instanceof Activity) return (Activity) current;
            if (!(current instanceof ContextWrapper)) return null;
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == current) return null;
            current = base;
        }
        return null;
    }

    /**
     * 取项目统一的 Coil 单例。
     *
     * <p>{@link HeroImageLoader#install()} 内部是 {@code setSafe}，Compose 首页已经装过就直接复用
     * 同一个实例（同一个 catvod OkHttp、同一份 3GB 磁盘缓存）；没装过时由这里补装。
     * <p>正式接入时，新 Activity 仍应在 onCreate 里先调一次 {@code HeroImageLoader.install()}
     * （与 HeroHomeActivity 一致）：这里的兜底会让 3GB 磁盘缓存在第一次 loadImage 时才初始化。
     */
    private static ImageLoader imageLoader(Context context) {
        try {
            HeroImageLoader.INSTANCE.install();
        } catch (Throwable e) {
            // 已有别的 Coil 默认单例时 setSafe 会抛异常，退回现有实例总比崩掉好。
            Log.w(TAG, "HeroImageLoader.install() failed", e);
        }
        return SingletonImageLoader.get(context);
    }

    private static void runCallback(Runnable callback) {
        if (callback != null) callback.run();
    }
}
