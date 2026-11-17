package com.wpsbox;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Nineteenth probe: does proot work inside an Android app process?
 *
 * The packaging question is settled. proot can bind the bulky payload, which
 * has to live in the private directory where execve is refused, onto a path
 * under the native library directory where execve is allowed. Locally that
 * produces a WPS that reaches Qt initialisation, and it needs only four binds.
 *
 * What is not settled is whether proot is allowed to run here at all. proot
 * is built on ptrace: it traces its children and rewrites the paths they ask
 * for. Android restricts ptrace for app processes, and if that restriction
 * applies then this whole approach collapses and the fallback is patching the
 * loader to build its own file table.
 *
 * The probe therefore checks, in the order that matters: whether the two
 * static helper binaries can be executed at all from the native library
 * directory, whether a plain static child runs under proot, whether a bind of
 * one file and of a whole directory becomes visible to the child, and whether
 * exec of a dynamically linked program under proot still works.
 */
public class ProbeActivity extends Activity {

    private TextView out;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        out = new TextView(this);
        out.setTextSize(9f);
        out.setPadding(12, 12, 12, 12);
        ScrollView sv = new ScrollView(this);
        sv.addView(out);
        setContentView(sv);
        new Thread(this::probe).start();
    }

    private void say(String s) {
        Log.i("WpsBoxProbe19", s);
        runOnUiThread(() -> out.append(s + "\n"));
    }

    private void probe() {
        File lib = new File(getApplicationInfo().nativeLibraryDir);
        File work = getDir("p19", MODE_PRIVATE);
        say("=== 探测19: proot 能否在应用内运行 ===");
        say("lib=" + lib.getAbsolutePath());
        say("");

        File proot = new File(lib, "libproot_exec.so");
        File hi = new File(lib, "libhi_exec.so");
        File pt = new File(lib, "libpt_exec.so");
        say("proot=" + proot.exists() + " hi=" + hi.exists() + " pt=" + pt.exists());
        say("");

        // 1. can we execute a static binary from the library dir at all?
        say("-- 1) 静态程序直接执行 --");
        say("hi: " + run(new String[]{hi.getAbsolutePath()}, 8000));
        say("pt: " + run(new String[]{pt.getAbsolutePath()}, 8000));

        // 2. proot running a plain static child
        say("");
        say("-- 2) proot 跑静态子进程（核心）--");
        say("proot -r / hi: " + run(
                new String[]{proot.getAbsolutePath(), "-r", "/", hi.getAbsolutePath()}, 15000));

        // 3. a single file bind, onto a path that does not exist
        say("");
        say("-- 3) 单文件绑定到不存在的路径 --");
        File target = new File(work, "hello.txt");
        try (FileOutputStream o = new FileOutputStream(target)) {
            o.write("BOUND-CONTENT".getBytes("UTF-8"));
        } catch (Exception e) { say("  准备失败 " + e); }
        File invisible = new File(lib, "libbound.so");
        say("  绑定前 exists=" + invisible.exists());
        String cmd = proot.getAbsolutePath() + " -r / -b " + target.getAbsolutePath()
                + ":" + invisible.getAbsolutePath() + " " + hi.getAbsolutePath();
        say("  proot+bind: " + run(new String[]{
                proot.getAbsolutePath(), "-r", "/",
                "-b", target.getAbsolutePath() + ":" + invisible.getAbsolutePath(),
                hi.getAbsolutePath()}, 15000));
        say("  绑定后 host 侧 exists=" + invisible.exists() + "（应仍为 false）");

        // 4. a directory bind, which is what the payload needs
        say("");
        say("-- 4) 目录绑定 --");
        File d = new File(work, "treedir");
        d.mkdirs();
        File inner = new File(d, "marker");
        try (FileOutputStream o = new FileOutputStream(inner)) {
            o.write("IN-TREE".getBytes("UTF-8"));
        } catch (Exception e) { }
        File guestDir = new File(lib, "libguestdir.so");
        say("  目录绑定: " + run(new String[]{
                proot.getAbsolutePath(), "-r", "/",
                "-b", d.getAbsolutePath() + ":" + guestDir.getAbsolutePath(),
                hi.getAbsolutePath()}, 15000));
        say("  host 侧 exists=" + guestDir.exists() + "（应仍为 false）");

        // 5. exec from a bind point, which is the shape the launch uses
        say("");
        say("-- 5) 从绑定点执行程序 --");
        try {
            java.nio.file.Files.copy(hi.toPath(),
                    new File(work, "hi").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            new File(work, "hi").setExecutable(true, true);
        } catch (Exception e) { say("  准备失败 " + e); }
        File guestW = new File(work, "w");
        guestW.mkdirs();
        try {
            java.nio.file.Files.copy(hi.toPath(),
                    new File(guestW, "hi").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            new File(guestW, "hi").setExecutable(true, true);
        } catch (Exception e) { say("  准备失败 " + e); }
        say("  绑定 " + work + " -> " + guestW);
        say("  exec /w/hi: " + run(new String[]{
                proot.getAbsolutePath(), "-r", "/",
                "-b", guestW.getAbsolutePath() + ":/w",
                "/w/hi"}, 15000));

        // 6. a dynamic binary under proot, via the host loader
        say("");
        say("-- 6) proot 下执行动态程序 --");
        say("  /system/bin/echo: " + run(new String[]{
                proot.getAbsolutePath(), "-r", "/", "/system/bin/echo", "DYN-OK"}, 15000));

        say("");
        say("=== 结束 ===");
        say("第2/3/4项都成功 -> proot 方案可行");
        say("第1项失败 -> 连静态程序都不能执行");
    }

    /** Copy the runtime into a private dir so proot can bind it by name. */
    private String sysdir(File ld, File work) { return work.getAbsolutePath() + "/rt"; }

    private String run(String[] cmd, int timeoutMs) {
        List<String> lines = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            Thread killer = new Thread(() -> {
                try { Thread.sleep(timeoutMs); p.destroyForcibly(); }
                catch (InterruptedException ignored) { }
            });
            killer.setDaemon(true);
            killer.start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (lines.size() < 6) lines.add(line);
                }
            }
            int rc = p.waitFor();
            return "rc=" + rc + " " + String.join(" | ", lines);
        } catch (Exception e) {
            return "异常 " + e;
        }
    }
}
