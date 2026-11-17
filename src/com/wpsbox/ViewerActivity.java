package com.wpsbox;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.wpsbox.cfg.Config;
import com.wpsbox.rdp.Rdp;
import com.wpsbox.rdp.RdpClient;
import com.wpsbox.rdp.RleDecoder;

/**
 * Renders the RDP desktop and forwards touch input.
 *
 * Deliberately avoids inner classes: d8 (R8 8.2.2) fails with an internal
 * NPE when converting them, so every listener here is either the activity
 * itself or a separate top-level class.
 *
 * Coordinates are mapped from the view into desktop pixels, accounting for
 * the letterboxing introduced when the aspect ratios differ -- without that,
 * taps near the edges land in the wrong place.
 */
public class ViewerActivity extends Activity
        implements RdpClient.FrameListener, SurfaceHolder.Callback,
                   TouchMapper.Target {

    private Config cfg;
    private RdpClient rdp;
    private SurfaceView surface;
    private TextView banner;
    private TouchMapper touch;

    private Bitmap bitmap;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private volatile boolean dirty = true;
    private volatile boolean renderLoopStarted = false;

    // view -> desktop transform
    private final Rect dst = new Rect();
    private float scale = 1f;
    private float offsetX = 0f, offsetY = 0f;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cfg = Config.load(getFilesDir());

        FrameLayout root = new FrameLayout(this);
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        banner = new TextView(this);
        banner.setText("正在连接 RDP…");
        banner.setTextColor(0xFFFFFFFF);
        banner.setBackgroundColor(0xCC000000);
        banner.setPadding(24, 12, 24, 12);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = 24;
        blp.topMargin = 24;
        root.addView(banner, blp);

        setContentView(root);

        surface.getHolder().addCallback(this);
        touch = new TouchMapper(this);
        surface.setOnTouchListener(touch);

        rdp = new RdpClient("127.0.0.1", cfg.rdpPort, cfg.width, cfg.height, this);
        rdp.connect();
    }

    // ---- SurfaceHolder.Callback ----

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        startRenderLoop();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) {
        dirty = true;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
    }

    // ---- rendering ----

    private void startRenderLoop() {
        if (renderLoopStarted) return;
        renderLoopStarted = true;
        Thread t = new Thread(() -> {
            while (!isFinishing()) {
                if (dirty) {
                    dirty = false;
                    drawNow();
                }
                try {
                    Thread.sleep(16);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "viewer-draw");
        t.setDaemon(true);
        t.start();
    }

    private void drawNow() {
        SurfaceHolder holder = surface.getHolder();
        Canvas c = null;
        try {
            c = holder.lockCanvas();
        } catch (Exception ignored) {
            return;
        }
        if (c == null) return;
        try {
            c.drawColor(0xFF000000);
            if (bitmap != null && !bitmap.isRecycled()) {
                computeDest(c.getWidth(), c.getHeight());
                c.drawBitmap(bitmap, null, dst, paint);
            }
        } finally {
            try {
                holder.unlockCanvasAndPost(c);
            } catch (Exception ignored) {
            }
        }
    }

    /** Fit the desktop inside the view, preserving aspect ratio. */
    private void computeDest(int vw, int vh) {
        if (vw <= 0 || vh <= 0) return;
        float sw = (float) vw / cfg.width;
        float sh = (float) vh / cfg.height;
        scale = Math.min(sw, sh);
        int w = Math.round(cfg.width * scale);
        int h = Math.round(cfg.height * scale);
        offsetX = (vw - w) / 2f;
        offsetY = (vh - h) / 2f;
        dst.set(Math.round(offsetX), Math.round(offsetY),
                Math.round(offsetX) + w, Math.round(offsetY) + h);
    }

    // ---- RdpClient.FrameListener ----

    @Override
    public void onFrame(RleDecoder.Frame f) {
        if (bitmap == null) {
            bitmap = Bitmap.createBitmap(f.width, f.height, Bitmap.Config.ARGB_8888);
        }
        bitmap.setPixels(f.pixels, 0, f.width, 0, 0, f.width, f.height);
        dirty = true;
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> {
            banner.setVisibility(View.VISIBLE);
            banner.setText("RDP 错误: " + message);
        });
    }

    @Override
    public void onReady(int w, int h) {
        runOnUiThread(() -> {
            banner.setText("");
            banner.setVisibility(View.GONE);
        });
    }

    // ---- TouchMapper.Target ----

    @Override
    public int mapX(float vx) {
        if (scale <= 0f) return 0;
        return clamp(Math.round((vx - offsetX) / scale), 0, cfg.width - 1);
    }

    @Override
    public int mapY(float vy) {
        if (scale <= 0f) return 0;
        return clamp(Math.round((vy - offsetY) / scale), 0, cfg.height - 1);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    @Override
    public void pointerMove(int x, int y) {
        try {
            rdp.sendMouseMove(x, y);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void click(int x, int y, int button) {
        int flag = button == 2 ? Rdp.PTRFLAGS_BUTTON3 : Rdp.PTRFLAGS_BUTTON1;
        try {
            rdp.sendMouseMove(x, y);
            rdp.sendMouseButton(x, y, true, flag);
            rdp.sendMouseButton(x, y, false, flag);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void zoom(float factor) {
        scale *= factor;
        scale = Math.max(0.2f, Math.min(4f, scale));
        dirty = true;
    }

    public void pan(float dx, float dy) {
        dirty = true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return touch.onTouch(surface, e) || super.onTouchEvent(e);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (rdp != null) rdp.disconnect();
    }
}
