package com.wpsbox.run;

import android.util.Log;

import com.wpsbox.cfg.Config;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the WPS process and tells the circuit breaker whether a launch worked.
 *
 * The health signal is external rather than reported by WPS: a launch counts
 * as good only once the process has stayed up past a grace period. A crash on
 * startup dies well inside that window, so it is classified as a failure and
 * feeds the breaker. This matters because a broken payload or an unsupported
 * device shows up as an immediate exit, not as a clean error.
 */
public final class ProcessManager {

    private static final String TAG = "WpsBox";
    /** How long the process must survive before the launch counts as good. */
    private static final long STARTUP_GRACE_MS = 15_000L;

    public interface Listener {
        void onLog(String line);
        void onState(String state);
        void onCrash(String reason);
    }

    private final Runner runner;
    private final Config cfg;
    private Process proc;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Listener listener;
    private volatile boolean userStopped = false;

    public ProcessManager(Runner runner, Config cfg) {
        this.runner = runner;
        this.cfg = cfg;
    }

    public void setListener(Listener l) { this.listener = l; }
    public boolean isRunning() { return running.get(); }

    private void log(String s) {
        Log.i(TAG, s);
        Listener l = listener;
        if (l != null) l.onLog(s);
    }

    private void state(String s) {
        Log.i(TAG, "state=" + s);
        Listener l = listener;
        if (l != null) l.onState(s);
    }

    /**
     * The command that starts WPS.
     *
     * proot performs the bindings, then the loader starts the binary with the
     * payload directory on its search path. The working directory is set to
     * the payload directory as well, because WPS opens several files by
     * relative name during startup.
     */
    public List<String> buildCommand(String component) {
        File office = runner.officeDir();
        File syslib = runner.syslibDir();
        File tmp = runner.tmpDir();

        String exe = runner.wps().getAbsolutePath();
        String guestExe = "/w/wps";
        if ("et".equals(component)) { guestExe = "/w/et"; }
        if ("wpp".equals(component)) { guestExe = "/w/wpp"; }

        List<String> cmd = new ArrayList<>();
        cmd.add(runner.proot().getAbsolutePath());
        cmd.add("-r"); cmd.add("/");
        cmd.add("-b"); cmd.add(office.getAbsolutePath() + ":/w");
        cmd.add("-b"); cmd.add(runner.loader().getAbsolutePath() + ":/w/ld.so");
        cmd.add("-b"); cmd.add(runner.wps().getAbsolutePath() + ":/w/wps");
        cmd.add("-b"); cmd.add(syslib.getAbsolutePath() + ":/syslib");
        cmd.add("-b"); cmd.add(runner.displayDir().getAbsolutePath() + ":/display");
        cmd.add("-b"); cmd.add(tmp.getAbsolutePath() + ":/tmp");
        cmd.add("-w"); cmd.add("/w");
        cmd.add("/w/ld.so");
        cmd.add("--library-path"); cmd.add("/w:/syslib:/display");
        cmd.add(guestExe);
        return cmd;
    }

    /** Environment that WPS and the display server need. */
    public String[] buildEnv(File home) {
        File office = runner.officeDir();
        return new String[]{
                "HOME=" + home.getAbsolutePath(),
                "TMPDIR=" + runner.tmpDir().getAbsolutePath(),
                "XDG_RUNTIME_DIR=" + runner.tmpDir().getAbsolutePath(),
                "XDG_CONFIG_HOME=" + home.getAbsolutePath(),
                "QT_PLUGIN_PATH=/w/qt/plugins",
                "QT_QPA_PLATFORM=xcb",
                "LANG=" + cfg.locale,
                "LC_ALL=" + cfg.locale,
                "LIBGL_ALWAYS_SOFTWARE=1",
                "GALLIUM_DRIVER=llvmpipe",
        };
    }

    /**
     * Start WPS and watch it.
     *
     * The watcher is what feeds the circuit breaker: an early exit is reported
     * as a crash, a long-lived process as a success.
     */
    public void start(String component, File home) throws IOException {
        if (running.get()) { log("已在运行"); return; }
        userStopped = false;

        List<String> cmd = buildCommand(component);
        log("启动 " + component);
        log(String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(runner.officeDir());
        pb.redirectErrorStream(true);
        for (String e : buildEnv(home)) {
            int eq = e.indexOf('=');
            pb.environment().put(e.substring(0, eq), e.substring(eq + 1));
        }

        final long started = System.currentTimeMillis();
        proc = pb.start();
        running.set(true);
        state("running");

        Thread pump = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) log(line);
            } catch (Exception ignored) { }
        });
        pump.setDaemon(true);
        pump.start();

        Thread watch = new Thread(() -> {
            int rc = -1;
            try { rc = proc.waitFor(); } catch (InterruptedException ignored) { }
            running.set(false);
            long lived = System.currentTimeMillis() - started;
            if (userStopped) { state("stopped"); return; }
            if (lived < STARTUP_GRACE_MS) {
                state("crashed");
                String reason = "启动后 " + (lived / 1000) + " 秒退出，返回码 " + rc;
                log(reason);
                Listener l = listener;
                if (l != null) l.onCrash(reason);
            } else {
                state("stopped");
                cfg.noteSuccess(home);
            com.wpsbox.cfg.CrashLog.reset(home);
            }
        });
        watch.setDaemon(true);
        watch.start();
    }

    public void stop() {
        userStopped = true;
        Process p = proc;
        if (p != null) {
            p.destroy();
            try { p.waitFor(); } catch (InterruptedException ignored) { }
            if (p.isAlive()) p.destroyForcibly();
        }
        running.set(false);
        state("stopped");
    }
}
