package com.adlife.dicom.pro;

import java.util.concurrent.CopyOnWriteArrayList;

/** Tiny event bus so the print pipeline can raise tray balloons / alerts without a UI dependency. */
public final class Notifier {
    public interface Listener { void notify(String title, String message, boolean warning); }
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<Listener>();
    private Notifier() {}
    public static void add(Listener l) { LISTENERS.add(l); }
    public static void info(String title, String msg) { fire(title, msg, false); }
    public static void warn(String title, String msg) { fire(title, msg, true); }
    private static void fire(String t, String m, boolean w) {
        for (Listener l : LISTENERS) {
            try { l.notify(t, m, w); } catch (RuntimeException ignored) {}
        }
    }
}
