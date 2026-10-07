package com.customloadingscreen.overlay;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.GlConst;
import org.lwjgl.opengl.GL32C;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.Util;
import net.neoforged.fml.earlydisplay.DisplayWindow;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.loading.NeoForgeLoadingOverlay;
import com.customloadingscreen.mixin.WindowAccessor;


/** Starts the visible loading-screen fade after NeoForge finishes initializing the menu. */
public final class SmoothLoadingOverlay extends NeoForgeLoadingOverlay {
    private static final long FADE_DURATION_NANOS = 250_000_000L;
    private static final Field FADE_OUT_START = fadeOutStartField();
    private boolean fadeReset;
    private final AtomicBoolean succeeded;
    private final Minecraft minecraft;
    private final DisplayWindow window;
    private final boolean fadelessLoaded;
    private final boolean independentWindow;
    private boolean revealRequested;
    private boolean fullscreenRestored;
    private boolean fadePaused;
    private long fadeStartedNanos = -1L;

    public static void clearFramebufferTextureBinding() {
        RenderSystem.setShaderTexture(0, 0);
        GlStateManager._activeTexture(GlConst.GL_TEXTURE0);
        GlStateManager._bindTexture(0);
        GL32C.glFinish();
    }

    private SmoothLoadingOverlay(Minecraft minecraft, ReloadInstance reload,
                                 Consumer<Optional<Throwable>> onFinish, DisplayWindow window, AtomicBoolean succeeded) {
        super(minecraft, reload, result -> {
            onFinish.accept(result);
            if (result.isEmpty()) succeeded.set(true);
        }, window);
        this.succeeded = succeeded;
        this.minecraft = minecraft;
        this.window = window;
        this.fadelessLoaded = ModList.get().isLoaded("fadeless");
        this.independentWindow = invokeBoolean(window, "usesIndependentWindow");
    }

    public static Supplier<LoadingOverlay> newInstance(Supplier<Minecraft> minecraft,
            Supplier<ReloadInstance> reload, Consumer<Optional<Throwable>> onFinish, DisplayWindow window) {
        return () -> new SmoothLoadingOverlay(minecraft.get(), reload.get(), onFinish, window, new AtomicBoolean());
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean wasActiveOverlay = minecraft.getOverlay() == this;
        boolean wasWaiting = !fadeReset && fadeOutStart() < 0;
        boolean snapshotFrame = false;
        if (fadePaused && invokeBoolean(window, "isTransitionReady")) {
            try {
                FADE_OUT_START.setLong(this, Util.getMillis() - 1000L);
                fadePaused = false;
                fadeStartedNanos = System.nanoTime();
                snapshotFrame = true;
            } catch (IllegalAccessException error) {
                throw new IllegalStateException("Could not start the loading-screen fade", error);
            }
        }
        if (fadeReset && !fadePaused && fadeStartedNanos >= 0) {
            long elapsedNanos = System.nanoTime() - fadeStartedNanos;
            long virtualElapsedMillis = 1000L + elapsedNanos * 1000L / FADE_DURATION_NANOS;
            try {
                FADE_OUT_START.setLong(this, Util.getMillis() - virtualElapsedMillis);
            } catch (IllegalAccessException error) {
                throw new IllegalStateException("Could not advance loading-screen fade", error);
            }
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        if (wasWaiting && fadeOutStart() >= 0) {
            try {
                fadeReset = true;
                fadePaused = independentWindow;
                fadeStartedNanos = fadePaused ? -1L : System.nanoTime();
                FADE_OUT_START.setLong(this, Util.getMillis() + (fadePaused ? 60_000L : -1000L));
                invokeWindow(succeeded.get() ? "loadingFinished" : "loadingFailed");
            } catch (IllegalAccessException error) {
                throw new IllegalStateException("Could not reset loading-screen fade", error);
            }
        }
        long fadeElapsed = fadeReset && !fadePaused && fadeStartedNanos >= 0
                ? 1000L + (System.nanoTime() - fadeStartedNanos) * 1000L / FADE_DURATION_NANOS
                : Util.getMillis() - fadeOutStart();
        if (snapshotFrame && !revealRequested) {
            invokeWindow("requestGameWindowReveal");
            revealRequested = true;
        }
        if (independentWindow && !fullscreenRestored && fadeElapsed >= 2000L && minecraft.getWindow().isFullscreen()) {
            ((WindowAccessor) (Object) minecraft.getWindow()).customloadingscreen$setMode();
            minecraft.resizeDisplay();
            fullscreenRestored = true;
        }
        if (fadelessLoaded && wasActiveOverlay && minecraft.getOverlay() == null && fadeReset
                && (fadePaused || fadeElapsed >= 0 && fadeElapsed < 2000L)) {
            // Fadeless clears the overlay at render start, so restore it only during NeoForge's fade.
            minecraft.setOverlay(this);
        }
    }

    private void invokeWindow(String name) {
        try {
            window.getClass().getMethod(name).invoke(window);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not update the custom loading window", error);
        }
    }

    private static boolean invokeBoolean(DisplayWindow window, String name) {
        try {
            return (boolean) window.getClass().getMethod(name).invoke(window);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not inspect the custom loading window", error);
        }
    }

    private long fadeOutStart() {
        try {
            return FADE_OUT_START.getLong(this);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Could not read loading-screen fade", error);
        }
    }

    private static Field fadeOutStartField() {
        try {
            Field field = NeoForgeLoadingOverlay.class.getDeclaredField("fadeOutStart");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
