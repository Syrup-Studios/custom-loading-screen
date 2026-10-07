package com.customloadingscreen.startup;

import com.customloadingscreen.scene.Scene;
import net.neoforged.fml.earlydisplay.DisplayWindow;
import net.neoforged.fml.earlydisplay.ColourScheme;
import net.neoforged.fml.earlydisplay.RenderElement;
import net.neoforged.fml.earlydisplay.SimpleFont;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.FileAlreadyExistsException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL32C.*;

public final class StartupWindowProvider extends DisplayWindow {
    public static final String PROVIDER_NAME = "customloadingscreen";
    private static final Logger LOGGER = LoggerFactory.getLogger(StartupWindowProvider.class);
    private static final long MAX_SCENE_BYTES = 1_048_576;
    private static final Field GLOBAL_ALPHA = findGlobalAlpha();
    private static final Method RENDER_THREAD_FUNC = findRenderThreadFunc();

    private final StartupStageAdapter stages = new StartupStageAdapter();
    private final AtomicReference<RenderElement> customElement = new AtomicReference<>();
    private final AtomicBoolean renderFailed = new AtomicBoolean();
    private final AtomicBoolean elementBufferClosed = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private volatile StartupRenderer renderer;
    private volatile boolean installed;
    private long gameWindow;
    private final int[] framebufferWidthValue = new int[1];
    private final int[] framebufferHeightValue = new int[1];
    private volatile boolean independentWindow;
    private volatile boolean frozen;
    private volatile boolean transitionReady;
    private volatile boolean freezeFailed;
    private volatile boolean gameWindowShown;
    private volatile boolean transitionStarted;
    private volatile boolean fullscreenAllowed;
    private volatile boolean revealRequested;
    private int placeholderTexture;
    private Method clearTextureBinding;

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public Runnable start(String minecraftVersion, String neoForgeVersion) {
        try {
            Field framebufferScale = field("fbScale");
            framebufferScale.setInt(this, 1);
        } catch (ReflectiveOperationException error) {
            installed = true;
            LOGGER.warn("Could not set the early window scale; the default loading screen will remain active", error);
        }
        try {
            field("colourScheme").set(this, ColourScheme.BLACK);
        } catch (ReflectiveOperationException error) {
            LOGGER.warn("Could not set a black background for the early window", error);
        }
        try {
            Semaphore renderLock = (Semaphore) field("renderLock").get(this);
            renderLock.acquire();
            try {
                Runnable tick = super.start(minecraftVersion, neoForgeVersion);
                ((Future<?>) field("initializationFuture").get(this)).get(30, TimeUnit.SECONDS);
                if (!installed) installRenderer();
                if (renderer != null && !renderFailed.get()) scheduleCustomRenderTick();
                return tick;
            } finally {
                renderLock.release();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while initializing the early display", error);
        } catch (ReflectiveOperationException | ExecutionException | TimeoutException error) {
            throw new IllegalStateException("Could not initialize the early display", error);
        }
    }

    private void scheduleCustomRenderTick() {
        if (RENDER_THREAD_FUNC == null) {
            LOGGER.warn("Could not increase the custom loading screen render rate");
            return;
        }
        ScheduledFuture<?> newTick = null;
        try {
            Field windowTickField = field("windowTick");
            ScheduledFuture<?> oldTick = (ScheduledFuture<?>) windowTickField.get(this);
            ScheduledExecutorService scheduler = (ScheduledExecutorService) field("renderScheduler").get(this);
            Runnable renderTick = () -> {
                try {
                    RENDER_THREAD_FUNC.invoke(this);
                } catch (ReflectiveOperationException error) {
                    LOGGER.warn("Could not render the custom loading screen at 60 FPS", error);
                }
            };
            newTick = scheduler.scheduleAtFixedRate(renderTick, 0, TimeUnit.SECONDS.toNanos(1) / 60, TimeUnit.NANOSECONDS);
            windowTickField.set(this, newTick);
            oldTick.cancel(false);
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (newTick != null) newTick.cancel(false);
            LOGGER.warn("Could not increase the custom loading screen render rate", error);
        }
    }

    private static Method findRenderThreadFunc() {
        try {
            Method method = DisplayWindow.class.getDeclaredMethod("renderThreadFunc");
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    @Override
    public Runnable initialize(String[] arguments) {
        LOGGER.info("Initializing custom early loading window");
        return super.initialize(arguments);
    }

    @Override
    public long setupMinecraftWindow(IntSupplier width, IntSupplier height, Supplier<String> title, LongSupplier monitorSupplier) {
        long earlyWindow;
        try {
            earlyWindow = field("window").getLong(this);
            if (RENDER_THREAD_FUNC == null) return super.setupMinecraftWindow(width, height, title, monitorSupplier);
            String[] version = getGLVersion().split("\\.");
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_API);
            glfwWindowHint(GLFW_CONTEXT_CREATION_API, GLFW_NATIVE_CONTEXT_API);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, Integer.parseInt(version[0]));
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, Integer.parseInt(version[1]));
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
            gameWindow = glfwCreateWindow(width.getAsInt(), height.getAsInt(), title.get(), 0L, earlyWindow);
            if (gameWindow == 0L) return super.setupMinecraftWindow(width, height, title, monitorSupplier);
            placeholderTexture = createPlaceholderTexture();
            if (placeholderTexture == 0) {
                glfwDestroyWindow(gameWindow);
                gameWindow = 0L;
                return super.setupMinecraftWindow(width, height, title, monitorSupplier);
            }
            independentWindow = true;
            LOGGER.info("Created independent game window; the startup display will keep rendering during initialization");
            return gameWindow;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while creating the independent game window; using the normal handoff", error);
            if (gameWindow != 0L) glfwDestroyWindow(gameWindow);
            gameWindow = 0L;
            return super.setupMinecraftWindow(width, height, title, monitorSupplier);
        } catch (ReflectiveOperationException | ExecutionException | TimeoutException | RuntimeException error) {
            LOGGER.warn("Could not create the independent game window; using the normal window handoff", error);
            if (gameWindow != 0L) glfwDestroyWindow(gameWindow);
            gameWindow = 0L;
            return super.setupMinecraftWindow(width, height, title, monitorSupplier);
        }
    }

    @Override
    public void periodicTick() {
        super.periodicTick();
        if (independentWindow && gameWindow != 0L && glfwWindowShouldClose(fieldWindow())) {
            glfwSetWindowShouldClose(gameWindow, true);
        }
    }

    @Override
    public boolean positionWindow(Optional<Object> monitor, IntConsumer width, IntConsumer height,
                                  IntConsumer x, IntConsumer y) {
        if (!independentWindow) return super.positionWindow(monitor, width, height, x, y);
        try {
            int windowWidth = field("winWidth").getInt(this);
            int windowHeight = field("winHeight").getInt(this);
            int windowX = field("winX").getInt(this);
            int windowY = field("winY").getInt(this);
            width.accept(windowWidth);
            height.accept(windowHeight);
            x.accept(windowX);
            y.accept(windowY);
            glfwSetWindowSize(gameWindow, windowWidth, windowHeight);
            glfwSetWindowPos(gameWindow, windowX, windowY);
            return true;
        } catch (ReflectiveOperationException error) {
            LOGGER.warn("Could not align the game window with the startup window", error);
            return false;
        }
    }

    @Override
    public void updateFramebufferSize(IntConsumer width, IntConsumer height) {
        if (!independentWindow) {
            super.updateFramebufferSize(width, height);
            return;
        }
        glfwGetFramebufferSize(gameWindow, framebufferWidthValue, framebufferHeightValue);
        width.accept(Math.max(1, framebufferWidthValue[0]));
        height.accept(Math.max(1, framebufferHeightValue[0]));
    }

    private long fieldWindow() {
        try {
            return field("window").getLong(this);
        } catch (ReflectiveOperationException error) {
            return 0L;
        }
    }

    private int createPlaceholderTexture() throws ReflectiveOperationException, InterruptedException, ExecutionException, TimeoutException {
        ScheduledExecutorService scheduler = (ScheduledExecutorService) field("renderScheduler").get(this);
        Future<Integer> texture = scheduler.submit(() -> {
            glfwMakeContextCurrent(fieldWindow());
            try {
                int id = glGenTextures();
                glBindTexture(GL_TEXTURE_2D, id);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                ByteBuffer pixel = MemoryUtil.memAlloc(4);
                try {
                    pixel.put(0, (byte) 0);
                    pixel.put(1, (byte) 0);
                    pixel.put(2, (byte) 0);
                    pixel.put(3, (byte) 0xFF);
                    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                } finally {
                    MemoryUtil.memFree(pixel);
                }
                glBindTexture(GL_TEXTURE_2D, 0);
                glFinish();
                return id;
            } finally {
                glfwMakeContextCurrent(0L);
            }
        });
        return texture.get(5, TimeUnit.SECONDS);
    }

    @Override
    public void updateModuleReads(ModuleLayer layer) {
        Module neoForge = layer.findModule("neoforge").orElseThrow();
        getClass().getModule().addReads(neoForge);
        Module mod = layer.findModule("customloadingscreen").orElseThrow();
        getClass().getModule().addReads(mod);
        Class<?> overlay = Class.forName(mod, "com.customloadingscreen.overlay.SmoothLoadingOverlay");
        Class<?> hooks = Class.forName(mod, "com.customloadingscreen.bridge.WindowHooks");
        if (overlay == null) throw new IllegalStateException("Could not load the custom loading overlay");
        if (hooks == null) throw new IllegalStateException("Could not load window hooks");
        try {
            Method method = overlay.getMethod("newInstance", Supplier.class, Supplier.class, Consumer.class, DisplayWindow.class);
            field("loadingOverlay").set(this, method);
            clearTextureBinding = overlay.getMethod("clearFramebufferTextureBinding");
            hooks.getMethod("registerProvider", Object.class).invoke(null, this);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not initialize the NeoForge loading overlay", error);
        }
    }

    @Override
    public void addMojangTexture(int textureId) {
    }

    public void loadingFinished() {
        transitionStarted = true;
        stages.complete();
        freezeStartupRenderer();
        LOGGER.info("Startup loading completed");
    }

    public void loadingFailed() {
        transitionStarted = true;
        freezeStartupRenderer();
    }

    private void freezeStartupRenderer() {
        if (!independentWindow || transitionReady) return;
        try {
            ScheduledExecutorService scheduler = (ScheduledExecutorService) field("renderScheduler").get(this);
            ScheduledFuture<?> tick = (ScheduledFuture<?>) field("windowTick").get(this);
            if (tick != null) tick.cancel(false);
            Future<?> finalFrame = scheduler.schedule(() -> {
                try {
                    RENDER_THREAD_FUNC.invoke(this);
                    glfwMakeContextCurrent(fieldWindow());
                    glFinish();
                } catch (ReflectiveOperationException error) {
                    throw new IllegalStateException("Could not capture the final startup frame", error);
                } finally {
                    glfwMakeContextCurrent(0L);
                }
            }, 20, TimeUnit.MILLISECONDS);
            finalFrame.get(5, TimeUnit.SECONDS);
            frozen = true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            freezeFailed = true;
            LOGGER.warn("Interrupted while freezing the startup display", error);
        } catch (ReflectiveOperationException | ExecutionException | TimeoutException error) {
            freezeFailed = true;
            LOGGER.warn("Could not freeze the startup display; the game will use a blank loading image", error);
        } finally {
            transitionReady = true;
        }
    }

    public boolean deferGameWindowFullscreen(long window) {
        return independentWindow && !fullscreenAllowed && window == gameWindow;
    }

    public boolean usesIndependentWindow() { return independentWindow; }
    public boolean isTransitionReady() { return !independentWindow || transitionReady; }

    public void requestGameWindowReveal() {
        if (independentWindow && transitionStarted) revealRequested = true;
    }

    public void revealGameWindowAfterFrame(long window) {
        if (window != gameWindow) return;
        if (!closed.get() && independentWindow && revealRequested && !gameWindowShown) {
            revealRequested = false;
            glfwShowWindow(gameWindow);
            glfwHideWindow(fieldWindow());
            gameWindowShown = true;
            LOGGER.info("Showing the game window after its first loading frame");
        }
        if (closeRequested.get()) closeIndependentWindow();
    }

    public void closeGameWindow(long window) {
        if (window != gameWindow) return;
        if (independentWindow) {
            closeRequested.set(true);
            closeIndependentWindow();
        } else close();
    }

    @Override
    public void render(int alpha) {
        if (closed.get()) return;
        if (!independentWindow) super.render(alpha);
    }

    @Override
    public int getFramebufferTextureId() {
        if (closed.get()) return placeholderTexture;
        if (independentWindow && (!frozen || freezeFailed)) return placeholderTexture;
        return super.getFramebufferTextureId();
    }

    public void lifecycleStage(String stageName) {
        try {
            stages.markStage(Scene.Stage.valueOf(stageName));
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void installRenderer() {
        try {
            SimpleFont font = (SimpleFont) field("font").get(this);
            Scene scene = loadScene();
            renderer = new StartupRenderer(scene, font);
            @SuppressWarnings("unchecked")
            List<RenderElement> elements = (List<RenderElement>) field("elements").get(this);
            RenderElement element = createRenderElement();
            customElement.set(element);
            elements.clear();
            elements.add(element);
            installed = true;
            LOGGER.info("Loaded startup scene with {} elements", scene.elements().size());
        } catch (Throwable error) {
            installed = true;
            LOGGER.warn("Could not initialize the custom loading screen; the default loading screen will remain active", error);
        }
    }

    private Scene loadScene() throws Exception {
        Path path = FMLPaths.CONFIGDIR.get().resolve("customloadingscreen/scene.json");
        Files.createDirectories(path.getParent());
        if (!Files.exists(path)) {
            try (InputStream bundled = getClass().getResourceAsStream("/assets/customloadingscreen/startup/scene.json")) {
                if (bundled == null) throw new IllegalStateException("Bundled startup scene is missing");
                try {
                    Files.copy(bundled, path);
                } catch (FileAlreadyExistsException ignored) {
                }
            }
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes((int) MAX_SCENE_BYTES + 1);
            if (bytes.length > MAX_SCENE_BYTES) throw new IllegalArgumentException("Scene file is larger than 1 MiB");
            return Scene.load(new ByteArrayInputStream(bytes));
        }
    }

    private RenderElement createRenderElement() throws ReflectiveOperationException {
        Class<?> initializerType = Class.forName("net.neoforged.fml.earlydisplay.RenderElement$Initializer");
        Class<?> rendererType = Class.forName("net.neoforged.fml.earlydisplay.RenderElement$Renderer");
        Object rendererProxy = Proxy.newProxyInstance(rendererType.getClassLoader(), new Class<?>[] { rendererType }, (proxy, method, args) -> {
            if (method.getName().equals("accept")) {
                int alpha = renderAlpha();
                if (!renderFailed.get()) {
                    try {
                        renderer.render((net.neoforged.fml.earlydisplay.SimpleBufferBuilder) args[0],
                                (RenderElement.DisplayContext) args[1], (int) args[2], alpha, stages);
                    } catch (Throwable error) {
                        if (renderFailed.compareAndSet(false, true)) {
                            LOGGER.warn("Custom loading screen rendering failed; using a solid-color loading screen", error);
                            customElement.get().retire((int) args[2] + 1);
                            closeElementBuffer(customElement.get());
                            customElement.set(null);
                            renderer.close();
                        }
                    }
                }
                return null;
            }
            return objectMethod(proxy, method.getName(), args);
        });
        Object initializerProxy = Proxy.newProxyInstance(initializerType.getClassLoader(), new Class<?>[] { initializerType },
                (proxy, method, args) -> method.getName().equals("get") ? rendererProxy : objectMethod(proxy, method.getName(), args));
        Constructor<RenderElement> constructor = RenderElement.class.getDeclaredConstructor(initializerType);
        constructor.setAccessible(true);
        return constructor.newInstance(initializerProxy);
    }

    private int renderAlpha() {
        try {
            if (GLOBAL_ALPHA == null) return 255;
            return GLOBAL_ALPHA.getInt(null);
        } catch (ReflectiveOperationException error) {
            return 255;
        }
    }

    private Field field(String name) throws ReflectiveOperationException {
        Field field = DisplayWindow.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Override
    public void close() {
        if (independentWindow) {
            if (closed.get() || !closeRequested.compareAndSet(false, true)) return;
            fullscreenAllowed = true;
            cancelWindowTicks();
            return;
        }
        if (!closed.compareAndSet(false, true)) return;
        if (customElement.get() != null) closeElementBuffer(customElement.get());
        if (renderer != null && !renderFailed.get()) renderer.close();
        super.close();
    }

    private synchronized void closeIndependentWindow() {
        if (!independentWindow || !closed.compareAndSet(false, true)) return;
        fullscreenAllowed = true;
        try {
            if (clearTextureBinding != null) clearTextureBinding.invoke(null);
        } catch (ReflectiveOperationException error) {
            LOGGER.warn("Could not clear the captured loading texture binding", error);
        }
        cancelWindowTicks();
        long earlyWindow = fieldWindow();
        try {
            ScheduledExecutorService scheduler = (ScheduledExecutorService) field("renderScheduler").get(this);
            Future<?> cleanup = scheduler.submit(() -> {
                glfwMakeContextCurrent(earlyWindow);
                try {
                    if (customElement.get() != null) closeElementBuffer(customElement.get());
                    if (renderer != null && !renderFailed.get()) renderer.close();
                    if (placeholderTexture != 0) glDeleteTextures(placeholderTexture);
                    super.close();
                } finally {
                    glfwMakeContextCurrent(0L);
                }
            });
            cleanup.get(10, TimeUnit.SECONDS);
            closeCallback(glfwSetFramebufferSizeCallback(earlyWindow, null));
            closeCallback(glfwSetWindowPosCallback(earlyWindow, null));
            closeCallback(glfwSetWindowSizeCallback(earlyWindow, null));
            glfwDestroyWindow(earlyWindow);
            field("window").setLong(this, 0L);
            independentWindow = false;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while closing the startup display", error);
        } catch (ReflectiveOperationException | ExecutionException | TimeoutException error) {
            throw new IllegalStateException("Could not close the startup display before GLFW shutdown", error);
        }
    }

    private void cancelWindowTicks() {
        try {
            ScheduledFuture<?> tick = (ScheduledFuture<?>) field("windowTick").get(this);
            ScheduledFuture<?> performance = (ScheduledFuture<?>) field("performanceTick").get(this);
            if (tick != null) tick.cancel(false);
            if (performance != null) performance.cancel(false);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not stop the startup display scheduler", error);
        }
    }

    private static void closeCallback(AutoCloseable callback) {
        if (callback == null) return;
        try {
            callback.close();
        } catch (Exception error) {
            LOGGER.debug("Could not release an early-window callback", error);
        }
    }

    private void closeElementBuffer(RenderElement element) {
        if (element == null) return;
        if (!elementBufferClosed.compareAndSet(false, true)) return;
        try {
            Field buffer = RenderElement.class.getDeclaredField("bb");
            buffer.setAccessible(true);
            ((AutoCloseable) buffer.get(element)).close();
        } catch (Exception error) {
            LOGGER.debug("Could not release custom loading screen buffer", error);
        }
    }


    private static Object objectMethod(Object proxy, String name, Object[] args) {
        return switch (name) {
            case "toString" -> "Custom loading screen provider proxy";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> null;
        };
    }

    private static Field findGlobalAlpha() {
        try {
            Field field = RenderElement.class.getDeclaredField("globalAlpha");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException error) {
            return null;
        }
    }
}
