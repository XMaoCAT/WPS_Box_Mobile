package com.wpsbox.cfg;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Append-only launch journal used by the CEF circuit breaker.
 *
 * Every launch writes one line. A launch is "clean" when WPSSignsOfLife is
 * observed (a window appears); otherwise it is a failure. The breaker counts
 * TRAILING consecutive failures, so one good run resets the counter -- that
 * matters because CEF problems are usually intermittent during startup.
 *
 * The journal is capped so it cannot grow without bound.
 */
public final class CrashLog {

    private static final String NAME = "launch.log";
    private static final int MAX_LINES = 400;
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private CrashLog() {}

    private static File f(File home) {
        return new File(home, NAME);
    }

    /** kind: "start" | "ok" | "fail" | "kill" */
    public static synchronized void append(File home, String kind, String detail) {
        try {
            File file = f(home);
            List<String> lines = readAll(file);
            lines.add(TS.format(new Date()) + "\t" + kind + "\t"
                    + (detail == null ? "" : detail.replace('\n', ' ')));
            while (lines.size() > MAX_LINES) lines.remove(0);
            Writer w = new OutputStreamWriter(new FileOutputStream(file, false),
                    StandardCharsets.UTF_8);
            try {
                for (String l : lines) w.write(l + "\n");
            } finally {
                w.close();
            }
        } catch (Exception ignored) {
        }
    }

    private static List<String> readAll(File file) {
        List<String> out = new ArrayList<>();
        if (!file.exists()) return out;
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            try {
                String l;
                while ((l = r.readLine()) != null) if (!l.trim().isEmpty()) out.add(l);
            } finally {
                r.close();
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /**
     * How many launch attempts since the last successful one ended in failure.
     * A "start" with no following "ok" or "fail" counts as failed, because
     * that is exactly what a hard crash looks like from here.
     */
    public static synchronized int consecutiveFailures(File home) {
        List<String> lines = readAll(f(home));
        int fails = 0;
        boolean pendingStart = false;
        for (String l : lines) {
            String[] p = l.split("\t", -1);
            if (p.length < 2) continue;
            String kind = p[1];
            if (kind.equals("start")) {
                if (pendingStart) fails++;   // previous start never resolved
                pendingStart = true;
            } else if (kind.equals("ok")) {
                fails = 0;
                pendingStart = false;
            } else if (kind.equals("fail")) {
                fails++;
                pendingStart = false;
            }
        }
        if (pendingStart) fails++;
        return fails;
    }

    /** Clean slate after the user re-enables CEF by hand. */
    public static synchronized void reset(File home) {
        try {
            f(home).delete();
        } catch (Exception ignored) {
        }
    }
}
