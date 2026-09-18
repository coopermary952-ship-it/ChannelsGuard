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
 * 视频号守门员核心服务（v2）。
 *
 * 原理：
 * 1. 监听微信（com.tencent.mm）的窗口切换事件，类名包含 "finder" 即为视频号界面。
 * 2. 严格模式：处于视频号界面时，收到 VIEW_SCROLLED（上下滑切换视频）立即全局返回。
 * 3. 限时模式：进入视频号后启动倒计时，剩余 1 分钟时提醒，到点自动全局返回。
 *
 * 统计：每次拦截计入「累计拦截」与「今日拦截」，供主界面展示。
 */
public class GuardService extends AccessibilityService {

    static final String PREFS = "guard_prefs";
    static final String KEY_MODE = "mode";            // strict | timed | off
    static final String KEY_TIMED_MINUTES = "timed_minutes";
    static final String KEY_TOTAL_BLOCKS = "total_blocks";
    static final String KEY_FIRST_DATE = "first_date";
    static final String KEY_DAY_PREFIX = "count_";    // count_yyyy-MM-dd

    static final String MODE_STRICT = "strict";
    static final String MODE_TIMED = "timed";
    static final String MODE_OFF = "off";

    private static final long ENTER_GRACE_MS = 1500;
    private static final long BLOCK_DEBOUNCE_MS = 1000;
    private static final long WARN_BEFORE_MS = 60_000; // 到点前 1 分钟提醒

    private boolean inChannels = false;
    private long enterTimeMs = 0;
    private long lastBlockMs = 0;
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
        String mode = prefs.getString(KEY_MODE, MODE_STRICT);
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
                if (MODE_TIMED.equals(mode)) {
                    startTimedSession(prefs);
                } else {
                    toast("已进入视频号，向下滑动将被拦截");
                }
            } else if (!nowInChannels && inChannels) {
                cancelTimers();
            }
            inChannels = nowInChannels;
        } else if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED && inChannels) {
            if (!MODE_STRICT.equals(mode)) {
                return; // 限时模式下允许自由滑动，到点统一退出
            }
            long now = System.currentTimeMillis();
            if (now - enterTimeMs < ENTER_GRACE_MS) {
                return;
            }
            if (now - lastBlockMs < BLOCK_DEBOUNCE_MS) {
                return;
            }
            lastBlockMs = now;
            blockAndExit(prefs, "想滑下一条？已帮你退出");
        }
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

    private void resetState() {
        inChannels = false;
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
