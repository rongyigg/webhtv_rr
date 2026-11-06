package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import com.fongmi.android.tv.api.config.SubscriptionTmdbCredentialStore;
import com.fongmi.android.tv.setting.Setting;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class TmdbConfig {

    public static final String ORIGIN_USER = "USER";
    public static final String ORIGIN_TRANSIENT_SUBSCRIPTION = "TRANSIENT_SUBSCRIPTION";

    private static final Gson GSON = new Gson();
    /**
     * 内置 TMDB API Key（兜底）。
     *
     * <p>用户从没配过、或者把设置里的 Key 清空时，仍然要能直接用 TMDB：
     * {@link #sanitize()} 在「用户没填、订阅也没给」时落到这个值，
     * 于是所有 {@code isReady()} 闸门（站点开关 / 详情页原生模式 / 首页预匹配）都会放行。
     *
     * <p>优先级：用户自己填的 Key &gt; 订阅下发的 Key &gt; 这个内置兜底。
     * 用户填了任何 Key 都只是覆盖默认值，不会与内置值混用。
     */
    private static final String DEFAULT_API_KEY = "d7040155454e7fdf547c4d889ebbcca7";
    private static final String DEFAULT_API_BASE = "https://api.tmdb.org/3";
    private static final String DEFAULT_IMAGE_HOST = "https://images.tmdb.org";
    private static final String DEFAULT_IMAGE_BASE = "https://images.tmdb.org/t/p/w342";
    private static final String DEFAULT_BACKDROP_BASE = "https://images.tmdb.org/t/p/w780";
    private static final String DEFAULT_LANGUAGE = "zh-CN";
    /**
     * 默认不富集 TMDB 的站点：非影视内容（音频/有声/小说/漫画/短剧/画）加上配置类站点。
     *
     * <p>统一写半角括号即可——{@link #normalizeBrackets} 会让同一条规则一并命中
     * {@code 「设」配置}、{@code 【设】配置} 这些写法，猫源用的正是全角角括号。
     *
     * <p>注意 {@code [书]} 与 {@code [小说]}、{@code [漫]} 与 {@code [漫画]} 不可互替：
     * 匹配是子串包含，{@code [书]} 命中 {@code [书]xxx} 但不命中 {@code [小说]xxx}，
     * 因为 {@code [小说]} 里 {@code [书} 后面跟的是 {@code 说} 而非 {@code ]}。
     * {@code 配置} 不带括号，纯文本子串匹配，能命中 {@code 「设」配置}、{@code [配置]xxx}。
     */
    private static final List<String> DEFAULT_DISABLED_RULES = List.of("[音]", "[听]", "[书]", "[漫]", "[短]", "[设]", "[画]", "[漫画]", "[小说]", "配置", "[配]");
    private static final Pattern TMDB_SIZE = Pattern.compile("/(?:w\\d+|h\\d+|original)$");

    @SerializedName("apiBase")
    private String apiBase;
    @SerializedName("apiKey")
    private String apiKey;
    @SerializedName(value = "apikey", alternate = {"api_key", "tmdbApiKey", "key"})
    private String apiKeyCompat;
    @SerializedName(value = "accessToken", alternate = {"token", "readAccessToken", "bearerToken"})
    private String accessToken;
    @SerializedName(value = "omdbApiKey", alternate = {"omdbKey", "imdbApiKey"})
    private String omdbApiKey;
    @SerializedName("language")
    private String language;
    @SerializedName("imageBase")
    private String imageBase;
    @SerializedName("backdropBase")
    private String backdropBase;
    @SerializedName(value = "enabledSites", alternate = {"siteKeys", "sites", "matchSites"})
    private List<String> enabledSites;
    @SerializedName(value = "excludeKeywords", alternate = {"exclude", "blockedKeywords", "skipKeywords"})
    private List<String> excludeKeywords;
    @SerializedName("excludeKeywordsConfigured")
    private Boolean excludeKeywordsConfigured;
    @SerializedName("disabledSites")
    private List<String> disabledSites;
    @SerializedName(value = "allowedSites", alternate = {"includeSites", "whitelistSites"})
    private List<String> allowedSites;
    private transient String credentialOrigin = ORIGIN_USER;
    private transient String credentialSubscriptionKey = "";
    private transient long credentialScopeEpoch;
    /**
     * 当前 [apiKey] 是不是「内置兜底值」（用户没填、订阅也没下发 Key）。
     *
     * <p>只用于让 {@link #effectiveCurrent()} / {@link #effective} 知道「这个 Key 不是用户自己配的」，
     * 从而不把订阅下发的临时 Key 顶掉；{@link #isReady()} 的语义不变（内置 Key 同样算可用）。
     * transient：绝不写进设置 JSON。
     */
    private transient boolean builtinApiKey;

    public static TmdbConfig objectFrom(String json) {
        try {
            TmdbConfig config = GSON.fromJson(json, TmdbConfig.class);
            return config == null ? new TmdbConfig().sanitize() : config.sanitize();
        } catch (Throwable e) {
            return new TmdbConfig().sanitize();
        }
    }

    public static TmdbConfig effectiveCurrent() {
        TmdbConfig configured = objectFrom(Setting.getTmdbConfig());
        // 只有「用户自己配的 Key」才压过订阅下发的临时 Key；内置兜底值不算用户配置，
        // 否则一旦内置了默认 Key，订阅里的 Key 会被永久旁路（功能看起来还在，实际用的都是内置值）。
        if (configured.isReady() && !configured.builtinApiKey) SubscriptionTmdbCredentialStore.discardCredential();
        SubscriptionTmdbCredentialStore.Scope scope = SubscriptionTmdbCredentialStore.currentScope();
        return effective(configured, SubscriptionTmdbCredentialStore.snapshot(scope));
    }

    public static TmdbConfig effective(TmdbConfig configured, SubscriptionTmdbCredentialStore.Snapshot snapshot) {
        TmdbConfig effective = configured == null ? new TmdbConfig() : configured.copy();
        effective.sanitize();
        if (effective.isReady() && !effective.builtinApiKey) {
            effective.credentialOrigin = ORIGIN_USER;
            return effective;
        }
        if (snapshot != null && !snapshot.isEmpty() && isOfficialApiBase(DEFAULT_API_BASE)) {
            effective.apiBase = DEFAULT_API_BASE;
            effective.apiKey = snapshot.getApiKey();
            effective.apiKeyCompat = effective.apiKey;
            effective.accessToken = "";
            effective.builtinApiKey = false;
            effective.credentialOrigin = ORIGIN_TRANSIENT_SUBSCRIPTION;
            effective.credentialSubscriptionKey = snapshot.getSubscriptionKey();
            effective.credentialScopeEpoch = snapshot.getScopeEpoch();
            return effective;
        }
        effective.credentialOrigin = ORIGIN_USER;
        return effective;
    }

    public TmdbConfig sanitize() {
        if (TextUtils.isEmpty(credentialOrigin)) credentialOrigin = ORIGIN_USER;
        apiBase = normalizeApiBase(trimOr(apiBase, DEFAULT_API_BASE));
        // 三方 Key 都为空时回落到内置 Key：清空设置 / 新装应用都能直接出 TMDB 数据。
        // 顺序不能颠倒：`apiKey` 优先，其次是历史字段 `apiKeyCompat`，最后才是内置值。
        String configuredKey = trimOr(apiKey, trimOr(apiKeyCompat, ""));
        apiKey = TextUtils.isEmpty(configuredKey) ? DEFAULT_API_KEY : configuredKey;
        apiKeyCompat = apiKey;
        accessToken = trimOr(accessToken, "");
        if (!TextUtils.isEmpty(accessToken) && accessToken.equals(apiKey) && !isAccessToken(accessToken)) accessToken = "";
        // 内置兜底 = 「用户既没填 Key、也没有自己的访问令牌」。
        // 只用于让 effective() / effectiveCurrent() 判断订阅下发的 Key 是否该优先，
        // 不影响 isReady()：内置 Key 同样算可用。
        builtinApiKey = TextUtils.isEmpty(accessToken) &&
            (TextUtils.isEmpty(configuredKey) || DEFAULT_API_KEY.equals(configuredKey));
        omdbApiKey = trimOr(omdbApiKey, "");
        language = trimOr(language, DEFAULT_LANGUAGE);
        imageBase = normalizeImageInput(trimOr(imageBase, DEFAULT_IMAGE_BASE));
        backdropBase = normalizeImageInput(trimOr(backdropBase, ""));
        if (TextUtils.isEmpty(backdropBase) && isImageHost(imageBase)) backdropBase = imageBase(imageBase, "w780");
        if (isImageHost(imageBase)) imageBase = imageBase(imageBase, "w342");
        backdropBase = trimOr(backdropBase, DEFAULT_BACKDROP_BASE);
        if (isImageHost(backdropBase) && !backdropBase.contains("/t/p/")) backdropBase = imageBase(backdropBase, "w780");
        enabledSites = cleanList(enabledSites);
        disabledSites = mergeList(cleanList(excludeKeywords), cleanList(disabledSites));
        excludeKeywords = null;
        allowedSites = cleanList(allowedSites);
        if (excludeKeywordsConfigured == null) excludeKeywordsConfigured = !disabledSites.isEmpty();
        if (!excludeKeywordsConfigured && disabledSites.isEmpty()) disabledSites = getDefaultDisabledRules();
        return this;
    }

    public static List<String> getDefaultDisabledRules() {
        return new ArrayList<>(DEFAULT_DISABLED_RULES);
    }

    public String getApiBase() {
        return apiBase;
    }

    public String getApiHost() {
        String api = TextUtils.isEmpty(apiBase) ? DEFAULT_API_BASE : apiBase;
        api = trimTrailingSlash(api);
        if (api.endsWith("/3")) api = api.substring(0, api.length() - 2);
        return trimTrailingSlash(api);
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getOmdbApiKey() {
        return omdbApiKey;
    }

    public String getLanguage() {
        return language;
    }

    public String getImageBase() {
        return imageBase;
    }

    public String getBackdropBase() {
        return backdropBase;
    }

    public String getImageHost() {
        String base = TextUtils.isEmpty(imageBase) ? DEFAULT_IMAGE_BASE : imageBase;
        base = stripImageSize(base);
        if (base.endsWith("/t/p")) base = base.substring(0, base.length() - 4);
        base = trimTrailingSlash(base);
        if (isHttpUrl(base)) return base;
        String withScheme = ensureHttpScheme(base);
        return isHttpUrl(withScheme) ? withScheme : DEFAULT_IMAGE_HOST;
    }

    public List<String> getEnabledSites() {
        return enabledSites == null ? new ArrayList<>() : enabledSites;
    }

    public List<String> getExcludeKeywords() {
        return excludeKeywords == null ? new ArrayList<>() : excludeKeywords;
    }

    public List<String> getDisabledSites() {
        return disabledSites == null ? new ArrayList<>() : disabledSites;
    }

    public List<String> getAllowedSites() {
        return allowedSites == null ? new ArrayList<>() : allowedSites;
    }

    public boolean isExcludeKeywordsConfigured() {
        return Boolean.TRUE.equals(excludeKeywordsConfigured);
    }

    public boolean isReady() {
        return !TextUtils.isEmpty(getAccessToken()) || !TextUtils.isEmpty(getApiKey());
    }

    public boolean isTransientSubscriptionCredential() {
        return ORIGIN_TRANSIENT_SUBSCRIPTION.equals(credentialOrigin);
    }

    public String getCredentialOrigin() {
        return credentialOrigin;
    }

    public String getCredentialSubscriptionKey() {
        return credentialSubscriptionKey == null ? "" : credentialSubscriptionKey;
    }

    public long getCredentialScopeEpoch() {
        return credentialScopeEpoch;
    }

    public static boolean isOfficialApiBase(String value) {
        if (TextUtils.isEmpty(value)) return false;
        try {
            URI uri = new URI(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            if (uri.getUserInfo() != null || uri.getPort() != -1 || uri.getQuery() != null || uri.getFragment() != null) return false;
            String host = uri.getHost();
            if (!"api.tmdb.org".equalsIgnoreCase(host) && !"api.themoviedb.org".equalsIgnoreCase(host)) return false;
            String path = uri.getPath();
            return "/3".equals(path) || "/3/".equals(path);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean hasSiteRules() {
        return !getEnabledSites().isEmpty() || !getAllowedSites().isEmpty() || !getDisabledSites().isEmpty();
    }

    public boolean isSiteEnabled(String key, String name) {
        sanitize();
        List<String> sites = getEnabledSites();
        if (matchesExact(getDisabledSites(), key) || matchesExact(getDisabledSites(), name)) return false;
        if (matchesExact(getAllowedSites(), key) || matchesExact(getAllowedSites(), name)) return true;
        if (matchesExact(sites, key) || matchesExact(sites, name)) return true;
        if (matches(getDisabledSites(), key) || matches(getDisabledSites(), name)) return false;
        return sites.isEmpty() || matches(sites, key) || matches(sites, name);
    }

    public String toJson() {
        TmdbConfig persistable = copy();
        if (persistable.isTransientSubscriptionCredential()) {
            persistable.apiKey = "";
            persistable.apiKeyCompat = "";
            persistable.accessToken = "";
            persistable.credentialOrigin = ORIGIN_USER;
        }
        persistable.sanitize();
        // 内置兜底 Key **不落盘**：设置里保持为空，将来升级内置 Key 时才能立刻生效，
        // 也不会把同一个共享 Key 写进每个用户的配置文件。
        if (persistable.builtinApiKey) {
            persistable.apiKey = "";
            persistable.apiKeyCompat = "";
        }
        return GSON.toJson(persistable);
    }

    private TmdbConfig copy() {
        TmdbConfig copy = new TmdbConfig();
        copy.apiBase = apiBase;
        copy.apiKey = apiKey;
        copy.apiKeyCompat = apiKeyCompat;
        copy.accessToken = accessToken;
        copy.omdbApiKey = omdbApiKey;
        copy.language = language;
        copy.imageBase = imageBase;
        copy.backdropBase = backdropBase;
        copy.enabledSites = copyList(enabledSites);
        copy.excludeKeywords = copyList(excludeKeywords);
        copy.excludeKeywordsConfigured = excludeKeywordsConfigured;
        copy.disabledSites = copyList(disabledSites);
        copy.allowedSites = copyList(allowedSites);
        copy.credentialOrigin = credentialOrigin;
        copy.credentialSubscriptionKey = credentialSubscriptionKey;
        copy.credentialScopeEpoch = credentialScopeEpoch;
        copy.builtinApiKey = builtinApiKey;
        return copy;
    }

    private static List<String> copyList(List<String> values) {
        return values == null ? null : new ArrayList<>(values);
    }

    private static String trimOr(String value, String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value.trim();
    }

    private static String normalizeApiBase(String value) {
        String api = ensureHttpScheme(trimTrailingSlash(value));
        if (api.endsWith("/3")) return api;
        return joinUrl(api, "3");
    }

    private static String normalizeImageInput(String value) {
        if (TextUtils.isEmpty(value)) return value;
        return ensureHttpScheme(value.trim());
    }

    private static String ensureHttpScheme(String value) {
        if (TextUtils.isEmpty(value) || isHttpUrl(value)) return value;
        if (looksLikeHost(value)) return "https://" + value.trim();
        return value;
    }

    private static boolean looksLikeHost(String value) {
        String text = trimTrailingSlash(value);
        if (TextUtils.isEmpty(text) || text.contains("://") || text.contains(" ") || text.startsWith("/")) return false;
        int slash = text.indexOf('/');
        String host = slash < 0 ? text : text.substring(0, slash);
        return host.contains(".") || host.equalsIgnoreCase("localhost") || host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+(:\\d+)?");
    }

    private static boolean isImageHost(String value) {
        String image = trimTrailingSlash(value);
        return image.endsWith("/t/p") || image.equals(DEFAULT_IMAGE_HOST) || image.endsWith(".tmdb.org") || isHttpUrl(image) || looksLikeHost(image);
    }

    private static String imageBase(String value, String size) {
        String image = stripImageSize(value);
        if (image.endsWith("/t/p")) return joinUrl(image, size);
        return joinUrl(joinUrl(image, "t/p"), size);
    }

    private static String joinUrl(String base, String path) {
        return trimTrailingSlash(base) + "/" + path;
    }

    private static String stripImageSize(String value) {
        String image = trimTrailingSlash(value);
        while (TMDB_SIZE.matcher(image).find()) {
            image = image.substring(0, image.lastIndexOf('/'));
            image = trimTrailingSlash(image);
        }
        return image;
    }

    private static String trimTrailingSlash(String value) {
        String text = TextUtils.isEmpty(value) ? "" : value.trim();
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return text;
    }

    private static boolean isHttpUrl(String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://"));
    }

    private static List<String> cleanList(List<String> values) {
        List<String> result = new ArrayList<>();
        if (values == null) return result;
        for (String value : values) {
            if (TextUtils.isEmpty(value)) continue;
            String item = value.trim();
            if (!item.isEmpty() && !result.contains(item)) result.add(item);
        }
        return result;
    }

    private static List<String> mergeList(List<String> first, List<String> second) {
        List<String> result = new ArrayList<>();
        addAllUnique(result, first);
        addAllUnique(result, second);
        return result;
    }

    private static void addAllUnique(List<String> result, List<String> values) {
        if (values == null) return;
        for (String value : values) if (!TextUtils.isEmpty(value) && !result.contains(value)) result.add(value);
    }

    /**
     * 括号写法归一。同一个分类标记在不同源里写作 {@code [音]}、{@code 「音」}、{@code 【音】}——
     * 猫源全用全角角括号，TVBox 配置多用半角。一条规则该把这些写法都覆盖住，
     * 否则默认规则对猫源的 57 个站点一条也匹配不上。
     */
    private static String normalizeBrackets(String value) {
        if (TextUtils.isEmpty(value)) return "";
        return value
                .replace('「', '[').replace('」', ']')
                .replace('【', '[').replace('】', ']')
                .replace('〔', '[').replace('〕', ']')
                .replace('［', '[').replace('］', ']');
    }

    private static boolean matches(List<String> rules, String value) {
        if (rules == null || TextUtils.isEmpty(value)) return false;
        String target = normalizeBrackets(value).toLowerCase(Locale.ROOT);
        for (String rule : rules) {
            if (TextUtils.isEmpty(rule)) continue;
            if (target.contains(normalizeBrackets(rule.trim()).toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static boolean matchesExact(List<String> rules, String value) {
        if (rules == null || TextUtils.isEmpty(value)) return false;
        String target = normalizeBrackets(value).trim();
        for (String rule : rules) {
            if (TextUtils.isEmpty(rule)) continue;
            if (target.equalsIgnoreCase(normalizeBrackets(rule.trim()))) return true;
        }
        return false;
    }

    private static boolean isAccessToken(String value) {
        return value != null && value.trim().split("\\.").length >= 3;
    }
}
