package com.suileyan.xpmibackup.ui;

import android.annotation.SuppressLint;
import android.app.Fragment;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.Toast;

import com.suileyan.cloud.CloudAccount;
import com.suileyan.cloud.CloudAccountStore;
import com.suileyan.cloud.EncryptedCredStore;
import com.suileyan.cloud.login.Yun139Login;
import com.suileyan.comm.LogHelp;
import com.suileyan.xpmibackup.R;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * 云盘 WebView 登录页（泛化，支持多网盘）
 * 139 云盘：加载 yun.139.com，捕获请求头 Authorization: Basic（personal 节点）保存
 * 光鸭云盘：加载 guangyapan.com，捕获 Authorization: Bearer + localStorage 扫描提取 access/refresh token
 * 夸克云盘：加载 pan.quark.cn，登录后捕获 Cookie（含 __puus 会话凭证）保存
 * 用户完成网页登录后点击「完成」自动提取凭据并保存
 */
public class WebViewLoginFragment extends Fragment {

    private static final String TAG = "XpMiBackup";

    /** 网盘类型常量 */
    public static final String PROVIDER_139 = "139";
    public static final String PROVIDER_GUANGYA = "guangya";
    public static final String PROVIDER_QUARK = "quark";
    public static final String PROVIDER_123 = "123";
    public static final String PROVIDER_189 = "189";
    public static final String PROVIDER_BAIDU = "baidu";
    public static final String PROVIDER_WO = "wo";
    public static final String ARG_PROVIDER = "provider";

    /** 139 云盘登录页 */
    private static final String URL_139 = "https://yun.139.com";
    /** 光鸭云盘登录页 */
    private static final String URL_GUANGYA = "https://www.guangyapan.com/";
    /** 夸克云盘登录页 */
    private static final String URL_QUARK = "https://pan.quark.cn/";
    /** 123云盘登录页 */
    private static final String URL_123 = "https://www.123pan.com/";
    /** 天翼云盘登录页 */
    private static final String URL_189 = "https://cloud.189.cn/";
    /** 百度网盘登录页 */
    private static final String URL_BAIDU = "https://pan.baidu.com/";
    /** 联通沃盘登录页 */
    private static final String URL_WO = "https://pan.wo.cn/";

    /** 桌面版 User-Agent（电脑模式） */
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    /**
     * 桌面模式核心脚本（HTML 预注入 + 兜底注入复用同一段）：
     * 1) 覆写 navigator.userAgent / appVersion / platform / maxTouchPoints——堵住旧式 UA 检测
     * 2) 覆写 navigator.userAgentData（Client Hints）mobile=false/platform=Windows——
     *    现代站点（光鸭等 SPA）改用 userAgentData 判断设备，旧实现只改 userAgent 导致仍按移动渲染
     * 3) 强制 viewport width=1280 且允许缩放——首帧即桌面宽布局
     * 4) MutationObserver 盯防 SPA 路由重写 viewport，改回桌面
     */
    private static final String DESKTOP_FIX_JS =
            "(function(){"
            + "var UA='" + DESKTOP_UA + "';"
            + "function def(o,k,v){try{Object.defineProperty(o,k,{get:function(){return v;},configurable:true});}catch(e){}}"
            + "def(navigator,'userAgent',UA);"
            + "def(navigator,'appVersion',UA.replace('Mozilla/',''));"
            + "def(navigator,'platform','Win32');"
            + "def(navigator,'maxTouchPoints',0);"
            + "try{Object.defineProperty(navigator,'userAgentData',{get:function(){"
            + "var b=[{brand:'Chromium',version:'131'},{brand:'Not_A Brand',version:'24'},{brand:'Google Chrome',version:'131'}];"
            + "return {mobile:false,platform:'Windows',platformVersion:'15.0.0',architecture:'x86',bitness:'64',brands:b,uaFullVersion:'131.0.0.0',"
            + "getHighEntropyValues:function(k){return Promise.resolve({mobile:false,platform:'Windows',platformVersion:'15.0.0',architecture:'x86',bitness:'64',brands:b});},"
            + "toJSON:function(){return {brands:b,mobile:false,platform:'Windows'};}};"
            + "},configurable:true});}catch(e){}"
            + "function forceViewport(){"
            + "var m=document.querySelector('meta[name=viewport]');"
            + "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}"
            + "var c='width=1280, initial-scale=1, user-scalable=yes';"
            + "if(m.getAttribute('content')!==c)m.setAttribute('content',c);}"
            + "if(document.head)forceViewport();"
            + "if(window.MutationObserver){try{"
            + "new MutationObserver(forceViewport).observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['content']});"
            + "}catch(e){}}"
            + "})()";

    /** 139 localStorage 兜底提取脚本：扫描含 Basic 的值 */
    private static final String EXTRACT_JS_139 =
            "(function(){var out='';function scan(s){try{for(var i=0;i<s.length;i++){"
            + "var k=s.key(i);var v=s.getItem(k);"
            + "if(v&&v.toLowerCase().indexOf('basic')>=0){out=v;break;}}}catch(e){}}"
            + "scan(window.localStorage);if(!out)scan(window.sessionStorage);return out;})()";

    /** 光鸭 localStorage 提取脚本：扫描 access_token / refresh_token，返回 "access|||refresh" */
    private static final String EXTRACT_JS_GUANGYA =
            "(function(){var at='',rt='';function scan(s){try{for(var i=0;i<s.length;i++){"
            + "var k=s.key(i);var v=s.getItem(k);try{var o=JSON.parse(v);"
            + "if(o.access_token&&!at)at=o.access_token;if(o.refresh_token&&!rt)rt=o.refresh_token;"
            + "}catch(e){if(k.toLowerCase().indexOf('token')>=0&&v.length>20){"
            + "if(!at&&k.toLowerCase().indexOf('access')>=0)at=v;"
            + "if(!rt&&k.toLowerCase().indexOf('refresh')>=0)rt=v;}}}}catch(e){}}"
            + "scan(window.localStorage);return at+'|||'+rt;})()";

    /**
     * 123云盘 localStorage/sessionStorage 提取脚本：返回最长的 token 候选
     * 覆盖三种形态：值以 "Bearer xxx" 开头、JSON 内 token/auth 字段、JWT 形（含两个点）
     */
    private static final String EXTRACT_JS_123 =
            "(function(){var best='';"
            + "function walk(o){if(!o)return;if(typeof o==='string'){if(o.length>best.length)best=o;return;}"
            + "if(typeof o==='object'){for(var k in o){if(/token|auth/i.test(k)&&typeof o[k]==='string'&&o[k].length>best.length)best=o[k];walk(o[k]);}}}"
            + "function pick(v){if(!v)return;v=String(v).trim();if(v.length<20)return;"
            + "var m=v.match(/^Bearer\\s+(\\S+)$/);if(m)v=m[1];"
            + "if(v.split('.').length>=3){if(v.length>best.length)best=v;return;}"
            + "try{walk(JSON.parse(v));}catch(e){}}"
            + "function scan(s){try{for(var i=0;i<s.length;i++){var v=s.getItem(s.key(i));pick(v);}}catch(e){}}"
            + "scan(window.localStorage);scan(window.sessionStorage);return best;})()";

    /** 沃盘 localStorage 兜底提取脚本：扫描 access_token / refresh_token，返回 "access|||refresh"（仿光鸭） */
    private static final String EXTRACT_JS_WO =
            "(function(){var at='',rt='';function scan(s){try{for(var i=0;i<s.length;i++){"
            + "var k=s.key(i);var v=s.getItem(k);try{var o=JSON.parse(v);"
            + "if(o.access_token&&!at)at=o.access_token;if(o.refresh_token&&!rt)rt=o.refresh_token;"
            + "}catch(e){if(k.toLowerCase().indexOf('token')>=0&&v.length>20){"
            + "if(!at&&k.toLowerCase().indexOf('access')>=0)at=v;"
            + "if(!rt&&k.toLowerCase().indexOf('refresh')>=0)rt=v;}}}}catch(e){}}"
            + "scan(window.localStorage);return at+'|||'+rt;})()";

    private WebView webView;
    private Button btnDone;
    private String provider = PROVIDER_139;

    // ---- 139 捕获状态 ----
    private volatile String capturedAuth = "";
    private volatile boolean authFromRequest = false;
    private volatile String capturedHost = "";
    // ---- 光鸭捕获状态 ----
    private volatile String capturedBearer = "";
    private volatile String capturedRefresh = "";
    private volatile boolean bearerFromRequest = false;
    // ---- 123 云盘捕获状态 ----
    private volatile String captured123Bearer = "";
    private volatile boolean bearer123FromRequest = false;
    // ---- 沃盘捕获状态 ----
    private volatile String capturedWoToken = "";
    private volatile String capturedWoRefresh = "";

    /**
     * 初始化界面：按网盘类型配置 WebView 并加载登录页
     */
    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        var view = inflater.inflate(R.layout.fragment_webview_login, container, false);
        var args = getArguments();
        if (args != null && args.containsKey(ARG_PROVIDER)) {
            provider = args.getString(ARG_PROVIDER, PROVIDER_139);
        }

        webView = view.findViewById(R.id.webview_login);
        btnDone = view.findViewById(R.id.btn_webview_done);

        // 标题按网盘类型动态显示（139 / 光鸭 / 夸克）
        var tvTitle = (android.widget.TextView) view.findViewById(R.id.tv_webview_title);
        if (tvTitle != null) {
            if (PROVIDER_GUANGYA.equals(provider)) {
                tvTitle.setText(R.string.title_webview_login_guangya);
            } else if (PROVIDER_QUARK.equals(provider)) {
                tvTitle.setText(R.string.title_webview_login_quark);
            } else if (PROVIDER_123.equals(provider)) {
                tvTitle.setText(R.string.title_webview_login_123);
            } else if (PROVIDER_189.equals(provider)) {
                tvTitle.setText(R.string.title_webview_login_189);
            } else if (PROVIDER_BAIDU.equals(provider)) {
                tvTitle.setText(R.string.title_webview_login_baidu);
            } else if (PROVIDER_WO.equals(provider)) {
                tvTitle.setText(R.string.title_webview_login_wo);
            } else {
                tvTitle.setText(R.string.title_webview_login);
            }
        }

        var settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        // 禁止混合内容：HTTPS 页面加载 HTTP 子资源可能被 MITM 注入脚本窃取登录凭据（HIGH-17）
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // 电脑模式：桌面 UA + 宽视口，按桌面宽度渲染
        settings.setUserAgentString(DESKTOP_UA);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        // 初始 100% 缩放，不自动缩小，横向滚动完整查看
        webView.setInitialScale(100);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
                var msg = consoleMessage.message() + " (line " + consoleMessage.lineNumber() + ")";
                // 前端 console 输出同步记录到 web.log（可能含容量等业务数据）
                LogHelp.web("CONSOLE " + msg);
                if (consoleMessage.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                    LogHelp.e(TAG, "page JS error: " + msg);
                } else {
                    LogHelp.d(TAG, "page console: " + msg);
                }
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // 记录所有子资源请求到独立 web.log，用于定位网盘前端 API（容量等）调用逻辑（NEW-H-06）
                LogHelp.web("REQ " + request.getMethod() + " " + request.getUrl());
                if (request.isForMainFrame()) {
                    LogHelp.web("  ^ main-frame redirect=" + request.isRedirect());
                }
                if (PROVIDER_GUANGYA.equals(provider)) {
                    interceptGuangya(request);
                    // 光鸭主文档 HTML 预注入桌面脚本（赶在 SPA defer 脚本执行前完成设备伪装）
                    if (request.isForMainFrame() && "GET".equalsIgnoreCase(request.getMethod())
                            && isGuangyaHost(request.getUrl().getHost())
                            && isHtmlPage(request.getUrl().toString())) {
                        var injected = fetchAndInjectDesktop(request.getUrl().toString());
                        if (injected != null) return injected;
                    }
                } else if (PROVIDER_QUARK.equals(provider)) {
                    // 夸克登录页同为 SPA，同样预注入桌面模式
                    if (request.isForMainFrame() && "GET".equalsIgnoreCase(request.getMethod())
                            && isQuarkHost(request.getUrl().getHost())
                            && isHtmlPage(request.getUrl().toString())) {
                        var injected = fetchAndInjectDesktop(request.getUrl().toString());
                        if (injected != null) return injected;
                    }
                } else if (PROVIDER_123.equals(provider)) {
                    intercept123(request);
                    // 123云盘登录页同为 SPA，同样预注入桌面模式
                    if (request.isForMainFrame() && "GET".equalsIgnoreCase(request.getMethod())
                            && isPan123Host(request.getUrl().getHost())
                            && isHtmlPage(request.getUrl().toString())) {
                        var injected = fetchAndInjectDesktop(request.getUrl().toString());
                        if (injected != null) return injected;
                    }
                } else if (PROVIDER_189.equals(provider)) {
                    // 天翼登录页同为 SPA，同样预注入桌面模式
                    if (request.isForMainFrame() && "GET".equalsIgnoreCase(request.getMethod())
                            && is189Host(request.getUrl().getHost())
                            && isHtmlPage(request.getUrl().toString())) {
                        var injected = fetchAndInjectDesktop(request.getUrl().toString());
                        if (injected != null) return injected;
                    }
                } else if (PROVIDER_BAIDU.equals(provider)) {
                    // 百度登录页同为 SPA，同样预注入桌面模式
                    if (request.isForMainFrame() && "GET".equalsIgnoreCase(request.getMethod())
                            && isBaiduHost(request.getUrl().getHost())
                            && isHtmlPage(request.getUrl().toString())) {
                        var injected = fetchAndInjectDesktop(request.getUrl().toString());
                        if (injected != null) return injected;
                    }
                } else if (PROVIDER_WO.equals(provider)) {
                    // 沃盘：捕获 dispatcher 请求的 Accesstoken 头（登录后前端每个 API 请求必带）
                    interceptWo(request);
                } else {
                    intercept139(request);
                }
                return null;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    LogHelp.e(TAG, "page load error: code=" + error.getErrorCode()
                            + " desc=" + error.getDescription() + " url=" + request.getUrl());
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                if (request != null && request.isForMainFrame()) {
                    LogHelp.e(TAG, "page http error: " + errorResponse.getStatusCode()
                            + " url=" + request.getUrl());
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                LogHelp.web("PAGE_START " + url);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                // 第一帧渲染前注入桌面模式：viewport 越早生效，首帧即桌面布局（光鸭等 SPA 不会先按移动渲染）
                injectDesktopMode(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                LogHelp.web("PAGE_FINISH " + url);
                // 再次注入：防页面脚本/SPA 重写 viewport；随后兜底扫描 localStorage
                injectDesktopMode(view);
                if (PROVIDER_GUANGYA.equals(provider)) {
                    view.evaluateJavascript(EXTRACT_JS_GUANGYA, value -> {
                        if (value != null && value.contains("|||")) {
                            var cleaned = value.replace("\"", "");
                            var parts = cleaned.split("\\|\\|\\|");
                            // 约定格式 "access|||refresh"，refresh 在 parts[1]（NEW-H-02）
                            if (parts.length >= 2) {
                                var at = parts[0].trim();
                                var rt = parts[1].trim();
                                if (!at.isEmpty()) {
                                    capturedBearer = at;
                                    if (!rt.isEmpty()) capturedRefresh = rt;
                                    LogHelp.i(TAG, "光鸭 localStorage 提取到 token, len=" + at.length()
                                            + " refresh=" + (!rt.isEmpty()));
                                }
                            }
                        }
                    });
                } else if (PROVIDER_QUARK.equals(provider)) {
                    // 夸克：登录态在 Cookie（可能跨多个子域），点「完成」时合并读取保存
                    var ck = captureQuarkCookie();
                    var names = new java.util.ArrayList<String>();
                    if (ck != null) {
                        for (var pair : ck.split(";")) {
                            var t = pair.trim();
                            if (!t.isEmpty()) names.add(t.split("=", 2)[0]);
                        }
                    }
                    LogHelp.d(TAG, "夸克登录页 Cookie 就绪: len=" + (ck != null ? ck.length() : 0)
                            + " keys=" + String.join(",", names));
                } else if (PROVIDER_123.equals(provider)) {
                    // 123云盘：localStorage/sessionStorage 兜底扫描（主通道是请求头拦截）
                    view.evaluateJavascript(EXTRACT_JS_123, value -> {
                        if (value != null && !"null".equals(value)) {
                            var cleaned = value.replace("\"", "").trim();
                            if (cleaned.length() > 20 && cleaned.length() > captured123Bearer.length()) {
                                captured123Bearer = cleaned;
                                LogHelp.i(TAG, "123云盘 localStorage 提取到 token, len=" + cleaned.length());
                            }
                        }
                    });
                } else if (PROVIDER_189.equals(provider)) {
                    // 天翼：登录态在 SSON Cookie（可能跨多个域），点「完成」时合并读取保存
                    var sson = capture189Cookie();
                    LogHelp.d(TAG, "天翼 SSON 就绪: len=" + (sson != null ? sson.length() : 0));
                } else if (PROVIDER_BAIDU.equals(provider)) {
                    // 百度：登录态在 Cookie（含 BDUSS），点「完成」时合并读取保存
                    var bdck = captureBaiduCookie();
                    LogHelp.d(TAG, "百度网盘 Cookie 就绪: len=" + (bdck != null ? bdck.length() : 0));
                } else if (PROVIDER_WO.equals(provider)) {
                    // 沃盘：主通道是 dispatcher 请求头拦截；此处 localStorage 兜底扫 access/refresh token
                    view.evaluateJavascript(EXTRACT_JS_WO, value -> {
                        if (value != null && value.contains("|||")) {
                            var cleaned = value.replace("\"", "");
                            var parts = cleaned.split("\\|\\|\\|");
                            if (parts.length >= 2) {
                                var at = parts[0].trim();
                                var rt = parts[1].trim();
                                if (!at.isEmpty() && at.length() > capturedWoToken.length()) {
                                    capturedWoToken = at;
                                    LogHelp.i(TAG, "沃盘 localStorage 提取到 access_token, len=" + at.length());
                                }
                                if (!rt.isEmpty()) {
                                    capturedWoRefresh = rt;
                                }
                            }
                        }
                    });
                } else {
                    view.evaluateJavascript(EXTRACT_JS_139, value -> {
                        if (value != null && value.toLowerCase().contains("basic")) {
                            var idx = value.indexOf("Basic");
                            if (idx < 0) idx = value.indexOf("basic");
                            if (idx >= 0) {
                                var v = value.substring(idx + 5).replace("\"", "").trim();
                                if (!v.isEmpty()) {
                                    capturedAuth = v;
                                }
                            }
                        }
                    });
                }
            }
        });

        webView.loadUrl(PROVIDER_GUANGYA.equals(provider) ? URL_GUANGYA
                : PROVIDER_QUARK.equals(provider) ? URL_QUARK
                : PROVIDER_123.equals(provider) ? URL_123
                : PROVIDER_189.equals(provider) ? URL_189
                : PROVIDER_BAIDU.equals(provider) ? URL_BAIDU
                : PROVIDER_WO.equals(provider) ? URL_WO : URL_139);

        // 返回键：优先让 WebView 后退
        view.setFocusableInTouchMode(true);
        view.requestFocus();
        view.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP && webView.canGoBack()) {
                webView.goBack();
                return true;
            }
            return false;
        });

        btnDone.setOnClickListener(v -> onDone());
        return view;
    }

    /** 139：捕获 hcy/file/list 请求头的 Authorization: Basic（personal 节点） */
    private void intercept139(WebResourceRequest request) {
        var headers = request.getRequestHeaders();
        var auth = headers != null ? headers.get("Authorization") : null;
        if (auth != null && auth.toLowerCase().startsWith("basic ")) {
            var value = auth.substring(6).trim();
            if (!value.isEmpty()) {
                var host = request.getUrl().getHost();
                // 只记录 personal 节点：文件 API 仅在 personal-*.yun.139.com 上提供
                // 严格校验前缀 personal- 且后缀属于 yun.139.com，防相似域名绕过（HIGH-18 / NEW-M-05）
                if (host != null && isPersonal139Host(host)) {
                    capturedAuth = value;
                    authFromRequest = true;
                    capturedHost = host;
                    LogHelp.d(TAG, "captured 139 Authorization from request, host=" + host
                            + " len=" + value.length());
                }
            }
        }
    }

    /** 严格判断 139 personal 节点主机名（personal- 前缀 + yun.139.com 后缀） */
    private static boolean isPersonal139Host(String host) {
        if (host == null) return false;
        var h = host.toLowerCase(java.util.Locale.ROOT);
        return (h.startsWith("personal-") || h.startsWith("personal."))
                && (h.endsWith(".yun.139.com") || h.endsWith(".yun139.com"));
    }

    /** 光鸭：捕获 api 请求头的 Authorization: Bearer（登录后前端真实携带的 access_token） */
    private void interceptGuangya(WebResourceRequest request) {
        var headers = request.getRequestHeaders();
        var auth = headers != null ? headers.get("Authorization") : null;
        if (auth != null && auth.toLowerCase().startsWith("bearer ")) {
            var value = auth.substring(7).trim();
            var host = request.getUrl().getHost();
            // 光鸭所有 API 均在 guangyapan.com 域下；严格后缀匹配避免相似域名（HIGH-18）
            if (!value.isEmpty() && host != null && isGuangyaHost(host)) {
                capturedBearer = value;
                bearerFromRequest = true;
                LogHelp.d(TAG, "captured 光鸭 Bearer from request, host=" + host
                        + " len=" + value.length());
            }
        }
    }

    /** 严格判断主机名是否属于 guangyapan.com（HIGH-18） */
    private static boolean isGuangyaHost(String host) {
        if (host == null) return false;
        var h = host.toLowerCase(java.util.Locale.ROOT);
        return h.equals("guangyapan.com") || h.endsWith(".guangyapan.com");
    }

    /** 严格判断主机名是否属于夸克网盘主站（pan.quark.cn） */
    private static boolean isQuarkHost(String host) {
        if (host == null) return false;
        var h = host.toLowerCase(java.util.Locale.ROOT);
        return h.equals("pan.quark.cn") || h.equals("drive.quark.cn") || h.equals("drive-pc.quark.cn");
    }

    /** 严格判断主机名是否属于 123pan.com（防相似域名绕过，HIGH-18） */
    private static boolean isPan123Host(String host) {
        if (host == null) return false;
        var h = host.toLowerCase(java.util.Locale.ROOT);
        return h.equals("123pan.com") || h.endsWith(".123pan.com");
    }

    /**
     * 123云盘：捕获登录后 SPA API 请求的 Authorization: Bearer <token>
     * 容错设计：遍历全部请求头（不假设 key 大小写），识别值以 "Bearer " 开头或 JWT 形（含两个点、长度>20）的
     * token/auth 类头；Cookie 兜底（token 若出现在 cookie 里）。登录后前端必然发 API 请求，拦截即得。
     */
    private void intercept123(WebResourceRequest request) {
        var host = request.getUrl().getHost();
        if (host == null || !isPan123Host(host)) return;
        var headers = request.getRequestHeaders();
        if (headers == null) return;
        for (var entry : headers.entrySet()) {
            var name = entry.getKey() == null ? "" : entry.getKey().toLowerCase(java.util.Locale.ROOT);
            var value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (value.isEmpty()) continue;
            if (name.equals("authorization") || name.contains("token") || name.contains("auth")) {
                var token = pickBearer(value);
                if (!token.isEmpty() && token.length() > captured123Bearer.length()) {
                    captured123Bearer = token;
                    bearer123FromRequest = true;
                    LogHelp.i(TAG, "captured 123 token from request header " + entry.getKey()
                            + " len=" + token.length());
                }
            } else if (name.equals("cookie")) {
                for (var pair : value.split(";")) {
                    var p = pair.trim();
                    var idx = p.indexOf('=');
                    if (idx <= 0) continue;
                    var ck = p.substring(0, idx).trim().toLowerCase(java.util.Locale.ROOT);
                    var cv = p.substring(idx + 1).trim();
                    if ((ck.contains("token") || ck.contains("auth")) && cv.length() > 20
                            && cv.length() > captured123Bearer.length()) {
                        captured123Bearer = cv;
                        bearer123FromRequest = true;
                        LogHelp.i(TAG, "captured 123 token from cookie " + ck + " len=" + cv.length());
                    }
                }
            }
        }
    }

    /** 从 header 值提取 token：优先 "Bearer xxx"，其次 JWT 形（含两个点、长度>20） */
    private static String pickBearer(String value) {
        var v = value.trim();
        var lower = v.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("bearer ")) {
            return v.substring(7).trim();
        }
        if (v.length() > 20 && v.indexOf('.') > 0 && v.indexOf('.', v.indexOf('.') + 1) > 0) {
            return v;
        }
        return "";
    }

    /** 注入桌面模式脚本（覆写 navigator.userAgent/UserAgentData + 强制桌面 viewport） */
    private void injectDesktopMode(WebView view) {
        view.evaluateJavascript(DESKTOP_FIX_JS, null);
    }

    /** 是否为页面 URL（路径最后一段无文件扩展名，或 .html/.htm） */
    private static boolean isHtmlPage(String url) {
        try {
            var path = java.net.URI.create(url).getPath();
            if (path == null || path.isEmpty()) return true;
            var seg = path.substring(path.lastIndexOf('/') + 1);
            return !seg.contains(".");
        } catch (Exception e) {
            return true;
        }
    }

    private static OkHttpClient sHttp;

    private static OkHttpClient httpClient() {
        if (sHttp != null) return sHttp;
        synchronized (WebViewLoginFragment.class) {
            if (sHttp != null) return sHttp;
            sHttp = new OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build();
            return sHttp;
        }
    }

    /**
     * 光鸭主文档 HTML 预注入：用 OkHttp 重新抓取页面（带 WebView 现有 Cookie），
     * 在 &lt;/head&gt; 前插入同步桌面脚本，返回注入后的 HTML。
     * 任何失败/非 HTML/CSP 限制均返回 null，降级让 WebView 自行加载（原有兜底注入仍生效）。
     */
    private WebResourceResponse fetchAndInjectDesktop(String url) {
        try {
            var builder = new Request.Builder().url(url).header("User-Agent", DESKTOP_UA);
            var cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null && !cookies.isEmpty()) {
                builder.header("Cookie", cookies);
            }
            try (var resp = httpClient().newCall(builder.build()).execute()) {
                if (!resp.isSuccessful()) return null;
                var body = resp.body();
                if (body == null) return null;
                var html = body.string();
                var type = resp.headers().get("Content-Type");
                if (type == null || !type.toLowerCase(java.util.Locale.ROOT).contains("text/html")) return null;
                // CSP 限制 inline script 时不注入，避免页面 JS 全被 CSP 拦下导致站点不可用（NEW-H-xx）
                var csp = resp.headers().get("Content-Security-Policy");
                if (csp != null && !csp.contains("unsafe-inline")) return null;
                var injected = html.replace("</head>",
                        "<script>" + DESKTOP_FIX_JS + "</script></head>");
                if (injected.length() == html.length()) return null; // 无 </head>，放弃注入
                return new WebResourceResponse("text/html", "utf-8",
                        new java.io.ByteArrayInputStream(injected.getBytes(StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            LogHelp.w(TAG, "光鸭 HTML 预注入失败，降级默认加载: " + url, e);
            return null;
        }
    }

    /**
     * 完成键：按网盘类型提取凭据并保存
     */
    private void onDone() {
        if (PROVIDER_GUANGYA.equals(provider)) {
            onDoneGuangya();
        } else if (PROVIDER_QUARK.equals(provider)) {
            onDoneQuark();
        } else if (PROVIDER_123.equals(provider)) {
            onDone123();
        } else if (PROVIDER_189.equals(provider)) {
            onDone189();
        } else if (PROVIDER_BAIDU.equals(provider)) {
            onDoneBaidu();
        } else if (PROVIDER_WO.equals(provider)) {
            onDoneWo();
        } else {
            onDone139();
        }
    }

    /**
     * 夸克：合并读取登录页相关子域的 Cookie（登录态 cookie 可能在 pan/drive/drive-pc/passport 域，
     * 且新版本登录态已不依赖 __puus），点「完成」后台验证通过后保存
     * 幂等复用已存在夸克账号 id
     */
    private void onDoneQuark() {
        var ck = captureQuarkCookie();
        if (ck == null || ck.isEmpty()) {
            Toast.makeText(getActivity(), R.string.toast_webview_no_auth, Toast.LENGTH_LONG).show();
            return;
        }
        LogHelp.i(TAG, "夸克 Cookie 捕获: len=" + ck.length());
        btnDone.setEnabled(false);
        btnDone.setText(R.string.testing_connection);
        var id = "quark_" + System.currentTimeMillis();
        // 幂等：复用已存在夸克账号 id，避免重复保存出现多个账号
        var existing = CloudAccountStore.list().stream()
                .filter(a -> CloudAccount.PROVIDER_QUARK.equals(a.provider))
                .findFirst().orElse(null);
        if (existing != null) id = existing.id;
        var accountId = id;
        new Thread(() -> {
            try {
                // 临时保存用于验证，成功后保留；失败则回滚
                EncryptedCredStore.put(accountId, "cookie", ck);
                var provider = com.suileyan.cloud.ProviderRegistry.forAccount(
                        new CloudAccount(accountId, CloudAccount.PROVIDER_QUARK, "", "", System.currentTimeMillis()));
                var ok = provider != null && provider.testConnection();
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    btnDone.setEnabled(true);
                    btnDone.setText(R.string.webview_login_done);
                    if (ok) {
                        saveAccountQuark(accountId, ck);
                    } else {
                        // 验证失败：打印捕获的 cookie 键名便于排查，并提示登录可能未完成
                        var names = new java.util.ArrayList<String>();
                        for (var pair : ck.split(";")) {
                            var t = pair.trim();
                            if (!t.isEmpty()) names.add(t.split("=", 2)[0]);
                        }
                        LogHelp.w(TAG, "夸克 Cookie 验证失败，捕获键名: " + String.join(",", names));
                        EncryptedCredStore.removeAccount(accountId);
                        Toast.makeText(getActivity(), R.string.toast_quark_login_incomplete, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                LogHelp.e(TAG, "夸克 Cookie 验证失败", e);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        btnDone.setEnabled(true);
                        btnDone.setText(R.string.webview_login_done);
                        Toast.makeText(getActivity(), R.string.toast_cloud_auth_invalid, Toast.LENGTH_LONG).show();
                    });
                }
            }
        }, "XpMiBackup-quark-validate").start();
    }

    /**
     * 合并夸克登录相关子域的 Cookie（按 cookie 名去重，跨域同名取首个）
     * 夸克登录态可能落在 pan.quark.cn / drive.quark.cn / drive-pc.quark.cn / passport.quark.cn
     */
    private String captureQuarkCookie() {
        var sb = new StringBuilder();
        var seen = new java.util.HashSet<String>();
        for (var base : new String[]{
                "https://pan.quark.cn/",
                "https://drive.quark.cn/",
                "https://drive-pc.quark.cn/",
                "https://passport.quark.cn/"}) {
            var ck = CookieManager.getInstance().getCookie(base);
            if (ck == null) continue;
            for (var pair : ck.split(";")) {
                var p = pair.trim();
                if (p.isEmpty()) continue;
                var name = p.split("=", 2)[0];
                if (seen.add(name)) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(p);
                }
            }
        }
        return sb.toString();
    }

    /**
     * 捕获天翼 SSON Cookie（登录态可能落在 cloud.189.cn / open.e.189.cn / api.cloud.189.cn，
     * 仅提取名为 SSON 的键值，取最长者）
     */
    private String capture189Cookie() {
        String best = "";
        for (var base : new String[]{
                "https://cloud.189.cn/",
                "https://open.e.189.cn/",
                "https://api.cloud.189.cn/"}) {
            var ck = CookieManager.getInstance().getCookie(base);
            if (ck == null) continue;
            for (var pair : ck.split(";")) {
                var p = pair.trim();
                var eq = p.indexOf('=');
                if (eq <= 0) continue;
                if ("SSON".equals(p.substring(0, eq))) {
                    var value = p.substring(eq + 1);
                    if (value.length() > best.length()) best = value;
                }
            }
        }
        return best;
    }

    /** 严格判断主机名是否属于天翼云盘（cloud.189.cn / open.e.189.cn，防相似域名，HIGH-18 风格） */
    private static boolean is189Host(String host) {
        if (host == null) return false;
        var h = host.toLowerCase(java.util.Locale.ROOT);
        return h.equals("cloud.189.cn") || h.endsWith(".cloud.189.cn")
                || h.equals("open.e.189.cn") || h.endsWith(".open.e.189.cn");
    }

    /** 保存夸克账号（Cookie 已在后台验证通过） */
    private void saveAccountQuark(String id, String cookie) {
        try {
            CloudAccountStore.add(new CloudAccount(id, CloudAccount.PROVIDER_QUARK, "",
                    getString(R.string.cloud_provider_quark), System.currentTimeMillis()));
            // 校验用的临时凭据保留为正式凭据
            LogHelp.i(TAG, "夸克账号已保存: " + id + " cookie_len=" + cookie.length());
            finishSave();
        } catch (Exception e) {
            LogHelp.e(TAG, "save 夸克 account failed", e);
            Toast.makeText(getActivity(), R.string.toast_cloud_account_save_failed, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 天翼云盘「完成」：捕获 SSON Cookie 后台验证通过后保存
     * 幂等复用已存在天翼账号 id
     */
    private void onDone189() {
        var sson = capture189Cookie();
        if (sson == null || sson.isEmpty()) {
            Toast.makeText(getActivity(), R.string.toast_webview_no_auth, Toast.LENGTH_LONG).show();
            return;
        }
        LogHelp.i(TAG, "天翼 SSON 捕获: len=" + sson.length());
        btnDone.setEnabled(false);
        btnDone.setText(R.string.testing_connection);
        var id = "189_" + System.currentTimeMillis();
        // 幂等：复用已存在天翼账号 id，避免重复保存出现多个账号
        var existing = CloudAccountStore.list().stream()
                .filter(a -> CloudAccount.PROVIDER_189.equals(a.provider))
                .findFirst().orElse(null);
        if (existing != null) id = existing.id;
        var accountId = id;
        // 幂等复用场景：先备份旧 SSON，验证失败时恢复而非删除账号（避免误删旧有效凭据，HIGH-01）
        final var prevSson = existing != null ? EncryptedCredStore.get(accountId, "sson_cookie") : null;
        new Thread(() -> {
            try {
                // 临时保存用于验证，成功后保留；失败则回滚（有旧值恢复旧值，无旧值才删账号）
                EncryptedCredStore.put(accountId, "sson_cookie", sson);
                var provider = com.suileyan.cloud.ProviderRegistry.forAccount(
                        new CloudAccount(accountId, CloudAccount.PROVIDER_189, "", "", System.currentTimeMillis()));
                var ok = provider != null && provider.testConnection();
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    btnDone.setEnabled(true);
                    btnDone.setText(R.string.webview_login_done);
                    if (ok) {
                        saveAccount189(accountId);
                    } else {
                        // 验证失败：恢复旧凭据（若原本无账号则删除临时凭据），提示登录可能未完成
                        if (prevSson != null && !prevSson.isEmpty()) {
                            EncryptedCredStore.put(accountId, "sson_cookie", prevSson);
                            LogHelp.w(TAG, "天翼验证失败，已恢复旧 SSON: " + accountId);
                        } else {
                            EncryptedCredStore.removeAccount(accountId);
                        }
                        Toast.makeText(getActivity(), R.string.toast_cloud_auth_invalid, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                LogHelp.e(TAG, "天翼验证失败", e);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        btnDone.setEnabled(true);
                        btnDone.setText(R.string.webview_login_done);
                        Toast.makeText(getActivity(), R.string.toast_cloud_auth_invalid, Toast.LENGTH_LONG).show();
                    });
                }
            }
        }, "XpMiBackup-189-validate").start();
    }

    /** 保存天翼账号（testConnection 已把 access_token/refresh_token/session_key 持久化；uid 取 login_name 或 JWT 解码） */
    private void saveAccount189(String id) {
        try {
            var uid = EncryptedCredStore.get(id, "login_name");
            if (uid == null || uid.isEmpty()) {
                uid = com.suileyan.cloud.AccountDisplay.decodeUid(EncryptedCredStore.get(id, "access_token"));
            }
            if (uid == null) uid = "";
            CloudAccountStore.add(new CloudAccount(id, CloudAccount.PROVIDER_189, uid,
                    getString(R.string.cloud_provider_189), System.currentTimeMillis()));
            // uid 为登录名（手机号/邮箱），日志只记长度不记明文
            LogHelp.i(TAG, "天翼账号已保存: " + id + " uidLen=" + (uid != null ? uid.length() : 0));
            finishSave();
        } catch (Exception e) {
            LogHelp.e(TAG, "save 天翼 account failed", e);
            Toast.makeText(getActivity(), R.string.toast_cloud_account_save_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** 严格判断主机名是否属于百度网盘（pan.baidu.com / passport.baidu.com，防相似域名，HIGH-18 风格） */
    private static boolean isBaiduHost(String host) {
        if (host == null) return false;
        var h = host.toLowerCase(java.util.Locale.ROOT);
        return h.equals("pan.baidu.com") || h.endsWith(".pan.baidu.com")
                || h.equals("passport.baidu.com") || h.endsWith(".passport.baidu.com");
    }

    /**
     * 捕获百度登录 Cookie（按 cookie 名去重合并，仿 captureQuarkCookie），
     * 覆盖 pan.baidu.com 与 passport.baidu.com 两个域；登录态核心为 BDUSS
     */
    private String captureBaiduCookie() {
        var sb = new StringBuilder();
        var seen = new java.util.HashSet<String>();
        for (var base : new String[]{"https://pan.baidu.com/", "https://passport.baidu.com/"}) {
            var ck = CookieManager.getInstance().getCookie(base);
            if (ck == null) continue;
            for (var pair : ck.split(";")) {
                var p = pair.trim();
                if (p.isEmpty()) continue;
                var name = p.split("=", 2)[0];
                if (seen.add(name)) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(p);
                }
            }
        }
        return sb.toString();
    }

    /**
     * 百度「完成」：捕获 Cookie（必须含 BDUSS）后台验证通过后保存
     * 幂等复用已存在百度账号 id
     */
    private void onDoneBaidu() {
        var ck = captureBaiduCookie();
        if (ck == null || ck.isEmpty()) {
            Toast.makeText(getActivity(), R.string.toast_webview_no_auth, Toast.LENGTH_LONG).show();
            return;
        }
        // BDUSS 是 xpan 认证核心，缺失视为未完成登录
        if (!containsCookie(ck, "BDUSS")) {
            Toast.makeText(getActivity(), R.string.toast_cloud_auth_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        LogHelp.i(TAG, "百度 Cookie 捕获: len=" + ck.length());
        btnDone.setEnabled(false);
        btnDone.setText(R.string.testing_connection);
        var id = "baidu_" + System.currentTimeMillis();
        // 幂等：复用已存在百度账号 id，避免重复保存出现多个账号
        var existing = CloudAccountStore.list().stream()
                .filter(a -> CloudAccount.PROVIDER_BAIDU.equals(a.provider))
                .findFirst().orElse(null);
        if (existing != null) id = existing.id;
        var accountId = id;
        // 幂等复用场景：先备份旧 Cookie，验证失败时恢复而非删除账号（避免误删旧有效凭据，HIGH-01）
        final var prevCk = existing != null ? EncryptedCredStore.get(accountId, "cookie") : null;
        new Thread(() -> {
            try {
                // 临时保存用于验证，成功后保留；失败则回滚（有旧值恢复旧值，无旧值才删账号）
                EncryptedCredStore.put(accountId, "cookie", ck);
                var provider = com.suileyan.cloud.ProviderRegistry.forAccount(
                        new CloudAccount(accountId, CloudAccount.PROVIDER_BAIDU, "", "", System.currentTimeMillis()));
                var ok = provider != null && provider.testConnection();
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    btnDone.setEnabled(true);
                    btnDone.setText(R.string.webview_login_done);
                    if (ok) {
                        saveAccountBaidu(accountId);
                    } else {
                        // 验证失败：恢复旧凭据（若原本无账号则删除临时凭据），提示登录可能未完成
                        if (prevCk != null && !prevCk.isEmpty()) {
                            EncryptedCredStore.put(accountId, "cookie", prevCk);
                            LogHelp.w(TAG, "百度验证失败，已恢复旧 Cookie: " + accountId);
                        } else {
                            EncryptedCredStore.removeAccount(accountId);
                        }
                        Toast.makeText(getActivity(), R.string.toast_cloud_auth_invalid, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                LogHelp.e(TAG, "百度验证失败", e);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        btnDone.setEnabled(true);
                        btnDone.setText(R.string.webview_login_done);
                        Toast.makeText(getActivity(), R.string.toast_cloud_auth_invalid, Toast.LENGTH_LONG).show();
                    });
                }
            }
        }, "XpMiBackup-baidu-validate").start();
    }

    /** 保存百度账号（uid 留空串，列表显示「百度网盘」） */
    private void saveAccountBaidu(String id) {
        try {
            CloudAccountStore.add(new CloudAccount(id, CloudAccount.PROVIDER_BAIDU, "",
                    getString(R.string.cloud_provider_baidu), System.currentTimeMillis()));
            LogHelp.i(TAG, "百度账号已保存: " + id);
            finishSave();
        } catch (Exception e) {
            LogHelp.e(TAG, "save 百度 account failed", e);
            Toast.makeText(getActivity(), R.string.toast_cloud_account_save_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** 严格判断主机名是否属于联通沃盘（pan.wo.cn / panservice.mail.wo.cn，防相似域名，HIGH-18 风格） */
    private static boolean isWoHost(String host) {
        if (host == null) return false;
        var h = host.toLowerCase(java.util.Locale.ROOT);
        return h.equals("pan.wo.cn") || h.endsWith(".pan.wo.cn")
                || h.equals("panservice.mail.wo.cn") || h.endsWith(".panservice.mail.wo.cn");
    }

    /**
     * 沃盘：捕获 dispatcher 请求的 Accesstoken 请求头（登录后前端每个 dispatcher API 请求必带 UUID 形 token）
     * 遍历全部请求头（不假设 key 大小写），只接受严格 host 下的请求（HIGH-18）
     */
    private void interceptWo(WebResourceRequest request) {
        var host = request.getUrl().getHost();
        if (host == null || !isWoHost(host)) return;
        var headers = request.getRequestHeaders();
        if (headers == null) return;
        for (var entry : headers.entrySet()) {
            var name = entry.getKey() == null ? "" : entry.getKey().toLowerCase(java.util.Locale.ROOT);
            var value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (name.equals("accesstoken") && !value.isEmpty() && value.length() > capturedWoToken.length()) {
                capturedWoToken = value;
                LogHelp.i(TAG, "captured 沃盘 Accesstoken from request, host=" + host + " len=" + value.length());
            }
        }
    }

    /**
     * 沃盘「完成」：token 为空则提示；后台 testConnection 通过后保存（幂等复用已存在沃盘账号 id）。
     * 验证失败恢复旧 access_token（幂等复用场景，避免误删旧有效凭据，HIGH-01）
     */
    private void onDoneWo() {
        var token = capturedWoToken;
        if (token.isEmpty()) {
            Toast.makeText(getActivity(), R.string.toast_webview_no_auth, Toast.LENGTH_LONG).show();
            return;
        }
        LogHelp.i(TAG, "沃盘 token 捕获: len=" + token.length());
        btnDone.setEnabled(false);
        btnDone.setText(R.string.testing_connection);
        var id = "wo_" + System.currentTimeMillis();
        // 幂等：复用已存在沃盘账号 id，避免重复保存出现多个账号
        var existing = CloudAccountStore.list().stream()
                .filter(a -> CloudAccount.PROVIDER_WO.equals(a.provider))
                .findFirst().orElse(null);
        if (existing != null) id = existing.id;
        var accountId = id;
        // 幂等复用场景：先备份旧 access_token，验证失败时恢复而非删除账号（HIGH-01）
        final var prevAt = existing != null ? EncryptedCredStore.get(accountId, "access_token") : null;
        new Thread(() -> {
            try {
                // 临时保存用于验证，成功后保留；失败则回滚（有旧值恢复旧值，无旧值才删账号）
                EncryptedCredStore.put(accountId, "access_token", token);
                var provider = com.suileyan.cloud.ProviderRegistry.forAccount(
                        new CloudAccount(accountId, CloudAccount.PROVIDER_WO, "", "", System.currentTimeMillis()));
                var ok = provider != null && provider.testConnection();
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    btnDone.setEnabled(true);
                    btnDone.setText(R.string.webview_login_done);
                    if (ok) {
                        saveAccountWo(accountId);
                    } else {
                        if (prevAt != null && !prevAt.isEmpty()) {
                            EncryptedCredStore.put(accountId, "access_token", prevAt);
                            LogHelp.w(TAG, "沃盘验证失败，已恢复旧 access_token: " + accountId);
                        } else {
                            EncryptedCredStore.removeAccount(accountId);
                        }
                        Toast.makeText(getActivity(), R.string.toast_cloud_auth_invalid, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                LogHelp.e(TAG, "沃盘验证失败", e);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        btnDone.setEnabled(true);
                        btnDone.setText(R.string.webview_login_done);
                        Toast.makeText(getActivity(), R.string.toast_cloud_auth_invalid, Toast.LENGTH_LONG).show();
                    });
                }
            }
        }, "XpMiBackup-wo-validate").start();
    }

    /** 保存沃盘账号（access_token + 可选 refresh_token 加密存储；uid 留空展示「联通沃盘」） */
    private void saveAccountWo(String id) {
        try {
            CloudAccountStore.add(new CloudAccount(id, CloudAccount.PROVIDER_WO, "",
                    getString(R.string.cloud_provider_wo), System.currentTimeMillis()));
            EncryptedCredStore.put(id, "access_token", capturedWoToken);
            if (capturedWoRefresh != null && !capturedWoRefresh.isEmpty()) {
                EncryptedCredStore.put(id, "refresh_token", capturedWoRefresh);
            }
            LogHelp.i(TAG, "沃盘账号已保存: " + id
                    + " refresh=" + (capturedWoRefresh != null && !capturedWoRefresh.isEmpty()));
            finishSave();
        } catch (Exception e) {
            LogHelp.e(TAG, "save 沃盘 account failed", e);
            Toast.makeText(getActivity(), R.string.toast_cloud_account_save_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** 判断 cookie 串中是否含指定键名（如 BDUSS） */
    private static boolean containsCookie(String cookieStr, String name) {
        if (cookieStr == null || name == null) return false;
        for (var pair : cookieStr.split(";")) {
            var t = pair.trim();
            if (!t.isEmpty() && t.split("=", 2)[0].equals(name)) return true;
        }
        return false;
    }

    /**
     * 123云盘「完成」：token 为空时现场重扫一次 localStorage（防"登录后才出现 API 请求"时序），
     * 仍为空则提示；否则后台验证通过后保存。幂等复用已存在 123 账号 id。
     */
    private void onDone123() {
        var token = captured123Bearer;
        if (token.isEmpty()) {
            webView.evaluateJavascript(EXTRACT_JS_123, value -> {
                if (value != null && !"null".equals(value)) {
                    var cleaned = value.replace("\"", "").trim();
                    if (cleaned.length() > 20) {
                        captured123Bearer = cleaned;
                        bearer123FromRequest = true;
                        doSave123();
                    } else {
                        Toast.makeText(getActivity(), R.string.toast_webview_no_auth, Toast.LENGTH_LONG).show();
                    }
                } else {
                    Toast.makeText(getActivity(), R.string.toast_webview_no_auth, Toast.LENGTH_LONG).show();
                }
            });
            return;
        }
        doSave123();
    }

    /** 123云盘：验证并保存捕获的 token */
    private void doSave123() {
        var token = captured123Bearer;
        if (token.isEmpty()) {
            Toast.makeText(getActivity(), R.string.toast_webview_no_auth, Toast.LENGTH_LONG).show();
            return;
        }
        // 仅 localStorage 兜底捕获且长度可疑（非 JWT 形）时拒绝
        if (!bearer123FromRequest && token.length() <= 20) {
            Toast.makeText(getActivity(), R.string.toast_cloud_auth_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        btnDone.setEnabled(false);
        btnDone.setText(R.string.testing_connection);
        var id = "123_" + System.currentTimeMillis();
        // 幂等：复用已存在 123 账号 id，避免重复保存出现多个账号
        var existing = CloudAccountStore.list().stream()
                .filter(a -> CloudAccount.PROVIDER_123.equals(a.provider))
                .findFirst().orElse(null);
        if (existing != null) id = existing.id;
        var accountId = id;
        // 归一化：与参考 self.authorization='Bearer '+token 一致
        var authValue = token.toLowerCase(java.util.Locale.ROOT).startsWith("bearer ") ? token : "Bearer " + token;
        new Thread(() -> {
            try {
                // 临时保存用于验证，成功后保留；失败则回滚
                EncryptedCredStore.put(accountId, "authorization", authValue);
                var provider = com.suileyan.cloud.ProviderRegistry.forAccount(
                        new CloudAccount(accountId, CloudAccount.PROVIDER_123, "", "", System.currentTimeMillis()));
                var ok = provider != null && provider.testConnection();
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    btnDone.setEnabled(true);
                    btnDone.setText(R.string.webview_login_done);
                    if (ok) {
                        saveAccount123(accountId, token);
                    } else {
                        EncryptedCredStore.removeAccount(accountId);
                        Toast.makeText(getActivity(), R.string.toast_cloud_auth_invalid, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                LogHelp.e(TAG, "123云盘验证失败", e);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        btnDone.setEnabled(true);
                        btnDone.setText(R.string.webview_login_done);
                        Toast.makeText(getActivity(), R.string.toast_cloud_auth_invalid, Toast.LENGTH_LONG).show();
                    });
                }
            }
        }, "XpMiBackup-123-validate").start();
    }

    /** 保存 123 账号（token 已在后台验证通过）；uid 从 JWT payload 解码回填 */
    private void saveAccount123(String id, String token) {
        try {
            var uid = com.suileyan.cloud.AccountDisplay.decodeUid(token);
            CloudAccountStore.add(new CloudAccount(id, CloudAccount.PROVIDER_123, uid,
                    getString(R.string.cloud_provider_123), System.currentTimeMillis()));
            LogHelp.i(TAG, "123云盘账号已保存: " + id + " uid=" + uid + " token_len=" + token.length());
            finishSave();
        } catch (Exception e) {
            LogHelp.e(TAG, "save 123 account failed", e);
            Toast.makeText(getActivity(), R.string.toast_cloud_account_save_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void onDone139() {
        var auth = capturedAuth;
        if (auth.isEmpty()) {
            Toast.makeText(getActivity(), R.string.toast_webview_no_auth, Toast.LENGTH_LONG).show();
            return;
        }
        if (authFromRequest) {
            if (looksLikeBasicCredential(auth)) {
                saveAccount139(auth);
            } else {
                LogHelp.e(TAG, "captured Authorization fails basic format check");
                Toast.makeText(getActivity(), R.string.toast_cloud_auth_invalid, Toast.LENGTH_LONG).show();
            }
            return;
        }
        btnDone.setEnabled(false);
        btnDone.setText(R.string.testing_connection);
        var host = capturedHost;
        new Thread(() -> {
            var ok = Yun139Login.validate(auth, host);
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                btnDone.setEnabled(true);
                btnDone.setText(R.string.webview_login_done);
                if (ok) {
                    saveAccount139(auth);
                } else {
                    Toast.makeText(getActivity(), R.string.toast_cloud_auth_invalid, Toast.LENGTH_LONG).show();
                }
            });
        }, "XpMiBackup-139-validate").start();
    }

    /** 光鸭：Bearer 来自真实请求头即直接保存（登录成功必然有效）；仅 localStorage 提取也接受 */
    private void onDoneGuangya() {
        var access = capturedBearer;
        if (access.isEmpty()) {
            Toast.makeText(getActivity(), R.string.toast_webview_no_auth, Toast.LENGTH_LONG).show();
            return;
        }
        if (bearerFromRequest || access.length() > 20) {
            saveAccountGuangya(access, capturedRefresh);
        } else {
            Toast.makeText(getActivity(), R.string.toast_cloud_auth_invalid, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 基本格式校验：Authorization（Basic 后内容）能 base64 解码且解码后含冒号（"xxx:手机号" 结构）
     */
    private static boolean looksLikeBasicCredential(String authorization) {
        try {
            var decoded = new String(Base64.getDecoder().decode(authorization), StandardCharsets.UTF_8);
            return decoded.indexOf(':') > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 保存 139 账号 */
    private void saveAccount139(String authorization) {
        try {
            var account = decodeAccount(authorization);
            var name = account.isEmpty() ? getString(R.string.cloud_account_default_name)
                    : getString(R.string.cloud_account_name_format, account);
            var id = "139_" + (account.isEmpty() ? System.currentTimeMillis() : account);
            CloudAccountStore.add(new CloudAccount(id, CloudAccount.PROVIDER_139, account, name, System.currentTimeMillis()));
            EncryptedCredStore.put(id, "authorization", authorization);
            if (capturedHost != null && !capturedHost.isEmpty()) {
                EncryptedCredStore.put(id, "host", capturedHost);
            }
            finishSave();
        } catch (Exception e) {
            LogHelp.e(TAG, "save 139 account failed", e);
            Toast.makeText(getActivity(), R.string.toast_cloud_account_save_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** 保存光鸭账号（access_token + refresh_token 加密存储） */
    private void saveAccountGuangya(String access, String refresh) {
        try {
            // 幂等：已存在光鸭账号则复用其 id（更新 token），避免重复保存出现多个账号
            var existing = CloudAccountStore.list().stream()
                    .filter(a -> CloudAccount.PROVIDER_GUANGYA.equals(a.provider))
                    .findFirst().orElse(null);
            var id = existing != null ? existing.id : "guangya_" + System.currentTimeMillis();
            // 从 access_token 解码账号标识（JWT payload），用于列表展示区分同网盘不同账户
            var uid = com.suileyan.cloud.AccountDisplay.decodeUid(access);
            if (existing == null) {
                CloudAccountStore.add(new CloudAccount(id, CloudAccount.PROVIDER_GUANGYA, uid,
                        getString(R.string.cloud_provider_guangya), System.currentTimeMillis()));
            } else if (!uid.isEmpty() && !uid.equals(existing.account)) {
                CloudAccountStore.add(new CloudAccount(existing.id, CloudAccount.PROVIDER_GUANGYA, uid,
                        existing.name, existing.createdAt));
            }
            EncryptedCredStore.put(id, "access_token", access);
            if (refresh != null && !refresh.isEmpty()) {
                EncryptedCredStore.put(id, "refresh_token", refresh);
            }
            LogHelp.i(TAG, "光鸭账号已保存: " + id + " uid=" + uid
                    + " refresh=" + (refresh != null && !refresh.isEmpty()));
            finishSave();
        } catch (Exception e) {
            LogHelp.e(TAG, "save 光鸭 account failed", e);
            Toast.makeText(getActivity(), R.string.toast_cloud_account_save_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void finishSave() {
        // 失效凭证检查缓存，返回账号页后立即重新校验（避免显示旧失效状态）
        com.suileyan.cloud.CredentialChecker.invalidateAll();
        Toast.makeText(getActivity(), R.string.toast_cloud_account_saved, Toast.LENGTH_SHORT).show();
        var fm = getFragmentManager();
        if (fm != null && fm.getBackStackEntryCount() > 0) {
            // 直接清空 overlay 二级页面栈（网盘选择页 + 本登录页），回到云盘账号页
            fm.popBackStack(null, android.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }
        // 直接刷新账号页列表（不依赖 onResume 生命周期时序，保证保存后立即可见）
        if (fm != null) {
            var accountFrag = (AccountConfigFragment) fm.findFragmentByTag("tab-account");
            if (accountFrag != null) {
                accountFrag.refreshList();
            }
        }
    }

    /**
     * 从 Authorization 解码账号：base64 解码后按冒号分段取第二段（与 alist 驱动一致）
     */
    private static String decodeAccount(String authorization) {
        try {
            var decoded = new String(Base64.getDecoder().decode(authorization), StandardCharsets.UTF_8);
            var parts = decoded.split(":");
            return parts.length > 1 ? parts[1] : "";
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public void onDestroyView() {
        if (webView != null) {
            webView.stopLoading();
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        // 清理全部 Cookie（含持久化登录态），避免登录凭据残留在 WebView 中（MED-09 / NEW-M-06）
        try {
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
        } catch (Throwable ignored) {
        }
        super.onDestroyView();
    }
}
