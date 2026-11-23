package com.xyh.gimbal;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 最小演示界面：连接 / 左右转 / 回中 / 心跳 / 自动回中 / 换速度档。
 * UI 全部用代码构建，没有 res 资源，方便整目录搬走。
 */
public class MainActivity extends Activity implements GimbalBle.Listener {

    private TextView stateView;
    private TextView logView;
    private ScrollView logScroll;
    private Button speedBtn;

    private GimbalBle gimbal;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        gimbal = new GimbalBle(this, this);
        askPermissions();
    }

    private static final int REQ_PERM = 100;

    private void askPermissions() {
        String[] perms;
        if (Build.VERSION.SDK_INT >= 31) {
            perms = new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        } else {
            perms = new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        }
        boolean need = false;
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need = true;
        }
        if (need) {
            requestPermissions(perms, REQ_PERM);
        } else {
            log("权限已就绪，点“连接云台”");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            boolean ok = grantResults.length > 0;
            for (int r : grantResults) ok &= (r == PackageManager.PERMISSION_GRANTED);
            log(ok ? "权限已授予，点“连接云台”" : "权限被拒绝，扫描会失败");
        }
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101014);
        int pad = dp(16);
        root.setPadding(pad, pad + dp(8), pad, pad);

        stateView = new TextView(this);
        stateView.setTextColor(0xFF7FD1FF);
        stateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        stateView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        stateView.setText("状态：idle");
        root.addView(stateView, matchWrap());

        Button connect = button("连接云台");
        connect.setOnClickListener(v -> gimbal.connect());
        root.addView(connect, rowParams());

        LinearLayout row1 = row();
        Button right = button("右 45°");
        right.setOnClickListener(v -> gimbal.turn(45, gimbal.getMode()));
        row1.addView(right, cellParams());

        Button left = button("左 45°");
        left.setOnClickListener(v -> gimbal.turn(-45, gimbal.getMode()));
        row1.addView(left, cellParams());
        root.addView(row1, rowParams());

        LinearLayout row2 = row();
        Button home = button("回中");
        home.setOnClickListener(v -> gimbal.home());
        row2.addView(home, cellParams());

        Button beat = button("心跳 开/关");
        beat.setOnClickListener(v -> {
            if (gimbal.isReady()) {
                gimbal.startHeartbeat();
            } else {
                log("先连接");
            }
        });
        row2.addView(beat, cellParams());
        root.addView(row2, rowParams());

        LinearLayout row3 = row();
        speedBtn = button(speedLabel());
        speedBtn.setOnClickListener(v -> {
            int m = gimbal.getMode() % 4 + 1;
            gimbal.setMode(m);
            speedBtn.setText(speedLabel());
        });
        row3.addView(speedBtn, cellParams());

        Button autoHome = button("自动回中 开/关");
        autoHome.setOnClickListener(v -> gimbal.setAutoHome(!autoHomeOn, 10000));
        autoHomeOn = !autoHomeOn;
        row3.addView(autoHome, cellParams());
        root.addView(row3, rowParams());

        logView = new TextView(this);
        logView.setTextColor(0xFFBBBBBB);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(dp(6), dp(6), dp(6), dp(6));

        logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(0xFF16161C);
        logScroll.addView(logView);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(12);
        root.addView(logScroll, lp);

        return root;
    }

    private boolean autoHomeOn = false;

    private String speedLabel() {
        int m = gimbal == null ? 3 : gimbal.getMode();
        return "速度档 " + m + "（" + GimbalBle.MS_PER_DEG[m - 1] + "ms/度）";
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        return b;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private ViewGroup.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private ViewGroup.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        return lp;
    }

    private ViewGroup.LayoutParams cellParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(dp(6));
        return lp;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    // ------------------------------------------------------------------ 回调

    @Override
    public void onLog(String msg) {
        log(msg);
    }

    @Override
    public void onState(String state) {
        runOnUiThread(() -> stateView.setText("状态：" + state));
    }

    private void log(final String msg) {
        runOnUiThread(() -> {
            if (logView == null) return;
            logView.append(msg + "\n");
            if (logScroll != null) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    protected void onDestroy() {
        if (gimbal != null) gimbal.disconnect();
        super.onDestroy();
    }
}
