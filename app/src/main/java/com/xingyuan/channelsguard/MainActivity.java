package com.xingyuan.channelsguard;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    static final String KEY_LOCK_UNTIL = GuardService.KEY_LOCK_UNTIL;

    private static final long DAY = 86_400_000L;

    // 严格模式承诺期选项：以「天」为最小单位
    private static final String[] LOCK_LABELS = {"1 天", "7 天", "两周", "1 个月", "半年"};
    private static final long[] LOCK_DURATIONS = {DAY, 7 * DAY, 14 * DAY, 30 * DAY, 180 * DAY};

    private SharedPreferences prefs;
    private TextView statusView;
    private TextView statsView;
    private TextView debugView;
    private TextView lockInfoView;
    private RadioGroup modeGroup;
    private RadioButton rbStrict;
    private RadioButton rbTimed;
    private RadioButton rbOff;
    private RadioGroup minutesGroup;
    private TextView minutesLabel;
    private TextView sensLabel;
    private RadioGroup sensGroup;
    private LinearLayout lockRows;
    private final List<RadioButton> lockButtons = new ArrayList<>();
    private CheckBox contentWatchBox;
    private TextView lockNote;
    private TextView batteryView;
    private Button batteryButton;
    private TextView keepAliveNote;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(GuardService.PREFS, MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("视频号守门员");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setTextSize(15);
        statusView.setPadding(0, dp(16), 0, dp(8));
        root.addView(statusView);

        Button openSettings = new Button(this);
        openSettings.setText("开启 / 重新开启无障碍服务");
        openSettings.setOnClickListener(v -> openAccessibilitySettings());
        root.addView(openSettings);

        batteryView = new TextView(this);
        batteryView.setTextSize(14);
        batteryView.setPadding(0, dp(10), 0, 0);
        root.addView(batteryView);

        batteryButton = new Button(this);
        batteryButton.setText("加入电池优化白名单（允许后台运行）");
        batteryButton.setOnClickListener(v -> requestIgnoreBatteryOptimization());
        root.addView(batteryButton);

        keepAliveNote = new TextView(this);
        keepAliveNote.setTextSize(13);
        keepAliveNote.setTextColor(Color.DKGRAY);
        keepAliveNote.setPadding(0, dp(8), 0, 0);
        keepAliveNote.setText(
                "别再划掉后台了：国产 ROM（MIUI / ColorOS / OriginOS / EMUI 等）把 App 从\n" +
                "最近任务划掉时，会把它置为「强制停止」，无障碍服务会一起被停用，\n" +
                "只能回到这里重新开一次。正确的做法是：\n" +
                "· 在最近任务里把本 App 的卡片下拉或长按，点「锁」固定住；\n" +
                "· 系统设置里允许自启动、允许后台运行、关闭省电策略；\n" +
                "· 完成上面两项后，日常根本不需要再点开本 App。\n\n" +
                "说明：第三方 App 无法用代码替你打开无障碍开关，这是系统的限制。"
        );
        root.addView(keepAliveNote);

        statsView = new TextView(this);
        statsView.setTextSize(16);
        statsView.setTextColor(Color.rgb(0x1B, 0x3A, 0x6B));
        statsView.setPadding(0, dp(18), 0, dp(4));
        root.addView(statsView);

        lockInfoView = new TextView(this);
        lockInfoView.setTextSize(15);
        lockInfoView.setTextColor(Color.rgb(0xC6, 0x28, 0x28));
        lockInfoView.setTypeface(Typeface.DEFAULT_BOLD);
        lockInfoView.setPadding(0, dp(10), 0, 0);
        root.addView(lockInfoView);

        root.addView(sectionLabel("守护模式"));

        modeGroup = new RadioGroup(this);
        rbStrict = new RadioButton(this);
        rbStrict.setText("严格模式：在视频号里一滑动就退出（需承诺期）");
        rbTimed = new RadioButton(this);
        rbTimed.setText("限时模式：可自由观看，到点自动退出");
        rbOff = new RadioButton(this);
        rbOff.setText("暂停守护");
        modeGroup.addView(rbStrict);
        modeGroup.addView(rbTimed);
        modeGroup.addView(rbOff);
        root.addView(modeGroup);

        TextView lockTitle = sectionLabel("严格模式承诺期（选定后到期前不可更改模式）");
        root.addView(lockTitle);
        lockRows = new LinearLayout(this);
        lockRows.setOrientation(LinearLayout.VERTICAL);
        buildLockRows(lockRows);
        root.addView(lockRows);

        lockNote = new TextView(this);
        lockNote.setTextSize(13);
        lockNote.setTextColor(Color.GRAY);
        lockNote.setText("小时级的不算数。最短一天，最长半年，选了就不能反悔。");
        root.addView(lockNote);

        minutesLabel = sectionLabel("限时时长");
        root.addView(minutesLabel);

        minutesGroup = new RadioGroup(this);
        minutesGroup.setOrientation(RadioGroup.HORIZONTAL);
        int[] options = {5, 10, 20};
        for (int m : options) {
            RadioButton rb = new RadioButton(this);
            rb.setText(m + " 分钟");
            rb.setId(1000 + m);
            rb.setTag(m);
            minutesGroup.addView(rb);
        }
        root.addView(minutesGroup);

        sensLabel = sectionLabel("拦截灵敏度（滑动几次触发退出）");
        root.addView(sensLabel);

        sensGroup = new RadioGroup(this);
        sensGroup.setOrientation(RadioGroup.HORIZONTAL);
        int[] sensOpts = {1, 2, 4};
        String[] sensTexts = {"1 次最猛", "2 次推荐", "4 次宽松"};
        for (int i = 0; i < sensOpts.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(sensTexts[i]);
            rb.setId(3000 + sensOpts[i]);
            rb.setTag(sensOpts[i]);
            sensGroup.addView(rb);
        }
        root.addView(sensGroup);

        contentWatchBox = new CheckBox(this);
        contentWatchBox.setText("实验：画面内容变化检测（滑动拦不住时才开）");
        contentWatchBox.setTextSize(14);
        contentWatchBox.setPadding(0, dp(16), 0, 0);
        root.addView(contentWatchBox);

        TextView debugLabel = sectionLabel("调试日志（拦截记录，用于排查问题）");
        root.addView(debugLabel);

        debugView = new TextView(this);
        debugView.setTextSize(12);
        debugView.setTextColor(Color.GRAY);
        debugView.setTypeface(Typeface.MONOSPACE);
        root.addView(debugView);

        Button copyLog = new Button(this);
        copyLog.setText("复制调试日志");
        copyLog.setOnClickListener(v -> copyDebugLog());
        root.addView(copyLog);

        TextView howto = new TextView(this);
        howto.setTextSize(14);
        howto.setTextColor(Color.DKGRAY);
        howto.setPadding(0, dp(18), 0, 0);
        howto.setText(
                "使用方法：\n" +
                "1. 点击上方按钮，在系统无障碍设置里找到「视频号守门员」并开启。\n" +
                "2. 回到本页确认状态显示「服务运行中」。\n" +
                "3. 选好模式后，在微信里点开视频号链接即可。\n\n" +
                "拦不住时这样排查：\n" +
                "· 看下方日志里有没有「滑动 ΔY=…」记录。\n" +
                "· 有记录但没退出 → 把灵敏度调到「1 次最猛」。\n" +
                "· 完全没有记录 → 你的微信不发出滚动事件，\n" +
                "   勾选上方「画面内容变化检测」再试。\n\n" +
                "已知限制：\n" +
                "· 严格模式下，快速滑动评论区也可能触发退出。\n" +
                "· 承诺期只能锁定 App 内的模式切换；\n" +
                "   直接关闭无障碍服务、卸载 App 或清除数据无法阻止。\n" +
                "· 限时模式的倒计时在服务被系统回收后会失效，\n" +
                "   重新进入视频号会重新开始计时。\n" +
                "· 微信大版本更新后若失效，请联系小沃更新识别规则。"
        );
        root.addView(howto);

        restoreSavedState();
        bindListeners();
        updateVisibility();
        requestNotificationPermission();
        setContentView(scroll);
    }

    /** 承诺期按钮按行排布（每行 3 个），避免横向挤成一团。 */
    private void buildLockRows(LinearLayout container) {
        LinearLayout row = null;
        for (int i = 0; i < LOCK_LABELS.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                container.addView(row);
            }
            RadioButton rb = new RadioButton(this);
            rb.setText(LOCK_LABELS[i]);
            rb.setTextSize(14);
            rb.setId(2000 + i);
            rb.setTag(LOCK_DURATIONS[i]);
            rb.setOnClickListener(v -> {
                RadioButton target = (RadioButton) v;
                if (!target.isChecked()) {
                    return; // 取消勾选不处理
                }
                clearLockChecks();
                target.setChecked(true);
                confirmLock(target);
            });
            if (row != null) {
                row.addView(rb);
            }
            lockButtons.add(rb);
        }
    }

    private void clearLockChecks() {
        for (RadioButton b : lockButtons) {
            b.setChecked(false);
        }
    }

    private void confirmLock(final RadioButton rb) {
        long duration = (long) rb.getTag();
        new AlertDialog.Builder(this)
                .setTitle("确认开启严格模式")
                .setMessage("承诺期：" + rb.getText() + "\n\n"
                        + "到期之前，你将无法在 App 内切换到限时模式或暂停守护。确定吗？")
                .setPositiveButton("确定，锁定", (d, w) -> {
                    long until = System.currentTimeMillis() + duration;
                    prefs.edit()
                            .putLong(KEY_LOCK_UNTIL, until)
                            .putString(GuardService.KEY_MODE, GuardService.MODE_STRICT)
                            .apply();
                    rbStrict.setChecked(true);
                    updateVisibility();
                    Toast.makeText(this, "严格模式已锁定 " + rb.getText(),
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("再想想", (d, w) -> clearLockChecks())
                .setCancelable(false)
                .show();
    }

    private void restoreSavedState() {
        String mode = prefs.getString(GuardService.KEY_MODE, GuardService.MODE_STRICT);
        if (GuardService.MODE_TIMED.equals(mode)) {
            rbTimed.setChecked(true);
        } else if (GuardService.MODE_OFF.equals(mode)) {
            rbOff.setChecked(true);
        } else {
            rbStrict.setChecked(true);
        }
        int savedMinutes = prefs.getInt(GuardService.KEY_TIMED_MINUTES, 10);
        for (int i = 0; i < minutesGroup.getChildCount(); i++) {
            RadioButton rb = (RadioButton) minutesGroup.getChildAt(i);
            if ((int) rb.getTag() == savedMinutes) {
                rb.setChecked(true);
            }
        }
        int savedSens = prefs.getInt(GuardService.KEY_SENSITIVITY, 2);
        for (int i = 0; i < sensGroup.getChildCount(); i++) {
            RadioButton rb = (RadioButton) sensGroup.getChildAt(i);
            if ((int) rb.getTag() == savedSens) {
                rb.setChecked(true);
            }
        }
        contentWatchBox.setChecked(prefs.getBoolean(GuardService.KEY_CONTENT_WATCH, false));
    }

    private void bindListeners() {
        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == rbStrict.getId()) {
                if (isLockActive()) {
                    saveMode(GuardService.MODE_STRICT);
                } else {
                    // 尚未选择承诺期：模式暂不落库，等用户在下方选定时长
                    Toast.makeText(this, "请选择一个承诺期，到期前将无法更改模式",
                            Toast.LENGTH_LONG).show();
                }
            } else if (checkedId == rbTimed.getId()) {
                saveMode(GuardService.MODE_TIMED);
            } else if (checkedId == rbOff.getId()) {
                saveMode(GuardService.MODE_OFF);
            }
            updateVisibility();
        });

        minutesGroup.setOnCheckedChangeListener((group, checkedId) -> {
            RadioButton rb = group.findViewById(checkedId);
            if (rb != null) {
                prefs.edit().putInt(GuardService.KEY_TIMED_MINUTES, (int) rb.getTag()).apply();
            }
        });

        sensGroup.setOnCheckedChangeListener((group, checkedId) -> {
            RadioButton rb = group.findViewById(checkedId);
            if (rb != null) {
                prefs.edit().putInt(GuardService.KEY_SENSITIVITY, (int) rb.getTag()).apply();
            }
        });

        contentWatchBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean(GuardService.KEY_CONTENT_WATCH, isChecked).apply());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshStats();
        refreshDebugLog();
        refreshBatteryStatus();
        updateVisibility();
    }

    private boolean isLockActive() {
        String mode = prefs.getString(GuardService.KEY_MODE, GuardService.MODE_STRICT);
        long until = prefs.getLong(KEY_LOCK_UNTIL, 0);
        return GuardService.MODE_STRICT.equals(mode) && until > System.currentTimeMillis();
    }

    private void saveMode(String mode) {
        prefs.edit().putString(GuardService.KEY_MODE, mode).apply();
    }

    private void updateVisibility() {
        boolean locked = isLockActive();

        // 锁定期间：禁止切换到限时 / 暂停
        rbTimed.setEnabled(!locked);
        rbOff.setEnabled(!locked);

        if (locked) {
            lockInfoView.setVisibility(android.view.View.VISIBLE);
            lockInfoView.setText("严格模式锁定中，" + formatRemaining() + "后可更改模式");
        } else {
            lockInfoView.setVisibility(android.view.View.GONE);
        }

        // 承诺期选择区：仅在勾选严格模式且未锁定时显示
        boolean showLockPicker = rbStrict.isChecked() && !locked;
        int lockVis = showLockPicker ? android.view.View.VISIBLE : android.view.View.GONE;
        lockRows.setVisibility(lockVis);
        lockNote.setVisibility(lockVis);

        // 限时时长：仅在勾选限时模式时显示
        boolean timed = rbTimed.isChecked();
        minutesLabel.setVisibility(timed ? android.view.View.VISIBLE : android.view.View.GONE);
        minutesGroup.setVisibility(timed ? android.view.View.VISIBLE : android.view.View.GONE);

        // 灵敏度：严格模式下才有意义
        boolean strict = GuardService.MODE_STRICT.equals(
                prefs.getString(GuardService.KEY_MODE, GuardService.MODE_STRICT));
        sensLabel.setVisibility(strict ? android.view.View.VISIBLE : android.view.View.GONE);
        sensGroup.setVisibility(strict ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private String formatRemaining() {
        long remain = prefs.getLong(KEY_LOCK_UNTIL, 0) - System.currentTimeMillis();
        if (remain <= 0) {
            return "已到期";
        }
        long days = TimeUnit.MILLISECONDS.toDays(remain);
        long hours = TimeUnit.MILLISECONDS.toHours(remain) % 24;
        if (days > 0) {
            return "剩余 " + days + " 天 " + hours + " 小时";
        }
        long minutes = TimeUnit.MILLISECONDS.toMinutes(remain) % 60;
        if (hours > 0) {
            return "剩余 " + hours + " 小时 " + minutes + " 分";
        }
        return "剩余 " + minutes + " 分钟";
    }

    /** 尝试直接打开本 App 的无障碍详情页；不支持的 ROM 回退到无障碍列表页。 */
    private void openAccessibilitySettings() {
        try {
            // 该 action 没有公开的 Settings 常量，需用字符串
            Intent detail = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
            detail.setData(Uri.parse("package:" + getPackageName()));
            detail.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(detail);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean isIgnoringBatteryOptimization() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestIgnoreBatteryOptimization() {
        if (isIgnoringBatteryOptimization()) {
            Toast.makeText(this, "已在白名单里，无需再设置", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable t) {
            // 部分 ROM 没有这个授权页，改跳应用详情页
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    private void refreshBatteryStatus() {
        boolean ok = isIgnoringBatteryOptimization();
        if (ok) {
            batteryView.setText("后台保护：已加入电池优化白名单 ✔");
            batteryView.setTextColor(Color.rgb(0x1B, 0x7D, 0x32));
            batteryButton.setEnabled(false);
        } else {
            batteryView.setText("后台保护：未加入白名单，进程容易被系统回收");
            batteryView.setTextColor(Color.rgb(0xC6, 0x28, 0x28));
            batteryButton.setEnabled(true);
        }
    }

    /** Android 13+ 需要通知权限才能显示常驻通知（前台服务靠它提升存活率）。 */
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    private void refreshStatus() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean running = enabled != null && enabled.contains(getPackageName());
        if (running) {
            statusView.setText("服务状态：运行中 ✔");
            statusView.setTextColor(Color.rgb(0x1B, 0x7D, 0x32));
        } else {
            statusView.setText("服务状态：未开启 ✘\n"
                    + "常见原因：你在最近任务里把本 App 划掉了，ROM 把它强制停止，"
                    + "无障碍服务随之停用。点下方按钮重新开启一次。");
            statusView.setTextColor(Color.rgb(0xC6, 0x28, 0x28));
        }
    }

    private void refreshStats() {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        int todayCount = prefs.getInt(GuardService.KEY_DAY_PREFIX + today, 0);
        long total = prefs.getLong(GuardService.KEY_TOTAL_BLOCKS, 0);
        long days = 0;
        String first = prefs.getString(GuardService.KEY_FIRST_DATE, null);
        if (first != null) {
            try {
                Date d0 = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(first);
                if (d0 != null) {
                    long diff = new Date().getTime() - d0.getTime();
                    days = TimeUnit.MILLISECONDS.toDays(diff) + 1;
                }
            } catch (ParseException ignored) {
            }
        }
        statsView.setText("今日拦截 " + todayCount + " 次 ｜ 累计拦截 " + total + " 次"
                + (days > 0 ? " ｜ 已守护 " + days + " 天" : ""));
    }

    private void refreshDebugLog() {
        String log = prefs.getString(GuardService.KEY_DEBUG_LOG, "");
        debugView.setText(log.isEmpty() ? "（暂无记录）" : log.trim());
    }

    private void copyDebugLog() {
        String log = prefs.getString(GuardService.KEY_DEBUG_LOG, "");
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            Toast.makeText(this, "无法访问剪贴板", Toast.LENGTH_SHORT).show();
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("ChannelsGuard-debug-log",
                log.isEmpty() ? "（暂无记录）" : log));
        Toast.makeText(this, "日志已复制，可直接粘贴发送", Toast.LENGTH_SHORT).show();
    }

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(Color.GRAY);
        tv.setPadding(0, dp(18), 0, dp(4));
        return tv;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
