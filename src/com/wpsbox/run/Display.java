package com.wpsbox.run;

import android.util.Log;

import com.wpsbox.cfg.Config;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runs the display server that WPS draws into.
 *
 * The payload ships weston with its RDP backend, which is what the original
 * Xiaomi package uses: the desktop is a Wayland compositor and the client is
 * any RDP viewer. That choice is not aesthetic. WPS links a private Qt whose
 * only platform plugin is xcb, so an X server must exist, and weston supplies
 * one through Xwayland while also exposing the finished desktop over RDP.
 *
 * Xvfb is not an option: the payload has no Xvfb and the host's build needs a
 * newer glibc than the runtime provides.
 *
 * The compositor runs under the same proot regime as WPS, for the same reason:
 * it is a desktop binary and cannot be executed from the private directory.
 */
public final class Display {

    private static final String TAG = "WpsBox";

    private static Process weston;

    public static boolean isRunning() {
        return weston != null && weston.isAlive();
    }

    /** Write a weston.ini that binds the RDP backend to the configured port. */
    public static File writeConfig(File home, Config cfg, Consumer<String> log) throws Exception {
        File dir = new File(home, "weston");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("cannot create " + dir);
        File ini = new File(dir, "weston.ini");

        StringBuilder sb = new StringBuilder();
        sb.append("[core]\n");
        sb.append("backend=rdp-backend.so\n");
        sb.append("xwayland=true\n");
        sb.append("idle-time=0\n");
        sb.append("require-input=false\n");
        sb.append("\n[output]\n");
        sb.append("name=rdp\n");
        sb.append("mode=").append(cfg.width).append("x").append(cfg.height).append("\n");
        sb.append("\n[rdp]\n");
        sb.append("tls=false\n");
        sb.append("port=").append(cfg.rdpPort).append("\n");
        sb.append("\n[shell]\n");
        sb.append("panel-position=none\n");
        sb.append("background-color=0xff1a1a1a\n");

        try (FileOutputStream o = new FileOutputStream(ini)) {
            o.write(sb.toString().getBytes("UTF-8"));
        }
        log.accept("已写入显示配置 " + ini.getAbsolutePath());
        return ini;
    }

    /**
     * Start the compositor.
     *
     * The bindings mirror the WPS launch: the display payload comes in at
     * /display, a writable directory at /tmp for the X socket and lock files,
     * and the runtime directory where Wayland puts its socket.
     */
    public static void start(Runner runner, File home, Config cfg, Consumer<String> log)
            throws Exception {
        if (isRunning()) { log.accept("显示服务已在运行"); return; }

        File ini = writeConfig(home, cfg, log);
        File tmp = runner.tmpDir();
        if (!tmp.exists()) tmp.mkdirs();
        File xsock = new File(tmp, ".X11-unix");
        if (!xsock.exists()) xsock.mkdirs();
        try { xsock.setExecutable(true, false); xsock.setWritable(true, false); } catch (Exception ignored) { }
        File runtime = new File(tmp, "wayland");
        if (!runtime.exists()) runtime.mkdirs();

        List<String> cmd = new ArrayList<>();
        cmd.add(runner.proot().getAbsolutePath());
        cmd.add("-r"); cmd.add("/");
        cmd.add("-b"); cmd.add(runner.displayDir().getAbsolutePath() + ":/display");
        cmd.add("-b"); cmd.add(runner.syslibDir().getAbsolutePath() + ":/syslib");
        cmd.add("-b"); cmd.add(runner.officeDir().getAbsolutePath() + ":/w");
        cmd.add("-b"); cmd.add(tmp.getAbsolutePath() + ":/tmp");
        cmd.add("-b"); cmd.add(runtime.getAbsolutePath() + ":/run");
        cmd.add("-b"); cmd.add(ini.getParentFile().getAbsolutePath() + ":/cfg");
        cmd.add("-w"); cmd.add("/cfg");
        cmd.add("/display/ld-linux-aarch64.so.1");
        cmd.add("--library-path"); cmd.add("/display:/syslib:/w");
        cmd.add("/display/weston");
        cmd.add("--config=/cfg/" + ini.getName());

        log.accept("启动显示服务 (RDP 端口 " + cfg.rdpPort + ")");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.environment().put("XDG_RUNTIME_DIR", "/run");
        pb.environment().put("HOME", "/cfg");
        pb.environment().put("XDG_CONFIG_HOME", "/cfg");
        pb.environment().put("XKB_CONFIG_ROOT", "/display/xkb");
        pb.environment().put("WESTON_MODULE_DIR", "/display");
        pb.environment().put("LIBGL_ALWAYS_SOFTWARE", "1");
        pb.environment().put("EGL_PLATFORM", "surfaceless");

        weston = pb.start();
        Thread pump = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(weston.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    Log.i(TAG, "weston: " + line);
                    log.accept(line);
                }
            } catch (Exception ignored) { }
        });
        pump.setDaemon(true);
        pump.start();
    }

    public static void stop() {
        Process p = weston;
        weston = null;
        if (p == null) return;
        p.destroy();
        try { p.waitFor(); } catch (InterruptedException ignored) { }
        if (p.isAlive()) p.destroyForcibly();
    }
}
