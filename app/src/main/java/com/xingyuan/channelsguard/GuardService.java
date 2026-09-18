package com.xingyuan.channelsguard;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 视频号守门员核心服务（v2.2）。
 *
 * 原理：
 * 1. 监听微信（com.tencent.mm）的窗口切换事件，类名包含 "finder" 即为视频号界面。
 * 2. 严格模式：识别「真实甩动」手势——1 秒内连续滚动事件 ≥ FLING_THRESHOLD 次
 *    才判定为用户主动下滑，执行全局返回。
 *    （v2.0 按时间宽限判断，第二次点开链接时微信恢复浏览状态的滚动
 *      超过宽限期会被误判，导致"点开第二个链接直接被退出"。）
 * 3. 限时模式：进入视频号后启动倒计时，剩余 1 分钟时提醒，到点自动全局返回。
 *
 * 调试：最近 30 条关键事件会记录到 SharedPreferences，主界面可查看。
 */
public class GuardService extends AccessibilityService {

    static final String PREFS = "guard_prefs";
    static final String KEY_MODE = "mode";            // strict | timed | off
    static final String KEY_TIMED_MINUTES = "timed_minutes";
    static final String KEY_TOTAL_BLOCKS = "total_blocks";
    static final String KEY_FIRST_DATE = "first_date";
    static final String KEY_DAY_PREFIX = "count_";    // count_yyyy-MM-dd
    static final String KEY_DEBUG_LOG = "debug_log";
    static final String KEY_LOCK_UNTIL = "lock_until"; // 严格模式承诺期截止时间（毫秒）

    static final String MODE_STRICT = "strict";
    static final String MODE_TIMED = "timed";
    static final String MODE_OFF = "off";

    private static final long ENTER_GRACE_MS = 2000;       // 进入界面后的初始化宽限
    private static final int FLING_THRESHOLD = 4;          // 判定甩动的滚动次数阈值
    private static final long FLING_WINDOW_MS = 1000;      // 甩动判定的时间窗口
    private static final long BLOCK_DEBOUNCE_MS = 1000;
    private static final long WARN_BEFORE_MS = 60_000;
    private static final int DEBUG_LOG_MAX = 30;

    private boolean inChannels = false;
    private long enterTimeMs = 0;
    private long lastBlockMs = 0;
    private int scrollCount = 0;
    private long lastScrollMs = 0;
    private Handler handler;
    private Runnable exitRunnable;
    private Runnable warnRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
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
            boolean nowInChannels = cls.toLowerCase().contains("finder");
            if (nowInChannels && !inChannels) {
                enterTimeMs = System.currentTimeMillis();
                scrollCount = 0;
                lastScrollMs = 0;
                logEvent(prefs, "进入视频号: " + cls);
                if (MODE_TIMED.equals(mode)) {
                    startTimedSession(prefs);
                } else {
                    toast("已进入视频号，向下滑动将被拦截");
                }
            } else if (!nowInChannels && inChannels) {
                logEvent(prefs, "离开视频号: " + cls);
                cancelTimers();
            }
            inChannels = nowInChannels;
        } else if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED && inChannels) {
            if (!MODE_STRICT.equals(mode)) {
                return; // 限时模式下允许自由滑动，到点统一退出
            }
            long now = System.currentTimeMillis();
            if (now - enterTimeMs < ENTER_GRACE_MS) {
                return; // 初始化阶段的滚动不介入
            }
            // 甩动识别：超过 1 秒没有滚动则重新计数；
            // 窗口内累计达到阈值才判定为用户主动下滑
            if (now - lastScrollMs > FLING_WINDOW_MS) {
                scrollCount = 0;
            }
            lastScrollMs = now;
            scrollCount++;
            if (scrollCount < FLING_THRESHOLD) {
                return;
            }
            if (now - lastBlockMs < BLOCK_DEBOUNCE_MS) {
                return;
            }
            lastBlockMs = now;
            int hits = scrollCount;
            scrollCount = 0;
            logEvent(prefs, "拦截: 1秒内滚动" + hits + "次，判定为甩动");
            blockAndExit(prefs, "想滑下一条？已帮你退出");
        }
    }

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

    private void blockAndExit(SharedPreferences prefs, String message) {
        recordBlock(prefs);
        long total = prefs.getLong(KEY_TOTAL_BLOCKS, 0);
        performGlobalAction(GLOBAL_ACTION_BACK);
        toast(message + "（累计拦截 " + total + " 次）");
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

    private void resetState() {
        inChannels = false;
        scrollCount = 0;
        cancelTimers();
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
