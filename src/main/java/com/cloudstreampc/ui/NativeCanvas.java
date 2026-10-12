package com.cloudstreampc.ui;

/**
 * A plain native panel whose window id another process (mpv) can draw into. Skiko's HardwareLayer
 * already reads that id through JAWT; it is internal to Kotlin code, hence this small Java class.
 */
public class NativeCanvas extends org.jetbrains.skiko.HardwareLayer {
    /** HWND on Windows, X11 window id on Linux; 0 when not available yet. */
    public long nativeId() {
        try {
            return getContentHandle();
        } catch (Throwable t) {
            return 0L;
        }
    }
}
