package com.moncloudi.ui;

import java.awt.Graphics;

/**
 * A plain native panel whose window id another process (mpv) can draw into. Skiko's HardwareLayer
 * already reads that id through JAWT; it is internal to Kotlin code, hence this small Java class.
 * Repaints are suppressed so AWT never paints black over mpv's native video surface.
 */
public class NativeCanvas extends org.jetbrains.skiko.HardwareLayer {
    public NativeCanvas() {
        setIgnoreRepaint(true);
    }

    @Override
    public void paint(Graphics g) {
        // Rendered directly by mpv via --wid.
    }

    @Override
    public void update(Graphics g) {
        // Do not erase background over mpv's swapchain.
    }

    /** HWND on Windows, X11 window id on Linux; 0 when not available yet. */
    public long nativeId() {
        try {
            return getContentHandle();
        } catch (Throwable t) {
            return 0L;
        }
    }
}
