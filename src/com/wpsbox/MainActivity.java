package com.wpsbox;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.wpsbox.cfg.Config;
import com.wpsbox.cfg.CrashLog;
import com.wpsbox.run.Display;
import com.wpsbox.run.ProcessManager;
import com.wpsbox.run.Runner;

import java.io.File;

/**
 * The launcher screen.
 *
 * Three states matter to the user: the payload is still being unpacked, it is
 * ready, or something went wrong. The screen says which, offers the three WPS
 * components, and carries the one switch the user asked for: whether CEF,
 * which supplies the online half of the application, is loaded at all.
 *
 * Unpacking a third of a gigabyte takes a visible amount of time on a phone,
 * so it happens on a background thread with progress, and it is skipped
 * entirely once the tree is complete.
 */
public class MainActivity extends Activity implements ProcessManager.Listener {

    private Config cfg;
    private Runner runner;
    private ProcessManager pm;
    private File home;

    private TextView status;
    private TextView logView;
    private Switch cefSwitch;
    private TextView cefNote;
    private ProgressBar progress;
    private final StringBuilder logBuf = new StringBuilder();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Button[] compButtons;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        home = getFilesDir();
        cfg = Config.load(home);
        runner = new Runner(this);
        pm = new ProcessManager(runner, cfg);
        pm.setListener(this);
        setContentView(buildUi());
        refreshCefUi();
        ensurePayload();
    }

    private int dp(int v) { return Math.round(getResources().getDisplayMetrics().density * v); }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("WPS 桌面版");
        title.setTextSize(22f);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(13f);
        status.setPadding(0, dp(6), 0, dp(10));
        root.addView(status);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        // the three components share one engine, each starts its own binary
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        compButtons = new Button[3];
        String[] labels = {"文字", "表格", "演示"};
        String[] comps = {"wps", "et", "wpp"};
        for (int i = 0; i < labels.length; i++) {
            final String comp = comps[i];
            Button bt = new Button(this);
            bt.setText(labels[i]);
            bt.setEnabled(false);
            bt.setOnClickListener(v -> launch(comp));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(bt, lp);
            compButtons[i] = bt;
        }
        root.addView(row);

        cefSwitch = new Switch(this);
        cefSwitch.setText("加载在线功能 (CEF)");
        cefSwitch.setOnCheckedChangeListener((v, checked) -> {
            cfg.cefEnable = checked;
            if (!checked) { cfg.cefAutoDisabled = false; cfg.cefDisabledReason = ""; }
            cfg.save(home);
            refreshCefUi();
        });
        root.addView(cefSwitch);

        cefNote = new TextView(this);
        cefNote.setTextSize(11f);
        cefNote.setPadding(0, 0, 0, dp(8));
        root.addView(cefNote);

        Button stop = new Button(this);
        stop.setText("停止");
        stop.setOnClickListener(v -> {
            pm.stop();
            Display.stop();
            status.setText("已停止");
        });
        root.addView(stop);

        TextView logTitle = new TextView(this);
        logTitle.setText("运行日志");
        logTitle.setTextSize(12f);
        logTitle.setPadding(0, dp(12), 0, dp(4));
        root.addView(logTitle);

        logView = new TextView(this);
        logView.setTextSize(9f);
        ScrollView sv = new ScrollView(this);
        sv.addView(logView);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        ScrollView outer = new ScrollView(this);
        outer.addView(root);
        return outer;
    }

    /** Unpack on first run, then enable the buttons. */
    private void ensurePayload() {
        if (runner.payloadReady()) {
            status.setText("就绪");
            enableButtons(true);
            return;
        }
        status.setText("首次启动，正在准备运行环境");
        progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                runner.unpack(done -> ui.post(() -> {
                    // the asset is compressed; the unpacked size is unknown
                    // up front, so the bar shows activity rather than a ratio
                }));
                ui.post(() -> {
                    progress.setVisibility(View.GONE);
                    status.setText("就绪");
                    enableButtons(true);
                    appendLog("运行环境已就绪");
                });
            } catch (Exception e) {
                ui.post(() -> {
                    progress.setVisibility(View.GONE);
                    status.setText("准备失败: " + e.getMessage());
                    appendLog("解压失败: " + e);
                });
            }
        }).start();
    }

    private void enableButtons(boolean on) {
        if (compButtons == null) return;
        for (Button bt : compButtons) bt.setEnabled(on);
    }

    private void launch(String comp) {
        if (!runner.payloadReady()) { status.setText("运行环境未就绪"); return; }
        status.setText("正在启动…");
        appendLog("=== 启动 " + comp + " ===");
        new Thread(() -> {
            try {
                Display.start(runner, new File(home, "cfg"), cfg, this::appendLog);
                Thread.sleep(4000);   // let the compositor bind its port
                pm.start(comp, home);
                ui.post(() -> status.setText("已启动，请用 RDP 客户端连接 127.0.0.1:"
                        + cfg.rdpPort));
            } catch (Exception e) {
                ui.post(() -> {
                    status.setText("启动失败: " + e.getMessage());
                    appendLog("失败: " + e);
                });
            }
        }).start();
    }

    private void refreshCefUi() {
        cefSwitch.setChecked(cfg.cefEnable);
        if (cfg.cefAutoDisabled) {
            cefNote.setText("已自动停用，原因：" + cfg.cefDisabledReason
                    + "。可手动重新打开。");
        } else {
            cefNote.setText("关闭后不加载云端与网页模块；离线编辑不受影响。");
        }
    }

    private void appendLog(String s) {
        ui.post(() -> {
            logBuf.append(s).append('\n');
            if (logBuf.length() > 12000) logBuf.delete(0, logBuf.length() - 12000);
            logView.setText(logBuf);
        });
    }

    @Override public void onLog(String line) { appendLog(line); }

    @Override
    public void onState(String state) {
        ui.post(() -> {
            if ("running".equals(state)) status.setText("运行中");
        });
    }

    @Override
    public void onCrash(String reason) {
        boolean tripped = cfg.noteCrash(home);
        CrashLog.append(home, "crash", reason);
        ui.post(() -> {
            status.setText("启动失败: " + reason);
            if (tripped) {
                refreshCefUi();
                Toast.makeText(this, "连续失败，已自动停用 CEF", Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pm.stop();
        Display.stop();
    }
}
