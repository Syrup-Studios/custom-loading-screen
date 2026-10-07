package com.customloadingscreen.render;

import static org.lwjgl.opengl.GL32C.*;
import static org.lwjgl.stb.STBImage.*;
import static org.lwjgl.system.MemoryStack.stackPush;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import net.neoforged.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;

/** Loads startup textures while the early display owns the GL context. */
public final class TextureLoader implements AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    private static final long MAX_CACHED_PIXEL_BYTES = 128L * 1024 * 1024;
    private final Object lock = new Object();
    private final Map<String, Texture> textures = new HashMap<>();
    private final BlockingQueue<Request> requests = new LinkedBlockingQueue<>();
    private final Thread worker;
    private long cachedPixelBytes;
    private boolean closed;

    public TextureLoader() {
        worker = new Thread(this::decodeLoop, "customloadingscreen-textures");
        worker.setDaemon(true);
        worker.start();
    }

    /** Returns a ready texture id, or zero while loading or after a failure. */
    public int load(String name) {
        String key;
        try { key = safeName(name); } catch (RuntimeException ex) { return 0; }
        Texture requested = requestKey(key);
        if (requested == null) return 0;
        synchronized (lock) {
            if (closed || textures.get(key) != requested) return 0;
            Texture texture = requested;
            if (texture.id != 0 || texture.failed || texture.pixels == null) return texture.id;

            ByteBuffer pixels = texture.pixels;
            texture.pixels = null;
            int id = 0;
            try {
                while (glGetError() != GL_NO_ERROR) { }
                id = glGenTextures();
                if (id == 0) throw new IllegalStateException("Could not allocate OpenGL texture");
                glBindTexture(GL_TEXTURE_2D, id);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, texture.width, texture.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                int error = GL_NO_ERROR, nextError;
                while ((nextError = glGetError()) != GL_NO_ERROR) error = nextError;
                if (error != GL_NO_ERROR) throw new IllegalStateException("OpenGL texture upload failed: " + error);
                texture.id = id;
            } catch (RuntimeException ex) {
                if (id != 0) glDeleteTextures(id);
                texture.failed = true;
                cachedPixelBytes -= texture.pixelBytes;
                texture.pixelBytes = 0;
                LOGGER.warn("Could not upload startup texture '{}'; using the scene fallback.", key, ex);
            } finally {
                stbi_image_free(pixels);
            }
            return texture.id;
        }
    }

    /** Starts loading a texture without making OpenGL calls. */
    public void request(String name) {
        try { requestKey(safeName(name)); } catch (RuntimeException ignored) { }
    }

    private Texture requestKey(String key) {
        synchronized (lock) {
            if (closed) return null;
            Texture texture = textures.get(key);
            if (texture == null) {
                texture = new Texture();
                textures.put(key, texture);
                requests.offer(new Request(key, texture));
            }
            return texture;
        }
    }

    public int width(String name) { Texture t = cached(name); return t == null ? 0 : t.width; }
    public int height(String name) { Texture t = cached(name); return t == null ? 0 : t.height; }

    private Texture cached(String name) {
        try { synchronized (lock) { return textures.get(safeName(name)); } }
        catch (RuntimeException ex) { return null; }
    }

    private void decodeLoop() {
        while (true) {
            Request request;
            try { request = requests.take(); }
            catch (InterruptedException ex) { return; }
            long availablePixelBytes;
            synchronized (lock) {
                if (closed) return;
                availablePixelBytes = MAX_CACHED_PIXEL_BYTES - cachedPixelBytes;
            }
            Decoded decoded = decode(request.key, availablePixelBytes);
            synchronized (lock) {
                if (closed || textures.get(request.key) != request.texture) {
                    if (decoded != null) stbi_image_free(decoded.pixels);
                    if (closed) return;
                    continue;
                }
                if (decoded == null || decoded.pixelBytes > MAX_CACHED_PIXEL_BYTES - cachedPixelBytes) {
                    fail(request.key, request.texture);
                    if (decoded != null) stbi_image_free(decoded.pixels);
                } else {
                    request.texture.width = decoded.width;
                    request.texture.height = decoded.height;
                    request.texture.pixelBytes = decoded.pixelBytes;
                    request.texture.pixels = decoded.pixels;
                    cachedPixelBytes += decoded.pixelBytes;
                }
            }
        }
    }

    private static Decoded decode(String key, long availablePixelBytes) {
        ByteBuffer encoded;
        try {
            Path assetRoot = FMLPaths.CONFIGDIR.get().resolve("customloadingscreen").resolve("assets").normalize();
            Path external = assetRoot.resolve(key).normalize();
            if (external.startsWith(assetRoot) && Files.isRegularFile(external)) {
                long size = Files.size(external);
                if (size < 1 || size > MAX_FILE_BYTES) return null;
                encoded = BufferUtils.createByteBuffer((int) size);
                try (InputStream in = Files.newInputStream(external)) { encoded = readBounded(in, encoded); }
            } else {
                try (InputStream in = TextureLoader.class.getResourceAsStream("/assets/customloadingscreen/startup/" + key)) {
                    if (in == null) return null;
                    encoded = readBounded(in, BufferUtils.createByteBuffer(8192));
                }
            }
        } catch (IOException | RuntimeException ex) {
            return null;
        }
        encoded.flip();
        try (var stack = stackPush()) {
            var width = stack.mallocInt(1);
            var height = stack.mallocInt(1);
            var channels = stack.mallocInt(1);
            if (!stbi_info_from_memory(encoded, width, height, channels) || !validSize(width.get(0), height.get(0))) return null;
            long pixelBytes = width.get(0) * (long) height.get(0) * 4;
            if (pixelBytes > availablePixelBytes) return null;
            encoded.rewind();
            ByteBuffer pixels = stbi_load_from_memory(encoded, width, height, channels, 4);
            if (pixels == null) return null;
            if (!validSize(width.get(0), height.get(0))) {
                stbi_image_free(pixels);
                return null;
            }
            return new Decoded(pixels, width.get(0), height.get(0), pixelBytes);
        }
    }

    private static boolean validSize(int width, int height) {
        return width > 0 && height > 0 && width <= 4096 && height <= 4096 && width * (long) height <= 16_777_216L;
    }

    private void fail(String key, Texture texture) {
        if (!texture.failed) LOGGER.warn("Could not load startup texture '{}'; using the scene fallback.", key);
        texture.failed = true;
    }

    private static final class Texture {
        int id, width, height;
        long pixelBytes;
        ByteBuffer pixels;
        boolean failed;
    }
    private record Request(String key, Texture texture) {}
    private record Decoded(ByteBuffer pixels, int width, int height, long pixelBytes) {}

    private static String safeName(String name) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.contains("\\") || name.contains(":")) throw new IllegalArgumentException("Invalid texture path");
        Path path = Path.of(name).normalize();
        if (path.isAbsolute() || path.startsWith("..")) throw new IllegalArgumentException("Invalid texture path");
        return path.toString().replace('\\', '/');
    }

    private static ByteBuffer readBounded(InputStream in, ByteBuffer out) throws IOException {
        byte[] chunk = new byte[8192];
        int count;
        while ((count = in.read(chunk)) >= 0) {
            if (out.position() + count > MAX_FILE_BYTES) throw new IOException("Texture file is too large");
            if (out.remaining() < count) {
                int capacity = Math.min(MAX_FILE_BYTES, Math.max(out.capacity() * 2, out.position() + count));
                ByteBuffer grown = BufferUtils.createByteBuffer(capacity);
                out.flip();
                grown.put(out);
                out = grown;
            }
            out.put(chunk, 0, count);
        }
        return out;
    }

    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            requests.clear();
            textures.values().forEach(texture -> {
                if (texture.pixels != null) stbi_image_free(texture.pixels);
                if (texture.id != 0) glDeleteTextures(texture.id);
            });
            textures.clear();
            cachedPixelBytes = 0;
        }
        worker.interrupt();
    }
}
