package com.wpsbox;

import android.view.MotionEvent;
import android.view.View;

/**
 * Touch-to-mouse translation for the desktop viewer.
 *
 * Implements OnTouchListener directly instead of extending framework gesture
 * adapters: d8 (R8 8.2.2) aborts with an internal NPE on subclasses of
 * GestureDetector.SimpleOnGestureListener, so the gesture logic is written
 * out by hand. That is also cheaper -- we only need tap, drag and pinch.
 *
 * Tap        -> left click at the touched point
 * Long press -> right click
 * Drag       -> pointer move
 * Pinch      -> zoom the view (handled by the caller)
 */
public final class TouchMapper implements View.OnTouchListener {

    /** Callbacks into the viewer. Coordinates are already desktop pixels. */
    public interface Target {
        void pointerMove(int x, int y);
        void click(int x, int y, int button);
        void zoom(float factor);
        /** Convert a view coordinate to a desktop coordinate. */
        int mapX(float viewX);
        int mapY(float viewY);
    }

    private static final long LONG_PRESS_MS = 550L;
    private static final float TOUCH_SLOP = 18f;

    private final Target target;

    private long downAt;
    private float downX, downY;
    private boolean longFired;
    private boolean moved;

    // pinch state
    private boolean pinching;
    private float lastSpan;

    public TouchMapper(Target target) {
        this.target = target;
    }

    @Override
    public boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downAt = System.currentTimeMillis();
                downX = e.getX();
                downY = e.getY();
                longFired = false;
                moved = false;
                pinching = false;
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() >= 2) {
                    pinching = true;
                    lastSpan = span(e);
                }
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (pinching && e.getPointerCount() >= 2) {
                    float s = span(e);
                    if (lastSpan > 0f) {
                        target.zoom(s / lastSpan);
                    }
                    lastSpan = s;
                    return true;
                }
                float dx = Math.abs(e.getX() - downX);
                float dy = Math.abs(e.getY() - downY);
                if (dx > TOUCH_SLOP || dy > TOUCH_SLOP) moved = true;
                if (moved) {
                    target.pointerMove(target.mapX(e.getX()), target.mapY(e.getY()));
                }
                // Long press with a held finger becomes a right click.
                if (!longFired && !moved
                        && System.currentTimeMillis() - downAt > LONG_PRESS_MS) {
                    longFired = true;
                    target.click(target.mapX(e.getX()), target.mapY(e.getY()), 2);
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                if (pinching) {
                    pinching = false;
                    return true;
                }
                if (!moved && !longFired) {
                    target.click(target.mapX(e.getX()), target.mapY(e.getY()), 1);
                }
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP:
                if (e.getPointerCount() <= 2) {
                    pinching = false;
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                pinching = false;
                moved = false;
                longFired = false;
                return true;

            default:
                return false;
        }
    }

    private static float span(MotionEvent e) {
        if (e.getPointerCount() < 2) return 0f;
        float dx = e.getX(0) - e.getX(1);
        float dy = e.getY(0) - e.getY(1);
        return (float) Math.hypot(dx, dy);
    }
}
