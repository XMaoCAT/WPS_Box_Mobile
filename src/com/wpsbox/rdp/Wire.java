package com.wpsbox.rdp;

import java.io.DataInputStream;
import java.io.IOException;

/**
 * Minimal RDP wire helpers.
 *
 * Scope note: this is a purpose-built client for talking to weston's
 * rdp-backend on loopback, not a general RDP implementation. We negotiate
 * the narrowest standard profile ("RDP" security with TLS, 32bpp bitmap
 * updates) rather than trying to cover the whole protocol surface.
 *
 * Everything multi-byte on the RDP wire is little-endian.
 */
public final class Wire {

    private Wire() {}

    // ---- little-endian writers ------------------------------------------

    public static void u8(java.io.OutputStream o, int v) throws IOException {
        o.write(v & 0xFF);
    }

    public static void u16le(java.io.OutputStream o, int v) throws IOException {
        o.write(v & 0xFF);
        o.write((v >>> 8) & 0xFF);
    }

    public static void u32le(java.io.OutputStream o, long v) throws IOException {
        o.write((int) (v & 0xFF));
        o.write((int) ((v >>> 8) & 0xFF));
        o.write((int) ((v >>> 16) & 0xFF));
        o.write((int) ((v >>> 24) & 0xFF));
    }

    public static void bytes(java.io.OutputStream o, byte[] b) throws IOException {
        o.write(b);
    }

    // ---- little-endian readers ------------------------------------------

    public static int u8(DataInputStream in) throws IOException {
        return in.readUnsignedByte();
    }

    public static int u16le(DataInputStream in) throws IOException {
        int a = in.readUnsignedByte();
        int b = in.readUnsignedByte();
        return a | (b << 8);
    }

    public static long u32le(DataInputStream in) throws IOException {
        long a = in.readUnsignedByte();
        long b = in.readUnsignedByte();
        long c = in.readUnsignedByte();
        long d = in.readUnsignedByte();
        return a | (b << 8) | (c << 16) | (d << 24);
    }

    public static byte[] fixed(DataInputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        in.readFully(b);
        return b;
    }

    // ---- big-endian helpers (TLS handshake records) ----------------------

    public static int u16be(DataInputStream in) throws IOException {
        int a = in.readUnsignedByte();
        int b = in.readUnsignedByte();
        return (a << 8) | b;
    }

    public static void u16be(java.io.OutputStream o, int v) throws IOException {
        o.write((v >>> 8) & 0xFF);
        o.write(v & 0xFF);
    }

    public static void u24be(java.io.OutputStream o, int v) throws IOException {
        o.write((v >>> 16) & 0xFF);
        o.write((v >>> 8) & 0xFF);
        o.write(v & 0xFF);
    }

    // ---- misc -----------------------------------------------------------

    /** RDP negotiates sizes in pixels; keep the descriptor compact. */
    public static byte[] ascii(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    public static byte[] utf16le(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
    }

    /** Copy a range, used heavily while reassembling bitmap tiles. */
    public static void copy(byte[] src, int srcPos, byte[] dst, int dstPos, int len) {
        System.arraycopy(src, srcPos, dst, dstPos, len);
    }
}
