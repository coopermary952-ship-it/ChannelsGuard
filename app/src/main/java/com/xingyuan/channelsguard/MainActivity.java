package com.xingyuan.channelsguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    static final String KEY_LOCK_UNTIL = GuardService.KEY_LOCK_UNTIL;

    private static final long HOUR = 3_600_000L;
    private static final long DAY = 86_400_000L;

    // 承诺期选项：以小时和天为单位
    private static final String[] LOCK_LABELS = {"12 小时", "1 天", "3 天", "7 天"};
    private static final long[] LOCK_DURATIONS = {12 * HOUR, DAY, 3 * DAY, 7 * DAY};

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
    private RadioGroup lockGroup;
    private TextView lockLabel;

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
        openSettings.setText("去开启无障碍服务");
        openSettings.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(openSettings);

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

        lockLabel = sectionLabel("严格模式承诺期（选定后到期前不可更改模式）");
        root.addView(lockLabel);

        lockGroup = new RadioGroup(this);
        lockGroup.setOrientation(RadioGroup.HORIZONTAL);
        for (int i = 0; i < LOCK_LABELS.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(LOCK_LABELS[i]);
            rb.setId(2000 + i);
            rb.setTag(LOCK_DURATIONS[i]);
            lockGroup.addView(rb);
        }
        root.addView(lockGroup);

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

        // 恢复保存的状态
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

        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == rbStrict.getId()) {
                if (isLockActive()) {
                    saveMode(GuardService.MODE_STRICT);
                } else {
                    // 尚未选择承诺期：先不保存，等用户在下方选定时长
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

        lockGroup.setOnCheckedChangeListener((group, checkedId) -> {
            RadioButton rb = group.findViewById(checkedId);
            if (rb == null) {
                return;
            }
            long duration = (long) rb.getTag();
            new AlertDialog.Builder(this)
                    .setTitle("确认开启严格模式")
                    .setMessage("承诺期：" + rb.getText() + "\n\n到期之前，你将无法在 App 内切换到限时模式或暂停守护。确定吗？")
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
                    .setNegativeButton("再想想", (d, w) -> group.clearCheck())
                    .show();
        });

        minutesGroup.setOnCheckedChangeListener((group, checkedId) -> {
            RadioButton rb = group.findViewById(checkedId);
            if (rb != null) {
                prefs.edit().putInt(GuardService.KEY_TIMED_MINUTES, (int) rb.getTag()).apply();
            }
        });

        TextView debugLabel = sectionLabel("调试日志（拦截记录，用于排查问题）");
        root.addView(debugLabel);

        debugView = new TextView(this);
        debugView.setTextSize(12);
        debugView.setTextColor(Color.GRAY);
        debugView.setTypeface(Typeface.MONOSPACE);
        root.addView(debugView);

        TextView howto = new TextView(this);
        howto.setTextSize(14);
        howto.setTextColor(Color.DKGRAY);
        howto.setPadding(0, dp(18), 0, 0);
        howto.setText(
                "使用方法：\n" +
                "1. 点击上方按钮，在系统无障碍设置里找到「视频号守门员」并开启。\n" +
                "2. 回到本页确认状态显示「服务运行中」。\n" +
                "3. 选好模式后，在微信里点开视频号链接即可。\n\n" +
                "已知限制：\n" +
                "· 严格模式下，快速滑动评论区也可能触发退出。\n" +
                "· 承诺期只能锁定 App 内的模式切换；\n" +
                "   直接关闭无障碍服务或卸载 App 无法阻止。\n" +
                "· 限时模式的倒计时在服务被系统回收后会失效，\n" +
                "   重新进入视频号会重新开始计时。\n" +
                "· 微信大版本更新后若失效，请联系小沃更新识别规则。"
        );
        root.addView(howto);

        updateVisibility();
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshStats();
        refreshDebugLog();
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
            lockInfoView.setText("严格模式锁定中，" + formatRemaining() + " 后可更改模式");
        } else {
            lockInfoView.setVisibility(android.view.View.GONE);
        }

        // 承诺期选择区：仅在勾选严格模式且未锁定时显示
        boolean showLockPicker = rbStrict.isChecked() && !locked;
        lockLabel.setVisibility(showLockPicker ? android.view.View.VISIBLE : android.view.View.GONE);
        lockGroup.setVisibility(showLockPicker ? android.view.View.VISIBLE : android.view.View.GONE);

        // 限时时长：仅在勾选限时模式时显示
        boolean timed = rbTimed.isChecked();
        minutesLabel.setVisibility(timed ? android.view.View.VISIBLE : android.view.View.GONE);
        minutesGroup.setVisibility(timed ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private String formatRemaining() {
        long remain = prefs.getLong(KEY_LOCK_UNTIL, 0) - System.currentTimeMillis();
        if (remain <= 0) {
            return "已到期";
        }
        long days = TimeUnit.MILLISECONDS.toDays(remain);
        long hours = TimeUnit.MILLISECONDS.toHours(remain) % 24;
        long minutes = TimeUnit.MILLISECONDS.toMinutes(remain) % 60;
        if (days > 0) {
            return "剩余 " + days + " 天 " + hours + " 小时";
        }
        if (hours > 0) {
            return "剩余 " + hours + " 小时 " + minutes + " 分";
        }
        return "剩余 " + minutes + " 分钟";
    }

    private void refreshStatus() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean running = enabled != null && enabled.contains(getPackageName());
        if (running) {
            statusView.setText("服务状态：运行中 ✔");
            statusView.setTextColor(Color.rgb(0x1B, 0x7D, 0x32));
        } else {
            statusView.setText("服务状态：未开启（点下方按钮去开启）");
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
