package com.wpsbox.rdp;

/**
 * Decoder for RDP interleaved RLE bitmap data ("RLE8" in the spec).
 *
 * weston's rdp-backend sends bitmap updates either raw or RLE-compressed;
 * handling both is required because which one arrives depends on how well
 * the previous frame compressed.
 *
 * The stream is a sequence of runs, each starting with a header byte whose
 * high bit selects between:
 *   0x00-0x7f : one byte  of run length  (short form)
 *   0x80-0xff : two bytes of run length  (long form)
 * and whose low bits carry the segment type. The run length stored on the
 * wire is the actual length minus one.
 */
public final class RleDecoder {

    /** A decoded 32bpp ARGB image. */
    public static final class Frame {
        public final int width;
        public final int height;
        public final int[] pixels;

        Frame(int w, int h) {
            width = w;
            height = h;
            pixels = new int[w * h];
        }
    }

    private RleDecoder() {}

    /**
     * Decode {@code data} into {@code frame}, placing the result at
     * ({@code x}, {@code y}).
     *
     * @param data     compressed bytes
     * @param frame    destination (already sized to the desktop)
     * @param x        left edge of this update
     * @param y        top edge of this update
     * @param w        width of this update
     * @param h        height of this update
     * @return number of bytes consumed
     */
    public static int decode(byte[] data, Frame frame,
                             int x, int y, int w, int h) {
        int p = 0;
        int cx = 0, cy = 0;

        while (cy < h) {
            if (p >= data.length) break;

            int hdr = data[p++] & 0xFF;
            int runLength;
            int segType;

            if ((hdr & 0x80) != 0) {
                // long form: 2-byte length
                if (p + 1 >= data.length) break;
                runLength = ((hdr & 0x7F) << 8) | (data[p++] & 0xFF);
                segType = data[p++] & 0xFF;
            } else {
                runLength = hdr & 0x7F;
                segType = data[p++] & 0xFF;
            }
            runLength += 1;   // wire stores length-1

            switch (segType) {
                case Rdp.Rle.REGULAR_BG_RUN:
                case Rdp.Rle.MEGA_MEGA_BG_RUN: {
                    int color = readColor(data, p);
                    p += 4;
                    fill(frame, x + cx, y + cy, w, runLength, color);
                    advance(runLength, w, frame);
                    cx += runLength;
                    while (cx >= w) { cx -= w; cy++; }
                    break;
                }

                case Rdp.Rle.REGULAR_FG_RUN:
                case Rdp.Rle.MEGA_MEGA_FG_RUN:
                case Rdp.Rle.LITE_SET_FG_FG_RUN: {
                    int color = readColor(data, p);
                    p += 4;
                    fill(frame, x + cx, y + cy, w, runLength, color);
                    cx += runLength;
                    while (cx >= w) { cx -= w; cy++; }
                    break;
                }

                case Rdp.Rle.REGULAR_COLOR_RUN:
                case Rdp.Rle.MEGA_MEGA_COLOR_RUN: {
                    int color = readColor(data, p);
                    p += 4;
                    fill(frame, x + cx, y + cy, w, runLength, color);
                    cx += runLength;
                    while (cx >= w) { cx -= w; cy++; }
                    break;
                }

                case Rdp.Rle.REGULAR_FGBG_IMAGE:
                case Rdp.Rle.LITE_SET_FG_FGBG_IMAGE: {
                    // Alternate fg/bg pixels, fg first, in groups of 8 bits.
                    int color = readColor(data, p);
                    p += 4;
                    int remaining = runLength;
                    while (remaining > 0) {
                        if (p >= data.length) break;
                        int mask = data[p++] & 0xFF;
                        for (int bit = 0; bit < 8 && remaining > 0; bit++, remaining--) {
                            if ((mask & (0x80 >> bit)) != 0) {
                                set(frame, x + cx, y + cy, w, color);
                            }
                            cx++;
                            while (cx >= w) { cx -= w; cy++; }
                        }
                    }
                    break;
                }

                case Rdp.Rle.REGULAR_COLOR_IMAGE:
                case Rdp.Rle.MEGA_MEGA_COLOR_IMAGE:
                case Rdp.Rle.LITE_SET_FG_COLOR_IMAGE: {
                    // Raw 32bpp pixels.
                    for (int i = 0; i < runLength; i++) {
                        int color = readColor(data, p);
                        p += 4;
                        set(frame, x + cx, y + cy, w, color);
                        cx++;
                        while (cx >= w) { cx -= w; cy++; }
                    }
                    break;
                }

                case Rdp.Rle.SPECIAL_FGBG_1:
                    set(frame, x + cx, y + cy, w, 0xFFFFFFFF);
                    cx++;
                    while (cx >= w) { cx -= w; cy++; }
                    break;

                case Rdp.Rle.SPECIAL_FGBG_2:
                    set(frame, x + cx, y + cy, w, 0xFF000000);
                    cx++;
                    while (cx >= w) { cx -= w; cy++; }
                    break;

                case Rdp.Rle.WHITE:
                    fill(frame, x + cx, y + cy, w, runLength, 0xFFFFFFFF);
                    cx += runLength;
                    while (cx >= w) { cx -= w; cy++; }
                    break;

                case Rdp.Rle.BLACK:
                    fill(frame, x + cx, y + cy, w, runLength, 0xFF000000);
                    cx += runLength;
                    while (cx >= w) { cx -= w; cy++; }
                    break;

                default:
                    // Unknown segment: stop rather than emit garbage.
                    return p;
            }
        }
        return p;
    }

    /** RDP colours are BGRX on the wire; convert to ARGB for Bitmap. */
    private static int readColor(byte[] d, int p) {
        if (p + 3 >= d.length) return 0xFF000000;
        int b = d[p] & 0xFF;
        int g = d[p + 1] & 0xFF;
        int r = d[p + 2] & 0xFF;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static void set(Frame f, int x, int y, int rowWidth, int color) {
        if (x < 0 || y < 0 || x >= f.width || y >= f.height) return;
        // rowWidth is the update width; the frame is the full desktop.
        f.pixels[y * f.width + x] = color;
    }

    private static void fill(Frame f, int x, int y, int rowWidth,
                             int count, int color) {
        for (int i = 0; i < count; i++) {
            int px = x + i;
            if (px >= 0 && px < f.width && y >= 0 && y < f.height) {
                f.pixels[y * f.width + px] = color;
            }
        }
    }

    private static void advance(int n, int w, Frame f) {
        // placeholder for symmetry; positioning is done by the callers
    }
}
