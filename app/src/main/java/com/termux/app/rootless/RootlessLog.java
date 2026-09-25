package com.termux.app.rootless;

import android.util.Log;

/**
 * Logging shim so the ported rootless classes do not depend on strykerapp's Core utility class.
 * Same idea, same tag, no coupling.
 */
public final class RootlessLog {

    private static final String TAG = "Rootless";

    private RootlessLog() {}

    public static void i(String message) { Log.i(TAG, message); }

    public static void w(String message) { Log.w(TAG, message); }

    public static void w(String message, Throwable t) { Log.w(TAG, message, t); }
}
