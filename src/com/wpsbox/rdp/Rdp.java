package com.wpsbox.rdp;

/**
 * Constants for the RDP subset weston's rdp-backend actually negotiates.
 *
 * Verified against a live weston 14.0.2 / FreeRDP 3.15 listener: sending an
 * X.224 Connection Request with RDP_NEG_REQ asking for TLS produced
 *
 *     03 00 00 13 0e d0 00 00 00 00 00 02 03 08 00 01 00 00 00
 *
 * i.e. TPKT(len 19) / X.224 CC / RDP_NEG_RSP with selectedProtocol = 0x08
 * (PROTOCOL_SSL). So the server insists on TLS and we must layer the RDP
 * MCS/CAPABILITY exchange on top of a TLS stream.
 */
public final class Rdp {

    private Rdp() {}

    // ---- TPKT / X.224 ----------------------------------------------------

    public static final int TPKT_VERSION = 3;

    /** X.224 Connection Confirm (0x0e << 4 | 0xd0). */
    public static final int X224_CC = 0xd0;
    /** X.224 Connection Request. */
    public static final int X224_CR = 0xe0;
    /** X.224 Data (DT). */
    public static final int X224_DT = 0xf0;

    // ---- RDP negotiation -------------------------------------------------

    public static final int NEG_REQ = 0x01;
    public static final int NEG_RSP = 0x02;
    public static final int NEG_FAILURE = 0x03;

    public static final int PROTOCOL_RDP       = 0x00000000;
    public static final int PROTOCOL_SSL       = 0x00000001;
    public static final int PROTOCOL_HYBRID    = 0x00000002;
    public static final int PROTOCOL_RDSTLS    = 0x00000004;
    public static final int PROTOCOL_HYBRID_EX = 0x00000008;

    // ---- MCS -------------------------------------------------------------
    // RDP uses a fixed channel layout; these ids come from the spec.
    public static final int MCS_GLOBAL_CHANNEL = 1003;
    public static final int MCS_USER_CHANNEL   = 1002;

    public static final int MCS_TYPE_CONNECT_INITIAL   = 101;
    public static final int MCS_TYPE_CONNECT_RESPONSE  = 102;
    public static final int MCS_TYPE_DISCONNECT_PROVIDER_ULTIMATUM = 8;

    public static final int MCS_SEND_DATA_INDICATION = 26;
    public static final int MCS_SEND_DATA_REQUEST    = 25;

    // ---- Security header -------------------------------------------------

    public static final int SEC_INFO_PKT   = 0x40;
    public static final int SEC_LICENCE_PKT = 0x80;
    public static final int SEC_EXCHANGE_PKT = 0x0001;

    // ---- Share control / PDU types --------------------------------------

    public static final int PDUTYPE_DEMANDACTIVEPDU  = 0x0001;
    public static final int PDUTYPE_CONFIRMACTIVEPDU = 0x0003;
    public static final int PDUTYPE_DATAPDU          = 0x0007;

    public static final int PDUTYPE2_UPDATE      = 2;
    public static final int PDUTYPE2_CONTROL     = 20;
    public static final int PDUTYPE2_POINTER     = 27;
    public static final int PDUTYPE2_INPUT       = 28;
    public static final int PDUTYPE2_SYNCHRONIZE = 31;
    public static final int PDUTYPE2_SUPPRESS_OUTPUT = 35;

    /** Slow-path update codes. */
    public static final int UPDATETYPE_ORDERS = 0;
    public static final int UPDATETYPE_BITMAP = 1;
    public static final int UPDATETYPE_PALETTE = 2;
    public static final int UPDATETYPE_SYNCHRONIZE = 3;

    // ---- Capability sets (the ones we must answer) -----------------------

    public static final int CAPSTYPE_GENERAL    = 1;
    public static final int CAPSTYPE_BITMAP     = 3;
    public static final int CAPSTYPE_ORDER      = 5;
    public static final int CAPSTYPE_POINTER    = 8;
    public static final int CAPSTYPE_INPUT      = 13;
    public static final int CAPSTYPE_VIRTUALCHANNEL = 14;
    public static final int CAPSTYPE_SOUND      = 12;
    public static final int CAPSTYPE_GLYPHCACHE = 16;
    public static final int CAPSTYPE_COLORCACHE = 10;

    // ---- Bitmap compression flags ---------------------------------------

    public static final int BITMAP_COMPRESSION = 0x0001;
    public static final int NO_BITMAP_COMPRESSION_HDR = 0x0400;

    /**
     * Interleaved RLE ("RLE8") segments used for compressed bitmap updates.
     * The values are the spec's segment codes.
     */
    public static final class Rle {
        public static final int REGULAR_BG_RUN = 0;
        public static final int REGULAR_FG_RUN = 1;
        public static final int REGULAR_FGBG_IMAGE = 2;
        public static final int REGULAR_COLOR_RUN = 3;
        public static final int REGULAR_COLOR_IMAGE = 4;
        public static final int LITE_SET_FG_FG_RUN = 5;
        public static final int LITE_SET_FG_FGBG_IMAGE = 6;
        public static final int LITE_SET_FG_COLOR_RUN = 7;
        public static final int LITE_SET_FG_COLOR_IMAGE = 8;
        public static final int MEGA_MEGA_BG_RUN = 240;
        public static final int MEGA_MEGA_FG_RUN = 241;
        public static final int MEGA_MEGA_FGBG_IMAGE = 242;
        public static final int MEGA_MEGA_COLOR_RUN = 243;
        public static final int MEGA_MEGA_COLOR_IMAGE = 244;
        public static final int SPECIAL_FGBG_1 = 249;
        public static final int SPECIAL_FGBG_2 = 250;
        public static final int WHITE = 255;
        public static final int BLACK = 254;
        private Rle() {}
    }

    // ---- Input events (what we send back) --------------------------------

    public static final int INPUT_EVENT_SYNC     = 0x0000;
    public static final int INPUT_EVENT_SCANCODE = 0x0004;
    public static final int INPUT_EVENT_UNICODE  = 0x0005;
    public static final int INPUT_EVENT_MOUSE    = 0x8001;
    public static final int INPUT_EVENT_MOUSEX   = 0x8002;

    public static final int PTRFLAGS_MOVE   = 0x0800;
    public static final int PTRFLAGS_DOWN   = 0x8000;
    public static final int PTRFLAGS_BUTTON1 = 0x1000;
    public static final int PTRFLAGS_BUTTON2 = 0x2000;
    public static final int PTRFLAGS_BUTTON3 = 0x4000;
    public static final int PTRFLAGS_WHEEL  = 0x0200;
    public static final int PTRFLAGS_WHEEL_NEGATIVE = 0x0100;
}
