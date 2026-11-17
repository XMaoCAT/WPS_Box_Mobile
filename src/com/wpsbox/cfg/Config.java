package com.wpsbox.cfg;

import java.io.File;
import java.util.Map;

/**
 * The user-facing configuration, persisted as YAML next to the payload.
 *
 * The user asked for two things specifically:
 *   1. a YAML switch for CEF, 1 = on / 0 = off, so they can choose
 *   2. if CEF causes repeated crashes, the app must auto-disable it
 *
 * Both live here. The circuit breaker writes `cef.enable: 0` back to disk
 * once the crash threshold is crossed, so the choice survives a restart
 * and the user can flip it back on whenever they want.
 */
public final class Config {

    public static final String FILE_NAME = "wpsbox.yaml";

    // ---- paths -----------------------------------------------------------
    public String rootfs;          // payload dir (kept for config compat)
    public String display = ":99";
    public int width = 1600;
    public int height = 1000;
    public int depth = 24;

    // ---- CEF -------------------------------------------------------------
    /** 1 = load CEF (cloud docs, embedded web), 0 = skip it entirely. */
    public boolean cefEnable = true;
    /** Consecutive crashes after which CEF is switched off automatically. */
    public int cefCrashThreshold = 3;
    /** Set by the breaker itself; shown in the UI as "auto-disabled". */
    public boolean cefAutoDisabled = false;
    public String cefDisabledReason = "";

    // ---- runtime ---------------------------------------------------------
    public boolean vncEnable = true;
    public int vncPort = 5900;
    /** RDP is how the desktop surface reaches the Android side. */
    public boolean rdpEnable = true;
    public int rdpPort = 3389;   // matches weston's rdp-backend default
    public boolean swRender = true;   // llvmpipe; /dev/dri is usually denied
    public String locale = "zh_CN.UTF-8";

    // ---- telemetry kept simple -------------------------------------------
    public int launchCount = 0;
    public int crashCount = 0;

    // ---------------------------------------------------------------------

    public static File fileFor(File home) {
        return new File(home, FILE_NAME);
    }

    public static Config load(File home) {
        Config c = new Config();
        File f = fileFor(home);
        if (!f.exists()) {
            c.rootfs = new File(home, "payload").getAbsolutePath();
            c.save(home);
            return c;
        }
        try {
            MiniYaml.Doc d = MiniYaml.parse(f);
            c.rootfs = d.str("rootfs", new File(home, "payload").getAbsolutePath());
            c.display = d.str("display", c.display);
            c.width = d.integer("display_width", c.width);
            c.height = d.integer("display_height", c.height);
            c.depth = d.integer("display_depth", c.depth);
            c.cefEnable = d.bool("cef.enable", c.cefEnable);
            c.cefCrashThreshold = d.integer("cef.crash_threshold", c.cefCrashThreshold);
            c.cefAutoDisabled = d.bool("cef.auto_disabled", c.cefAutoDisabled);
            c.cefDisabledReason = d.str("cef.auto_disabled_reason", "");
            c.vncEnable = d.bool("vnc.enable", c.vncEnable);
            c.vncPort = d.integer("vnc.port", c.vncPort);
            c.rdpEnable = d.bool("rdp.enable", c.rdpEnable);
            c.rdpPort = d.integer("rdp.port", c.rdpPort);
            c.swRender = d.bool("render.software", c.swRender);
            c.locale = d.str("locale", c.locale);
            c.launchCount = d.integer("stats.launch_count", 0);
            c.crashCount = d.integer("stats.crash_count", 0);
        } catch (Exception e) {
            // A corrupt config must never brick the app: fall back to defaults.
            c.rootfs = new File(home, "payload").getAbsolutePath();
        }
        return c;
    }

    public void save(File home) {
        MiniYaml.Doc d = new MiniYaml.Doc();
        d.put("rootfs", rootfs);
        d.put("display", display);
        d.put("display_width", width);
        d.put("display_height", height);
        d.put("display_depth", depth);
        d.put("locale", locale);

        d.put("cef.enable", cefEnable ? 1 : 0);
        d.put("cef.crash_threshold", cefCrashThreshold);
        d.put("cef.auto_disabled", cefAutoDisabled ? 1 : 0);
        d.put("cef.auto_disabled_reason", cefDisabledReason);
        d.put("cef.comment",
                "enable: 1=load CEF (cloud/online features), 0=off; "
              + "auto-disabled flips enable to 0 after repeated crashes");

        d.put("vnc.enable", vncEnable ? 1 : 0);
        d.put("vnc.port", vncPort);
        d.put("rdp.enable", rdpEnable ? 1 : 0);
        d.put("rdp.port", rdpPort);
        d.put("render.software", swRender ? 1 : 0);
        d.put("stats.launch_count", launchCount);
        d.put("stats.crash_count", crashCount);
        try {
            MiniYaml.save(fileFor(home), d);
        } catch (Exception ignored) {
        }
    }

    /**
     * Record the outcome of a launch. Returns true when the breaker just
     * tripped, so the caller can tell the user.
     */
    public boolean noteCrash(File home) {
        crashCount++;
        if (!cefEnable) {
            save(home);
            return false;
        }
        int consecutive = CrashLog.consecutiveFailures(home);
        if (consecutive >= cefCrashThreshold) {
            cefEnable = false;
            cefAutoDisabled = true;
            cefDisabledReason = "连续 " + consecutive + " 次启动失败，已自动停用 CEF";
            save(home);
            return true;
        }
        save(home);
        return false;
    }

    public void noteSuccess(File home) {
        launchCount++;
        save(home);
    }
}
