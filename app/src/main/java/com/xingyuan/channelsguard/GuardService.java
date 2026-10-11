package com.xingyuan.channelsguard;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 视频号守门员核心服务（v2.7）。
 *
 * 原理：
 * 1. 监听微信（com.tencent.mm）的窗口切换事件，类名包含 "finder" / "channels" 即为视频号界面。
 * 2. 严格模式：进入视频号后累计滚动事件的次数，达到灵敏度阈值即为"你在往下刷"，执行全局返回。
 * 3. 返回可能被部分 ROM / 微信吃掉，因此第一次 BACK 后 600ms 复核一次，
 *    确认仍在视频号且没有收到"已离开"事件时才补发（最多补 1 次）。
 * 4. 限时模式：进入视频号后启动倒计时，剩余 1 分钟时提醒，到点自动返回。
 *
 * 隐私设计（v2.7 起）：
 * - 无障碍配置**未开启 canRetrieveWindowContent**，因此本服务在系统层面就没有
 *   读取界面文本的能力，无法读取任何聊天内容。
 * - 锁屏时不弹 Toast，通知使用隐私模式，避免泄露使用情况。
 * - 日志只含类名、时间和计数，先写内存缓冲再批量落盘，减少 I/O。
 *
 * 调试：最近 30/60 条关键事件记录到 SharedPreferences，主界面可查看、可复制。
 */
public class GuardService extends AccessibilityService {

    static final String PREFS = "guard_prefs";
    static final String KEY_MODE = "mode";            // strict | timed | off
    static final String KEY_TIMED_MINUTES = "timed_minutes";
    static final String KEY_SENSITIVITY = "sensitivity";      // 触发拦截所需的累计滚动次数
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

    /** 日志先写内存缓冲，按 LOG_FLUSH_DELAY_MS 批量落盘，避免高频写盘。 */
    private static final long LOG_FLUSH_DELAY_MS = 800;
    private static final long CONFIG_CACHE_MS = 1000;      // 配置项内存缓存有效期

    private boolean inChannels = false;
    private long enterTimeMs = 0;
    private long lastBlockMs = 0;
    private int scrollAccumulator = 0;   // 进入视频号后的累计滚动次数
    private int loggedScrolls = 0;       // 已写入调试日志的滚动条数（避免刷屏）
    private long lastFinderHitMs = 0;    // 最近一次命中视频号类名的时间
    private long lastRawScrollLogMs = 0; // 原始日志中滚动行的节流时间
    private boolean leftConfirmed = false; // 已收到「明确离开视频号」的窗口事件

    // ---- 性能：内存缓冲 + 批量落盘，避免在无障碍回调里频繁读写真实存储 ----
    private SharedPreferences prefs;
    private final StringBuilder debugBuf = new StringBuilder();
    private final StringBuilder rawBuf = new StringBuilder();
    private boolean flushScheduled = false;
    private long modeCacheMs = 0;
    private String modeCache = null;
    private int sensitivityCache = 2;

    private Handler handler;
    private Runnable exitRunnable;
    private Runnable warnRunnable;

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
            logEvent("前台服务启动失败: " + t.getClass().getSimpleName());
        }
    }

    private Notification buildNotification() {
        SharedPreferences p = prefs();
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        int todayCount = p.getInt(KEY_DAY_PREFIX + today, 0);
        String mode = effectiveMode();
        String modeText = MODE_TIMED.equals(mode) ? "限时模式"
                : MODE_OFF.equals(mode) ? "已暂停" : "严格模式";

        Intent intent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 1, intent, flags);

        // 锁屏时只显示中性文案，不暴露"正在看视频号"和拦截次数
        Notification publicVersion = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("视频号守门员")
                .setContentText("正在运行")
                .build();

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("视频号守门员运行中 · " + modeText)
                .setContentText("今日已拦截 " + todayCount + " 次")
                .setContentIntent(pi)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
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
        String mode = effectiveMode();
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
            logRaw("WIN  " + cls);

            boolean nowInChannels = isFinderClass(cls);
            if (nowInChannels) {
                lastFinderHitMs = System.currentTimeMillis();
                if (!inChannels) {
                    onEnterChannels(mode, cls);
                }
            } else if (isClearlyOutside(cls)) {
                // 明确回到了聊天/桌面等地方：解除粘性窗口，避免误拦聊天列表
                lastFinderHitMs = 0;
                leftConfirmed = true;
                if (inChannels) {
                    logEvent("离开视频号: " + cls);
                    leaveChannels();
                }
            } else if (inChannels) {
                // 未命中视频号但也不明确在外面 —— 多半是微信内部的半屏/子层，
                // 保持状态不动，靠粘性窗口兜住随后到来的滚动事件
                logEvent("视频号内的其它窗口(保持守护): " + cls);
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
            logRaw("SCRL " + event.getClassName() + " dy=" + dy);
            onStrictScroll(dy);
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

    private void onEnterChannels(String mode, String cls) {
        enterTimeMs = System.currentTimeMillis();
        scrollAccumulator = 0;
        loggedScrolls = 0;
        logEvent("进入视频号: " + cls);

        if (MODE_TIMED.equals(mode)) {
            startTimedSession();
        } else {
            toast("已进入视频号，向下滑动将被拦截");
        }
    }

    private void leaveChannels() {
        cancelTimers();
    }

    // ---------------- 严格模式拦截 ----------------

    private void onStrictScroll(int dy) {
        long now = System.currentTimeMillis();

        scrollAccumulator++;
        int limit;
        // 刚进入界面时微信会自己恢复浏览位置产生滚动，用短宽限期吸收；
        // 但若在宽限期内就连滑多次，说明是你在主动刷，同样要拦。
        if (now - enterTimeMs < ENTER_GRACE_MS) {
            limit = GRACE_BURST_LIMIT;
        } else {
            limit = cachedSensitivity();
        }

        // 少量滚动写入日志用于排查，超过 6 条后只在临近阈值时记录，避免刷屏
        if (loggedScrolls < 6 || scrollAccumulator >= limit - 1) {
            loggedScrolls++;
            logEvent("滑动 ΔY=" + dy + " 第" + scrollAccumulator + "次/阈值" + limit
                    + (now - enterTimeMs < ENTER_GRACE_MS ? "(宽限期内连滑)" : ""));
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
        logEvent("拦截: 累计滑动" + hits + "次触发");
        blockAndExit("想滑下一条？已帮你退出");
    }

    // ---------------- 退出执行 ----------------

    private void blockAndExit(String message) {
        recordBlock();
        long total = prefs().getLong(KEY_TOTAL_BLOCKS, 0);
        backOut();
        refreshNotification();
        toast(message + "（累计拦截 " + total + " 次）");
    }

    /**
     * 发送返回键，并在 600ms 后补发一次。
     *
     * 补发是为了对付"部分 ROM 会吞掉第一次返回"；但补发本身有风险——
     * 如果第一次已经退出了视频号，补发就会把聊天页也一并退出（甚至退回桌面）。
     * 因此补发前必须确认「这期间没有收到已离开视频号的窗口事件」，
     * 并且当前仍处于视频号状态；任何一条不满足就放弃补发。
     */
    private void backOut() {
        leftConfirmed = false;
        performGlobalAction(GLOBAL_ACTION_BACK);
        handler.postDelayed(() -> {
            if (leftConfirmed || !inChannels || !isSticky()) {
                return; // 已经离开视频号（或状态不可靠）→ 绝不补发
            }
            logEvent("补发返回：首次未生效");
            performGlobalAction(GLOBAL_ACTION_BACK);
        }, BACK_RETRY_DELAY_MS);
    }

    // ---------------- 限时模式 ----------------

    /** 限时模式：安排「剩余 1 分钟提醒」与「到点退出」。 */
    private void startTimedSession() {
        cancelTimers();
        int minutes = prefs().getInt(KEY_TIMED_MINUTES, 10);
        final long limitMs = minutes * 60_000L;
        toast("已进入视频号，本次可看 " + minutes + " 分钟");

        if (limitMs > WARN_BEFORE_MS) {
            warnRunnable = () -> toast("还剩 1 分钟，看完这条就收工");
            handler.postDelayed(warnRunnable, limitMs - WARN_BEFORE_MS);
        }
        exitRunnable = () -> {
            inChannels = false;
            blockAndExit("时间到，已退出视频号");
        };
        handler.postDelayed(exitRunnable, limitMs);
    }

    // ---------------- 承诺期与统计 ----------------

    /**
     * 承诺期生效则强制为严格模式——即便用户绕过 UI 直接改了 SharedPreferences 也不放行。
     * 承诺期到点后自动解除，恢复为预存模式。
     *
     * 结果在内存里缓存 CONFIG_CACHE_MS，避免每个无障碍事件都去读盘。
     */
    private String effectiveMode() {
        long now = System.currentTimeMillis();
        if (modeCache != null && now - modeCacheMs < CONFIG_CACHE_MS) {
            return modeCache;
        }
        SharedPreferences p = prefs();
        String mode = p.getString(KEY_MODE, MODE_STRICT);
        long until = p.getLong(KEY_LOCK_UNTIL, 0);
        if (until > now) {
            modeCache = MODE_STRICT;
        } else {
            if (MODE_STRICT.equals(mode) && until > 0) {
                p.edit().putLong(KEY_LOCK_UNTIL, 0).apply();
            }
            modeCache = mode;
        }
        modeCacheMs = now;
        return modeCache;
    }

    private int cachedSensitivity() {
        long now = System.currentTimeMillis();
        if (sensitivityCache < 0) {
            sensitivityCache = prefs().getInt(KEY_SENSITIVITY, 2);
            modeCacheMs = now;
        } else if (now - modeCacheMs >= CONFIG_CACHE_MS) {
            sensitivityCache = prefs().getInt(KEY_SENSITIVITY, 2);
            modeCacheMs = now;
        }
        return sensitivityCache;
    }

    private void recordBlock() {
        SharedPreferences p = prefs();
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        SharedPreferences.Editor e = p.edit();
        e.putLong(KEY_TOTAL_BLOCKS, p.getLong(KEY_TOTAL_BLOCKS, 0) + 1);
        e.putInt(KEY_DAY_PREFIX + today, p.getInt(KEY_DAY_PREFIX + today, 0) + 1);
        if (!p.contains(KEY_FIRST_DATE)) {
            e.putString(KEY_FIRST_DATE, today);
        }
        e.apply();
        flushLogs(); // 拦截是低频事件，顺手落盘保证日志完整
    }

    /**
     * 追加一条调试日志。先写内存缓冲，延迟批量落盘——
     * 无障碍回调极其频繁，原地读改写会造成明显卡顿与耗电。
     */
    private void logEvent(String msg) {
        appendLine(debugBuf, msg);
        scheduleFlush();
    }

    /**
     * 追加一条原始事件日志。窗口类名全量记录——这是「微信更新后失效」时唯一能
     * 定位问题的东西，拿到它就不用靠猜。
     */
    private void logRaw(String msg) {
        long now = System.currentTimeMillis();
        if (msg.startsWith("SCRL")) {
            if (now - lastRawScrollLogMs < 300) {
                return; // 滚动事件很密，节流
            }
            lastRawScrollLogMs = now;
        }
        appendLine(rawBuf, msg);
        scheduleFlush();
    }

    private void appendLine(StringBuilder buf, String msg) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        buf.append(time).append(' ').append(msg).append('\n');
    }

    private void scheduleFlush() {
        if (flushScheduled) {
            return;
        }
        flushScheduled = true;
        handler.postDelayed(this::flushLogs, LOG_FLUSH_DELAY_MS);
    }

    /** 把内存缓冲合并进 SharedPreferences，并保持固定行数上限。 */
    private void flushLogs() {
        flushScheduled = false;
        try {
            SharedPreferences p = prefs();
            SharedPreferences.Editor ed = p.edit();
            boolean changed = false;
            if (debugBuf.length() > 0) {
                ed.putString(KEY_DEBUG_LOG, merge(debugBuf.toString(),
                        p.getString(KEY_DEBUG_LOG, ""), DEBUG_LOG_MAX));
                debugBuf.setLength(0);
                changed = true;
            }
            if (rawBuf.length() > 0) {
                ed.putString(KEY_RAW_LOG, merge(rawBuf.toString(),
                        p.getString(KEY_RAW_LOG, ""), RAW_LOG_MAX));
                rawBuf.setLength(0);
                changed = true;
            }
            if (changed) {
                ed.apply();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 缓冲区是时间递增的，历史记录是"新行在前"，拼起来后截取前 max 行即可。 */
    private String merge(String buffered, String stored, int max) {
        String combined = buffered + stored;
        String[] lines = combined.split("\n");
        if (lines.length <= max) {
            return combined;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < max; i++) {
            sb.append(lines[i]).append("\n");
        }
        return sb.toString();
    }

    private SharedPreferences prefs() {
        if (prefs == null) {
            prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        }
        return prefs;
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
        flushLogs(); // 别把内存里的日志丢掉
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
        // 无需处理
    }

    private void toast(final String msg) {
        // 锁屏时不弹：Toast 在锁屏上也会显示，会泄露"正在刷视频号"和使用次数
        if (isScreenLocked()) {
            return;
        }
        handler.post(() -> {
            if (!isScreenLocked()) {
                Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private boolean isScreenLocked() {
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isDeviceLocked();
    }
}
