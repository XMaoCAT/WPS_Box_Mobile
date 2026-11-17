package com.wpsbox.run;

import android.content.Context;
import android.util.Log;

import com.wpsbox.cfg.Config;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Assembles the environment for the WPS process and (re)builds the CEF
 * visibility switch.
 *
 * Why we hide CEF instead of editing WPS config: `wps` does not link libcef
 * directly, it dlopen()s it from addons/cef. Moving that directory out of the
 * way is therefore a complete, reversible off switch that needs no knowledge
 * of WPS internals and cannot corrupt its own configuration files.
 */
public final class Launcher {

    private static final String TAG = "WpsBox";

    private final Context ctx;
    private final Config cfg;
    private final File home;

    public Launcher(Context ctx, Config cfg) {
        this.ctx = ctx;
        this.cfg = cfg;
        this.home = ctx.getFilesDir();
    }

    public File home() { return home; }

    /** Directory that holds the payload (binaries + libs + resources). */
    public File payload() {
        return new File(cfg.rootfs);
    }

    public File office6() {
        return new File(payload(), "office6");
    }

    public File syslib() {
        return new File(payload(), "syslib");
    }

    /** True when the WPS runtime files are actually present. */
    public boolean payloadInstalled() {
        File wps = new File(office6(), "wps");
        File skins = new File(office6(), "skins");
        return wps.exists() && skins.exists();
    }

    private File cefDir() {
        return new File(office6(), "addons/cef");
    }

    private File cefHidden() {
        return new File(payload(), ".cef-disabled");
    }

    /**
     * Apply the CEF switch. Idempotent and safe to call on every start.
     *
     * @return a human-readable description of what was done
     */
    public String applyCefSwitch() {
        File cef = cefDir();
        File hidden = cefHidden();
        try {
            if (!cfg.cefEnable) {
                if (cef.exists() && !hidden.exists()) {
                    if (cef.renameTo(hidden)) {
                        return "CEF 已停用（目录已移出）";
                    }
                    return "CEF 停用失败：无法移动目录";
                }
                return hidden.exists() ? "CEF 已停用" : "CEF 未安装";
            } else {
                if (hidden.exists() && !cef.exists()) {
                    File parent = cef.getParentFile();
                    if (parent != null) parent.mkdirs();
                    if (hidden.renameTo(cef)) {
                        return "CEF 已启用";
                    }
                    return "CEF 启用失败：无法恢复目录";
                }
                return cef.exists() ? "CEF 已启用" : "CEF 未安装";
            }
        } catch (Exception e) {
            return "CEF 切换异常: " + e;
        }
    }

    /** Build the environment for the WPS process. */
    public List<String> buildEnv(String display) {
        List<String> env = new ArrayList<>();
        String office = office6().getAbsolutePath();
        String sys = syslib().getAbsolutePath();

        env.add("DISPLAY=" + display);
        env.add("HOME=" + home.getAbsolutePath());
        env.add("TMPDIR=" + new File(home, "tmp").getAbsolutePath());
        env.add("XDG_RUNTIME_DIR=" + new File(home, "xdg").getAbsolutePath());
        env.add("XDG_CONFIG_HOME=" + new File(home, "config").getAbsolutePath());
        env.add("XDG_CACHE_HOME=" + new File(home, "cache").getAbsolutePath());
        env.add("XDG_DATA_HOME=" + new File(home, "data").getAbsolutePath());
        env.add("PATH=/system/bin:/system/xbin");
        env.add("LANG=" + cfg.locale);
        env.add("LC_ALL=" + cfg.locale);

        // office6 first so WPS's own Qt5.12 wins over anything else,
        // then the 59 non-glibc system libraries.
        env.add("LD_LIBRARY_PATH=" + office + ":" + sys);
        env.add("QT_PLUGIN_PATH=" + office + "/qt/plugins");
        env.add("QT_QPA_PLATFORM=xcb");

        if (cfg.swRender) {
            // /dev/dri is normally inaccessible inside an app sandbox, so
            // force Mesa's llvmpipe software rasteriser.
            env.add("LIBGL_ALWAYS_SOFTWARE=1");
            env.add("GALLIUM_DRIVER=llvmpipe");
            env.add("MESA_LOADER_DRIVER_OVERRIDE=llvmpipe");
        }
        env.add("QT_XKB_CONFIG_ROOT=/system/usr/share/X11/xkb");

        // Keep the JVM-style noise down; WPS is chatty on stderr.
        env.add("QT_LOGGING_RULES=*.debug=false");

        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            String k = e.getKey();
            if (k.startsWith("LD_") || k.startsWith("QT_") || k.equals("DISPLAY")
                    || k.equals("HOME") || k.startsWith("XDG_")) {
                continue;
            }
            env.add(k + "=" + e.getValue());
        }
        return env;
    }

    /** Command line for the WPS binary. */
    public List<String> buildCommand(String component) {
        String exe;
        switch (component) {
            case "et":  exe = "et";  break;   // Spreadsheets
            case "wpp": exe = "wpp"; break;   // Presentation
            default:    exe = "wps"; break;   // Writer
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(new File(office6(), exe).getAbsolutePath());
        return cmd;
    }

    /** Make sure every writable directory the payload expects exists. */
    public void prepareDirs() {
        String[] dirs = {"tmp", "xdg", "config", "cache", "data"};
        for (String d : dirs) {
            File f = new File(home, d);
            if (!f.exists()) f.mkdirs();
        }
        try {
            // XDG_RUNTIME_DIR must be 0700 or Qt complains loudly.
            File xdg = new File(home, "xdg");
            xdg.setReadable(true, true);
            xdg.setWritable(true, true);
            xdg.setExecutable(true, true);
        } catch (Exception ignored) {
        }
    }

    /**
     * Write a small shell wrapper. Running through sh gives us a place to
     * set ulimits and to log the exit status, which is how the circuit
     * breaker learns that a launch died.
     */
    public File writeRunner(String display, String component) throws Exception {
        File sh = new File(home, "run-wps.sh");
        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        sb.append("# generated by WpsBox\n");
        sb.append("ulimit -c 0 2>/dev/null\n");
        for (String e : buildEnv(display)) {
            sb.append("export ").append(e).append("\n");
        }
        sb.append("cd ").append(office6().getAbsolutePath()).append("\n");
        sb.append("exec ").append(new File(office6(),
                component == null ? "wps" : component).getAbsolutePath()).append("\n");

        Writer w = new OutputStreamWriter(new FileOutputStream(sh), StandardCharsets.UTF_8);
        try {
            w.write(sb.toString());
        } finally {
            w.close();
        }
        sh.setExecutable(true, false);
        sh.setReadable(true, false);
        return sh;
    }

    public static String readFile(File f) {
        if (!f.exists()) return "";
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            try {
                String l;
                while ((l = r.readLine()) != null) sb.append(l).append('\n');
            } finally {
                r.close();
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }
}
