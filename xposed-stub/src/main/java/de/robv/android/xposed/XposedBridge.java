package de.robv.android.xposed;

import java.util.Set;

/** Compile-only stub; the real class is provided by the Xposed framework at runtime. */
public final class XposedBridge {
    private XposedBridge() {
    }

    public static void log(String text) {
        throw new UnsupportedOperationException("stub");
    }

    public static Set<Object> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub");
    }
}
