package com.xingyuan.channelsguard;

import android.app.Activity;
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

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private TextView statusView;
    private TextView statsView;
    private RadioGroup modeGroup;
    private RadioGroup minutesGroup;
    private TextView minutesLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences(GuardService.PREFS, MODE_PRIVATE);

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

        TextView modeLabel = sectionLabel("守护模式");
        root.addView(modeLabel);

        modeGroup = new RadioGroup(this);
        RadioButton rbStrict = new RadioButton(this);
        rbStrict.setText("严格模式：在视频号里一滑动就退出");
        RadioButton rbTimed = new RadioButton(this);
        rbTimed.setText("限时模式：可自由观看，到点自动退出");
        RadioButton rbOff = new RadioButton(this);
        rbOff.setText("暂停守护");
        modeGroup.addView(rbStrict);
        modeGroup.addView(rbTimed);
        modeGroup.addView(rbOff);
        root.addView(modeGroup);

        minutesLabel = sectionLabel("限时时长");
        root.addView(minutesLabel);

        minutesGroup = new RadioGroup(this);
        minutesGroup.setOrientation(RadioGroup.HORIZONTAL);
        int[] options = {5, 10, 20};
        for (int m : options) {
            RadioButton rb = new RadioButton(this);
            rb.setText(m + " 分钟");
            rb.setId(View_id(m));
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
        updateMinutesVisibility();

        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            String newMode = GuardService.MODE_STRICT;
            if (checkedId == rbTimed.getId()) {
                newMode = GuardService.MODE_TIMED;
            } else if (checkedId == rbOff.getId()) {
                newMode = GuardService.MODE_OFF;
            }
            prefs.edit().putString(GuardService.KEY_MODE, newMode).apply();
            updateMinutesVisibility();
        });

        minutesGroup.setOnCheckedChangeListener((group, checkedId) -> {
            RadioButton rb = group.findViewById(checkedId);
            if (rb != null) {
                prefs.edit().putInt(GuardService.KEY_TIMED_MINUTES, (int) rb.getTag()).apply();
            }
        });

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
                "· 严格模式下，滑动评论区也会触发退出。\n" +
                "· 限时模式的倒计时在服务被系统回收后会失效，\n" +
                "   重新进入视频号会重新开始计时。\n" +
                "· 微信大版本更新后若失效，请联系小沃更新识别规则。"
        );
        root.addView(howto);

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshStats();
    }

    private void updateMinutesVisibility() {
        boolean timed = false;
        if (modeGroup.getChildAt(1) instanceof RadioButton) {
            timed = ((RadioButton) modeGroup.getChildAt(1)).isChecked();
        }
        int vis = timed ? android.view.View.VISIBLE : android.view.View.GONE;
        minutesLabel.setVisibility(vis);
        minutesGroup.setVisibility(vis);
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
        SharedPreferences prefs = getSharedPreferences(GuardService.PREFS, MODE_PRIVATE);
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

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(Color.GRAY);
        tv.setPadding(0, dp(18), 0, dp(4));
        return tv;
    }

    private int View_id(int minutes) {
        // 用固定偏移生成稳定 id，避免与系统 id 冲突
        return 1000 + minutes;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
