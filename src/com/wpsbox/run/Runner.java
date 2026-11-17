package com.wpsbox.run;

import android.content.Context;
import android.util.Log;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Lays out the payload and builds the command that starts WPS.
 *
 * The command's shape is forced by two facts about Android.
 *
 * execve is permitted only from the native library directory, which is flat,
 * read-only, and accepts only lib*.so names. That is where proot, the loader
 * and wps have to live. There is no room there for two hundred shared
 * libraries or a resource tree.
 *
 * WPS finds its libraries and resources by taking the directory containing
 * /proc/self/exe and appending names to it. Working directory is not
 * consulted, and LD_LIBRARY_PATH covers only the linker's own search, not the
 * dlopen calls that follow.
 *
 * proot reconciles the two. The bulk stays in the private directory, which
 * allows reading and dlopen but not execve, and proot binds it onto a path
 * beneath the native library directory that does not exist on disk. WPS then
 * sees one directory holding its binary, its libraries and its resources, as
 * it does on a desktop, while Android still executes only from the one place
 * it allows.
 */
public final class Runner {

    private static final String TAG = "WpsBox";

    /** Where the payload tree appears inside the proot session. */
    private static final String GUEST = "/w";
    private static final String GUEST_SYSLIB = "/syslib";

    private final Context ctx;

    public Runner(Context ctx) { this.ctx = ctx; }

    public File payloadDir() { return new File(ctx.getFilesDir(), "payload"); }
    public File officeDir()  { return new File(payloadDir(), "office6"); }
    public File syslibDir()  { return new File(payloadDir(), "syslib"); }
    public File displayDir() { return new File(payloadDir(), "display"); }
    public File tmpDir()     { return new File(ctx.getFilesDir(), "tmp"); }

    public File proot()  { return new File(libDir(), "libproot_exec.so"); }
    public File loader() { return new File(libDir(), "libld_exec.so"); }
    public File wps()    { return new File(libDir(), "libwps_exec.so"); }

    private File libDir() { return new File(ctx.getApplicationInfo().nativeLibraryDir); }

    /** True once the payload has been unpacked and looks complete. */
    public boolean payloadReady() {
        return new File(officeDir(), "wps").exists()
                && new File(officeDir(), "skins").isDirectory()
                && new File(syslibDir(), "libc.so.6").exists()
                && proot().exists() && loader().exists() && wps().exists();
    }

    /**
     * Unpack the payload archive shipped as an asset.
     *
     * Extraction goes into a temporary directory and is moved into place only
     * when complete, so a half-written tree is never mistaken for a usable
     * one. Symlinks stored in the archive are recreated; they matter because
     * DT_NEEDED refers to names like libQt5CoreKso.so.5 while the file on disk
     * is libQt5CoreKso.so.5.12.10.
     */
    public void unpack(Progress p) throws IOException {
        File target = payloadDir();
        File tmp = new File(ctx.getFilesDir(), "payload.part");
        deleteTree(tmp);
        if (!tmp.mkdirs()) throw new IOException("cannot create " + tmp);

        long totalDone = 0;
        try (InputStream raw = ctx.getAssets().open("payload.tar.gz");
             GZIPInputStream gz = new GZIPInputStream(raw, 1 << 16)) {
            DataInputStream in = new DataInputStream(gz);
            byte[] header = new byte[512];
            while (true) {
                readFully(in, header);
                if (isZero(header)) {
                    readFully(in, header);
                    break;
                }
                String name = str(header, 0, 100);
                String prefix = str(header, 345, 155);
                if (prefix.length() > 0) name = prefix + "/" + name;
                long size = octal(header, 124, 12);
                char type = (char) (header[156] & 0xff);

                if (name.startsWith("./")) name = name.substring(2);
                if (name.length() == 0) { skip(in, size); continue; }

                File out = new File(tmp, name);
                int slash = name.lastIndexOf('/');
                if (slash > 0) new File(tmp, name.substring(0, slash)).mkdirs();

                if (type == '5') {
                    out.mkdirs();
                } else if (type == '2') {
                    String link = str(header, 157, 100);
                    out.getParentFile().mkdirs();
                    try {
                        java.nio.file.Files.deleteIfExists(out.toPath());
                        java.nio.file.Files.createSymbolicLink(
                                out.toPath(), java.nio.file.Paths.get(link));
                    } catch (Exception ignored) { }
                    skip(in, size);
                } else if (type == '0' || type == 0) {
                    out.getParentFile().mkdirs();
                    writeFile(in, out, size);
                } else {
                    skip(in, size);
                }

                long pad = (512 - (size % 512)) % 512;
                if (pad > 0) skip(in, pad);

                totalDone += size;
                if (p != null) p.onProgress(totalDone);
            }
        }

        deleteTree(target);
        if (!tmp.renameTo(target)) throw new IOException("cannot move payload into place");
        Log.i(TAG, "payload unpacked to " + target);
    }

    private static void readFully(DataInputStream in, byte[] b) throws IOException {
        in.readFully(b);
    }

    private static void writeFile(DataInputStream in, File out, long size) throws IOException {
        try (OutputStream o = new FileOutputStream(out)) {
            byte[] b = new byte[1 << 16];
            long left = size;
            while (left > 0) {
                int n = (int) Math.min(b.length, left);
                in.readFully(b, 0, n);
                o.write(b, 0, n);
                left -= n;
            }
        }
        out.setReadable(true, true);
        out.setExecutable(true, true);
    }

    private static void skip(DataInputStream in, long n) throws IOException {
        long left = n;
        byte[] b = new byte[8192];
        while (left > 0) {
            int k = (int) Math.min(b.length, left);
            in.readFully(b, 0, k);
            left -= k;
        }
    }

    private static boolean isZero(byte[] a) {
        for (byte b : a) if (b != 0) return false;
        return true;
    }

    private static String str(byte[] a, int off, int len) {
        int end = off;
        while (end < off + len && a[end] != 0) end++;
        try { return new String(a, off, end - off, "UTF-8"); }
        catch (Exception e) { return ""; }
    }

    private static long octal(byte[] a, int off, int len) {
        long v = 0; boolean any = false;
        for (int i = off; i < off + len; i++) {
            byte c = a[i];
            if (c == 0 || c == ' ') continue;
            if (c < '0' || c > '7') break;
            v = v * 8 + (c - '0'); any = true;
        }
        return any ? v : 0;
    }

    public static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    public interface Progress { void onProgress(long done); }
}
