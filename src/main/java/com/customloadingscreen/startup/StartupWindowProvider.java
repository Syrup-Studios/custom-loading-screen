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
import java.util.concurrent.Semaphore;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;

public final class StartupWindowProvider extends DisplayWindow {
    public static final String PROVIDER_NAME = "customloadingscreen";
    private static final Logger LOGGER = LoggerFactory.getLogger(StartupWindowProvider.class);
    private static final long MAX_SCENE_BYTES = 1_048_576;
    private static final Field GLOBAL_ALPHA = findGlobalAlpha();

    private final StartupStageAdapter stages = new StartupStageAdapter();
    private final AtomicReference<RenderElement> customElement = new AtomicReference<>();
    private final AtomicBoolean renderFailed = new AtomicBoolean();
    private final AtomicBoolean elementBufferClosed = new AtomicBoolean();
    private volatile StartupRenderer renderer;
    private volatile boolean installed;
    private long gameWindow;
    private Field framebufferWidth;
    private Field framebufferHeight;
    private final int[] framebufferWidthValue = new int[1];
    private final int[] framebufferHeightValue = new int[1];

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

    @Override
    public Runnable initialize(String[] arguments) {
        LOGGER.info("Initializing custom early loading window");
        return super.initialize(arguments);
    }

    @Override
    public long setupMinecraftWindow(IntSupplier width, IntSupplier height, Supplier<String> title, LongSupplier monitorSupplier) {
        gameWindow = super.setupMinecraftWindow(width, height, title, monitorSupplier);
        try {
            framebufferWidth = field("fbWidth");
            framebufferHeight = field("fbHeight");
            refreshFramebufferSize();
        } catch (ReflectiveOperationException error) {
            framebufferWidth = null;
            framebufferHeight = null;
            LOGGER.warn("Could not refresh the early window framebuffer size after handoff", error);
        }
        return gameWindow;
    }

    @Override
    public void periodicTick() {
        refreshFramebufferSize();
        super.periodicTick();
    }

    private void refreshFramebufferSize() {
        if (gameWindow == 0 || framebufferWidth == null || framebufferHeight == null) return;
        glfwGetFramebufferSize(gameWindow, framebufferWidthValue, framebufferHeightValue);
        if (framebufferWidthValue[0] == 0 || framebufferHeightValue[0] == 0) return;
        try {
            framebufferWidth.setInt(this, framebufferWidthValue[0]);
            framebufferHeight.setInt(this, framebufferHeightValue[0]);
        } catch (IllegalAccessException error) {
            framebufferWidth = null;
            framebufferHeight = null;
            LOGGER.warn("Could not refresh the early window framebuffer size after handoff", error);
        }
    }

    @Override
    public void updateModuleReads(ModuleLayer layer) {
        Module neoForge = layer.findModule("neoforge").orElseThrow();
        getClass().getModule().addReads(neoForge);
        Class<?> overlay = Class.forName(neoForge, "net.neoforged.neoforge.client.loading.NeoForgeLoadingOverlay");
        try {
            Method method = overlay.getMethod("newInstance", Supplier.class, Supplier.class, Consumer.class, DisplayWindow.class);
            field("loadingOverlay").set(this, method);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not initialize the NeoForge loading overlay", error);
        }
    }

    @Override
    public void addMojangTexture(int textureId) {
    }

    @Override
    public <T> Supplier<T> loadingOverlay(Supplier<?> minecraft, Supplier<?> reload, Consumer<Optional<Throwable>> onFinish, boolean fade) {
        return super.loadingOverlay(minecraft, reload, result -> {
            if (result.isEmpty()) {
                stages.complete();
                LOGGER.info("Startup loading completed");
            }
            onFinish.accept(result);
        }, fade);
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
        if (customElement.get() != null) closeElementBuffer(customElement.get());
        if (renderer != null && !renderFailed.get()) renderer.close();
        super.close();
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
