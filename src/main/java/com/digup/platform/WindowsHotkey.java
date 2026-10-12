package com.digup.platform;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Global system hotkey registration on Windows using Win32 RegisterHotKey via JNA.
 */
public final class WindowsHotkey {

    public interface HotkeyListener {
        void onHotkeyPressed();
    }

    private static final int HOTKEY_ID = 0x5D16; // "DIG"
    private final AtomicBoolean listening = new AtomicBoolean(false);
    private Thread thread;
    private int winThreadId = 0;
    private final HotkeyListener listener;

    public WindowsHotkey(HotkeyListener listener) {
        this.listener = listener;
    }

    public synchronized void register(int modifiers, int vkCode) {
        if (!Platform.isWindows()) return;
        stop();

        listening.set(true);
        thread = new Thread(() -> {
            winThreadId = Kernel32.INSTANCE.GetCurrentThreadId();
            boolean ok = User32.INSTANCE.RegisterHotKey(null, HOTKEY_ID, modifiers, vkCode);
            if (!ok) {
                // Try Win+Shift+Space (0x0008 | 0x0004)
                ok = User32.INSTANCE.RegisterHotKey(null, HOTKEY_ID, 0x0008 | 0x0004, 0x20);
            }
            if (!ok) {
                // Try Ctrl+Shift+Space (0x0002 | 0x0004)
                ok = User32.INSTANCE.RegisterHotKey(null, HOTKEY_ID, 0x0002 | 0x0004, 0x20);
            }
            if (!ok) {
                System.err.println("[Hotkey] Could not register global hotkey (all combinations in use).");
                return;
            }

            WinUser.MSG msg = new WinUser.MSG();
            while (listening.get()) {
                int res = User32.INSTANCE.GetMessage(msg, null, 0, 0);
                if (res <= 0) break;

                if (msg.message == WinUser.WM_HOTKEY && msg.wParam.intValue() == HOTKEY_ID) {
                    if (listener != null) {
                        listener.onHotkeyPressed();
                    }
                }
                User32.INSTANCE.TranslateMessage(msg);
                User32.INSTANCE.DispatchMessage(msg);
            }

            User32.INSTANCE.UnregisterHotKey(null, HOTKEY_ID);
        }, "DigUp-GlobalHotkey");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        if (!listening.get()) return;
        listening.set(false);
        if (winThreadId != 0) {
            User32.INSTANCE.PostThreadMessage(winThreadId, WinUser.WM_QUIT, new WinDef.WPARAM(0), new WinDef.LPARAM(0));
            winThreadId = 0;
        }
        thread = null;
    }
}
