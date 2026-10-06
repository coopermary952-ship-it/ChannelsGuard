package com.xingyuan.channelsguard;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 视频号守门员核心服务（v2.3）。
 *
 * 原理：
 * 1. 监听微信（com.tencent.mm）的窗口切换事件，类名包含 "finder" 即为视频号界面。
 * 2. 严格模式：进入视频号后累计滚动事件的次数（不再限定必须发生在同一秒内），
 *    达到灵敏度阈值即为"你在往下刷"，执行全局返回。
 *    v2.1/v2.2 用「1 秒内连续 ≥4 次」判定，忽略了"看完一段再滑"的间隔，
 *    导致连续刷数个视频都拦不住，本版改为累计计数 + 灵敏度可配置。
 * 3. 返回可能被部分 ROM / 微信吃掉，因此一次 BACK 后会在 0.7s / 1.5s 各复核一次，
 *    只要仍在视频号界面就补发 BACK（最多 3 次）。
 * 4. 限时模式：进入视频号后启动倒计时，剩余 1 分钟时提醒，到点自动返回。
 *
 * 实验功能：content_watch —— 若你的微信压根不发出滚动事件（调试日志里看不到
 * "滑动"记录），可用画面内容指纹兜底检测视频切换。默认关闭。
 *
 * 调试：最近 30 条关键事件会记录到 SharedPreferences，主界面可查看、可复制。
 */
public class GuardService extends AccessibilityService {

    static final String PREFS = "guard_prefs";
    static final String KEY_MODE = "mode";            // strict | timed | off
    static final String KEY_TIMED_MINUTES = "timed_minutes";
    static final String KEY_SENSITIVITY = "sensitivity";      // 触发拦截所需的累计滚动次数
    static final String KEY_CONTENT_WATCH = "content_watch";  // 实验：内容指纹兜底
    static final String KEY_TOTAL_BLOCKS = "total_blocks";
    static final String KEY_FIRST_DATE = "first_date";
    static final String KEY_DAY_PREFIX = "count_";    // count_yyyy-MM-dd
    static final String KEY_DEBUG_LOG = "debug_log";
    static final String KEY_RAW_LOG = "raw_log";          // 原始事件日志（排障用）
    static final String KEY_LOCK_UNTIL = "lock_until"; // 严格模式承诺期截止时间（毫秒）

    static final String MODE_STRICT = "strict";
    static final String MODE_TIMED = "timed";
    static final String MODE_OFF = "off";

    private static final long ENTER_GRACE_MS = 900;        // 进入界面后的初始化宽限
    private static final int GRACE_BURST_LIMIT = 3;        // 宽限期内连滑这么多次也算主动刷
    private static final long BLOCK_DEBOUNCE_MS = 1200;    // 两次拦截的最小间隔
    private static final long BACK_RETRY_DELAY_MS = 600;   // 返回补发的等待时间
    private static final long WARN_BEFORE_MS = 60_000;
    private static final int DEBUG_LOG_MAX = 30;
    private static final int RAW_LOG_MAX = 60;

    /**
     * 「粘性窗口」：命中视频号后的一小段时间内，即使收到指向其它类名的窗口事件
     * （微信滑动切视频时偶尔会抛出一个半屏/子层窗口），也仍按视频号处理。
     * 一旦收到明确的「已离开」类名（聊天列表、桌面等）立即清零，避免误拦聊天。
     */
    private static final long STICKY_WINDOW_MS = 8000;

    /** 常驻通知：让进程前台化，降低被系统回收的概率。 */
    private static final int NOTIF_ID = 20260;
    private static final String CHANNEL_ID = "guard_running";

    /** 内容指纹轮询间隔（仅实验功能开启时运行）。 */
    private static final long CONTENT_POLL_MS = 800;
    private static final int CONTENT_MAX_NODES = 220;
    private static final long CONTENT_BASELINE_DELAY_MS = 2500; // 先等界面稳定再取基准指纹

    private boolean inChannels = false;
    private long enterTimeMs = 0;
    private long lastBlockMs = 0;
    private int scrollAccumulator = 0;   // 进入视频号后的累计滚动次数
    private int loggedScrolls = 0;       // 已写入调试日志的滚动条数（避免刷屏）
    private long lastFinderHitMs = 0;    // 最近一次命中视频号类名的时间
    private long lastRawScrollLogMs = 0; // 原始日志中滚动行的节流时间
    private boolean leftConfirmed = false; // 已收到「明确离开视频号」的窗口事件
    private String lastFingerprint = null;
    private boolean fingerprintBaselined = false;

    private Handler handler;
    private Runnable exitRunnable;
    private Runnable warnRunnable;
    private Runnable contentPollRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        startForegroundIfAllowed();
    }

    // ---------------- 常驻通知（提升存活率） ----------------

    /**
     * 把服务提升为前台服务。国产 ROM 清理后台时，前台进程的存活概率明显高于后台进程。
     * 失败不影响拦截功能本身，只是少了这层保护。
     */
    private void startForegroundIfAllowed() {
        try {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return; // 未授权通知，前台通知会被系统丢弃，跳过即可
            }
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) {
                return;
            }
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "守门员运行状态", NotificationManager.IMPORTANCE_MIN);
            channel.setDescription("显示视频号守门员正在运行及今日拦截次数");
            nm.createNotificationChannel(channel);
            startForeground(NOTIF_ID, buildNotification());
        } catch (Throwable t) {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            logEvent(p, "前台服务启动失败: " + t.getClass().getSimpleName());
        }
    }

    private Notification buildNotification() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        int todayCount = prefs.getInt(KEY_DAY_PREFIX + today, 0);
        String mode = effectiveMode(prefs);
        String modeText = MODE_TIMED.equals(mode) ? "限时模式"
                : MODE_OFF.equals(mode) ? "已暂停" : "严格模式";

        Intent intent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 1, intent, flags);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("视频号守门员运行中 · " + modeText)
                .setContentText("今日已拦截 " + todayCount + " 次")
                .setContentIntent(pi)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    /** 每次拦截后刷新通知里的计数。 */
    private void refreshNotification() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(NOTIF_ID, buildNotification());
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String mode = effectiveMode(prefs);
        if (MODE_OFF.equals(mode)) {
            resetState();
            return;
        }

        String pkg = String.valueOf(event.getPackageName());
        if (!"com.tencent.mm".equals(pkg)) {
            resetState();
            return;
        }

        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String cls = String.valueOf(event.getClassName());
            logRaw(prefs, "WIN  " + cls);

            boolean nowInChannels = isFinderClass(cls);
            if (nowInChannels) {
                lastFinderHitMs = System.currentTimeMillis();
                if (!inChannels) {
                    onEnterChannels(prefs, mode, cls);
                }
            } else if (isClearlyOutside(cls)) {
                // 明确回到了聊天/桌面等地方：解除粘性窗口，避免误拦聊天列表
                lastFinderHitMs = 0;
                leftConfirmed = true;
                if (inChannels) {
                    logEvent(prefs, "离开视频号: " + cls);
                    leaveChannels();
                }
            } else if (inChannels) {
                // 未命中视频号但也不明确在外面 —— 多半是微信内部的半屏/子层，
                // 保持状态不动，靠粘性窗口兜住随后到来的滚动事件
                logEvent(prefs, "视频号内的其它窗口(保持守护): " + cls);
            }
            inChannels = nowInChannels || (!isClearlyOutside(cls) && isSticky());
        } else if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED && (inChannels || isSticky())) {
            if (!MODE_STRICT.equals(mode)) {
                return; // 限时模式下允许自由滑动，到点统一退出
            }
            int dy = 0;
            try {
                dy = event.getScrollDeltaY();
            } catch (Throwable ignored) {
            }
            logRaw(prefs, "SCRL " + event.getClassName() + " dy=" + dy);
            onStrictScroll(prefs, event);
        }
    }

    /** 视频号界面类名特征。微信改内部类名时，这里是唯一需要跟着改的地方。 */
    private boolean isFinderClass(String cls) {
        String c = cls == null ? "" : cls.toLowerCase();
        return c.contains("finder") || c.contains("channels");
    }

    /** 明确已在视频号之外：聊天列表、通讯录、发现、桌面等。 */
    private boolean isClearlyOutside(String cls) {
        String c = cls == null ? "" : cls.toLowerCase();
        return c.contains("chatting")
                || c.contains("conversation")
                || c.contains("launcher")
                || c.contains("contacts")
                || c.contains("discover")
                || c.contains("com.tencent.mm.ui.launcher");
    }

    private boolean isSticky() {
        return lastFinderHitMs > 0
                && System.currentTimeMillis() - lastFinderHitMs <= STICKY_WINDOW_MS;
    }

    // ---------------- 进入 / 离开 ----------------

    private void onEnterChannels(SharedPreferences prefs, String mode, String cls) {
        enterTimeMs = System.currentTimeMillis();
        scrollAccumulator = 0;
        loggedScrolls = 0;
        lastFingerprint = null;
        fingerprintBaselined = false;
        logEvent(prefs, "进入视频号: " + cls);

        if (MODE_TIMED.equals(mode)) {
            startTimedSession(prefs);
        } else {
            toast("已进入视频号，向下滑动将被拦截");
            if (prefs.getBoolean(KEY_CONTENT_WATCH, false)) {
                startContentWatch();
            }
        }
    }

    private void leaveChannels() {
        stopContentWatch();
        cancelTimers();
    }

    // ---------------- 严格模式拦截 ----------------

    private void onStrictScroll(SharedPreferences prefs, AccessibilityEvent event) {
        long now = System.currentTimeMillis();

        int dy = 0;
        try {
            dy = event.getScrollDeltaY(); // API 26+
        } catch (Throwable ignored) {
            // 个别 ROM 不支持，忽略即可
        }

        scrollAccumulator++;
        int sensitivity = prefs.getInt(KEY_SENSITIVITY, 2);
        // 刚进入界面时微信会自己恢复浏览位置产生滚动，用短宽限期吸收；
        // 但若在宽限期内就连滑多次，说明是你在主动刷，同样要拦。
        boolean inGrace = now - enterTimeMs < ENTER_GRACE_MS;
        int limit = inGrace ? GRACE_BURST_LIMIT : sensitivity;

        // 少量滚动写入日志用于排查，超过 6 条后只在临近阈值时记录，避免刷屏
        if (loggedScrolls < 6 || scrollAccumulator >= limit - 1) {
            loggedScrolls++;
            logEvent(prefs, "滑动 ΔY=" + dy + " 第" + scrollAccumulator + "次/阈值" + limit
                    + (inGrace ? "(宽限期内连滑)" : ""));
        }

        if (scrollAccumulator < limit) {
            return;
        }
        if (now - lastBlockMs < BLOCK_DEBOUNCE_MS) {
            return;
        }
        lastBlockMs = now;
        int hits = scrollAccumulator;
        scrollAccumulator = 0;
        logEvent(prefs, "拦截: 累计滑动" + hits + "次触发");
        blockAndExit(prefs, "想滑下一条？已帮你退出");
    }

    // ---------------- 退出执行 ----------------

    private void blockAndExit(SharedPreferences prefs, String message) {
        recordBlock(prefs);
        long total = prefs.getLong(KEY_TOTAL_BLOCKS, 0);
        backOut(prefs);
        refreshNotification();
        toast(message + "（累计拦截 " + total + " 次）");
        // 指纹基准作废，待下一次进入时重新采样
        lastFingerprint = null;
        fingerprintBaselined = false;
    }

    /**
     * 发送返回键，并在 600ms 后补发一次。
     *
     * 补发是为了对付"部分 ROM 会吞掉第一次返回"；但补发本身有风险——
     * 如果第一次已经退出了视频号，补发就会把聊天页也一并退出（甚至退回桌面）。
     * 因此补发前必须确认「这期间没有收到已离开视频号的窗口事件」，
     * 并且当前仍处于视频号状态；任何一条不满足就放弃补发。
     */
    private void backOut(SharedPreferences prefs) {
        leftConfirmed = false;
        performGlobalAction(GLOBAL_ACTION_BACK);
        handler.postDelayed(() -> {
            if (leftConfirmed || !inChannels || !isSticky()) {
                return; // 已经离开视频号（或状态不可靠）→ 绝不补发
            }
            logEvent(prefs, "补发返回：首次未生效");
            performGlobalAction(GLOBAL_ACTION_BACK);
        }, BACK_RETRY_DELAY_MS);
    }

    // ---------------- 限时模式 ----------------

    /** 限时模式：安排「剩余 1 分钟提醒」与「到点退出」。 */
    private void startTimedSession(SharedPreferences prefs) {
        cancelTimers();
        int minutes = prefs.getInt(KEY_TIMED_MINUTES, 10);
        final long limitMs = minutes * 60_000L;
        toast("已进入视频号，本次可看 " + minutes + " 分钟");

        if (limitMs > WARN_BEFORE_MS) {
            warnRunnable = () -> toast("还剩 1 分钟，看完这条就收工");
            handler.postDelayed(warnRunnable, limitMs - WARN_BEFORE_MS);
        }
        exitRunnable = () -> {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            inChannels = false;
            blockAndExit(p, "时间到，已退出视频号");
        };
        handler.postDelayed(exitRunnable, limitMs);
    }

    // ---------------- 实验：画面内容指纹兜底 ----------------

    /**
     * 轮询整棵视图树的可见文本摘要作为指纹。指纹变化说明屏幕内容整体换了
     * （通常是切到了下一条视频）。仅在用户手动开启 content_watch 时运行。
     */
    private void startContentWatch() {
        stopContentWatch();
        // 先等界面稳定（避开加载、布局动画），再采样基准指纹
        handler.postDelayed(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            lastFingerprint = fingerprintOf(root);
            recycleQuietly(root);
            fingerprintBaselined = true;
            logEvent(getSharedPreferences(PREFS, MODE_PRIVATE),
                    "内容检测: 已取基准指纹");
        }, CONTENT_BASELINE_DELAY_MS);

        contentPollRunnable = new Runnable() {
            @Override
            public void run() {
                SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
                if (!fingerprintBaselined || !inChannels
                        || System.currentTimeMillis() - enterTimeMs < ENTER_GRACE_MS) {
                    handler.postDelayed(this, CONTENT_POLL_MS);
                    return;
                }
                AccessibilityNodeInfo root = getRootInActiveWindow();
                String current = fingerprintOf(root);
                recycleQuietly(root);
                if (current != null && !current.equals(lastFingerprint)) {
                    if (System.currentTimeMillis() - lastBlockMs >= BLOCK_DEBOUNCE_MS) {
                        lastBlockMs = System.currentTimeMillis();
                        logEvent(prefs, "拦截: 画面内容变化（内容检测）");
                        blockAndExit(prefs, "检测到换了视频，已退出");
                    }
                }
                lastFingerprint = current;
                handler.postDelayed(this, CONTENT_POLL_MS);
            }
        };
        handler.postDelayed(contentPollRunnable, CONTENT_BASELINE_DELAY_MS + CONTENT_POLL_MS);
    }

    private void stopContentWatch() {
        if (contentPollRunnable != null) {
            handler.removeCallbacks(contentPollRunnable);
            contentPollRunnable = null;
        }
    }

    /** 遍历视图树，收集可见文本节点，生成结构+文本摘要。 */
    private String fingerprintOf(AccessibilityNodeInfo root) {
        if (root == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        collectText(root, sb, 0, new int[]{0});
        return sb.toString();
    }

    private void collectText(AccessibilityNodeInfo node, StringBuilder sb, int depth, int[] budget) {
        if (node == null || budget[0] > CONTENT_MAX_NODES || depth > 18) {
            return;
        }
        budget[0]++;
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            sb.append(text).append('|');
        } else {
            CharSequence desc = node.getContentDescription();
            if (desc != null && desc.length() > 0) {
                sb.append(desc).append('|');
            }
        }
        int count = node.getChildCount();
        for (int i = 0; i < count && budget[0] <= CONTENT_MAX_NODES; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectText(child, sb, depth + 1, budget);
                child.recycle();
            }
        }
    }

    private void recycleQuietly(AccessibilityNodeInfo node) {
        if (node != null) {
            try {
                node.recycle();
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------- 承诺期与统计 ----------------

    /**
     * 承诺期生效则强制为严格模式——即便用户绕过 UI 直接改了 SharedPreferences 也不放行。
     * 承诺期到点后自动解除，恢复为预存模式。
     */
    private String effectiveMode(SharedPreferences prefs) {
        String mode = prefs.getString(KEY_MODE, MODE_STRICT);
        long until = prefs.getLong(KEY_LOCK_UNTIL, 0);
        if (until > System.currentTimeMillis()) {
            return MODE_STRICT;
        }
        if (MODE_STRICT.equals(mode) && until > 0) {
            prefs.edit().putLong(KEY_LOCK_UNTIL, 0).apply();
        }
        return mode;
    }

    private void recordBlock(SharedPreferences prefs) {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        SharedPreferences.Editor e = prefs.edit();
        e.putLong(KEY_TOTAL_BLOCKS, prefs.getLong(KEY_TOTAL_BLOCKS, 0) + 1);
        e.putInt(KEY_DAY_PREFIX + today, prefs.getInt(KEY_DAY_PREFIX + today, 0) + 1);
        if (!prefs.contains(KEY_FIRST_DATE)) {
            e.putString(KEY_FIRST_DATE, today);
        }
        e.apply();
    }

    /** 追加一条调试日志（环形保留最近 30 条），供主界面查看。 */
    private void logEvent(SharedPreferences prefs, String msg) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String old = prefs.getString(KEY_DEBUG_LOG, "");
        String next = time + " " + msg + "\n" + old;
        String[] lines = next.split("\n");
        if (lines.length > DEBUG_LOG_MAX) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < DEBUG_LOG_MAX; i++) {
                sb.append(lines[i]).append("\n");
            }
            next = sb.toString();
        }
        prefs.edit().putString(KEY_DEBUG_LOG, next).apply();
    }

    /**
     * 追加一条原始事件日志。窗口类名全量记录——这是「微信更新后失效」时唯一能
     * 定位问题的东西，拿到它就不用靠猜。
     */
    private void logRaw(SharedPreferences prefs, String msg) {
        try {
            long now = System.currentTimeMillis();
            if (msg.startsWith("SCRL") && now - lastRawScrollLogMs < 300) {
                return; // 滚动事件很密，节流避免频繁写盘
            }
            if (msg.startsWith("SCRL")) {
                lastRawScrollLogMs = now;
            }
            String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            String old = prefs.getString(KEY_RAW_LOG, "");
            String next = time + " " + msg + "\n" + old;
            String[] lines = next.split("\n");
            if (lines.length > RAW_LOG_MAX) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < RAW_LOG_MAX; i++) {
                    sb.append(lines[i]).append("\n");
                }
                next = sb.toString();
            }
            prefs.edit().putString(KEY_RAW_LOG, next).apply();
        } catch (Throwable ignored) {
        }
    }

    private void resetState() {
        inChannels = false;
        scrollAccumulator = 0;
        lastFinderHitMs = 0;
        leftConfirmed = false;
        leaveChannels();
    }

    private void cancelTimers() {
        if (exitRunnable != null) {
            handler.removeCallbacks(exitRunnable);
            exitRunnable = null;
        }
        if (warnRunnable != null) {
            handler.removeCallbacks(warnRunnable);
            warnRunnable = null;
        }
    }

    @Override
    public void onDestroy() {
        cancelTimers();
        stopContentWatch();
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
        // 无需处理
    }

    private void toast(final String msg) {
        handler.post(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show());
    }
}
