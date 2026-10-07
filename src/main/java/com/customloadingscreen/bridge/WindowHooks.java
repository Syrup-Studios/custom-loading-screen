package com.customloadingscreen.bridge;

import java.lang.reflect.Method;

public final class WindowHooks {
    private static volatile Object provider;
    private static Method deferFullscreen;
    private static Method reveal;
    private static Method close;
    private static Method stage;

    private WindowHooks() {}

    public static void registerProvider(Object value) {
        try {
            Class<?> type = value.getClass();
            deferFullscreen = type.getMethod("deferGameWindowFullscreen", long.class);
            reveal = type.getMethod("revealGameWindowAfterFrame", long.class);
            close = type.getMethod("closeGameWindow", long.class);
            stage = type.getMethod("lifecycleStage", String.class);
            provider = value;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not register the early window provider", error);
        }
    }

    public static boolean deferFullscreen(long window) {
        Object target = provider;
        if (target == null) return false;
        try {
            return (boolean) deferFullscreen.invoke(target, window);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not query the early window provider", error);
        }
    }

    public static void revealAfterSwap(long window) {
        invoke(reveal, window);
    }

    public static void close(long window) {
        invoke(close, window);
    }

    public static void publishStage(String stageName) {
        invoke(stage, stageName);
    }

    private static void invoke(Method method, Object argument) {
        Object target = provider;
        if (target == null) return;
        try {
            method.invoke(target, argument);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not update the early window provider", error);
        }
    }
}
