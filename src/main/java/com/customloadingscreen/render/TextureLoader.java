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
import net.neoforged.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;

/** Loads small startup textures while the early display owns the GL context. */
public final class TextureLoader implements AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    private static final long MAX_CACHED_PIXEL_BYTES = 128L * 1024 * 1024;
    private final Map<String, Texture> textures = new HashMap<>();
    private long cachedPixelBytes;

    public int load(String name) {
        String key;
        try { key = safeName(name); } catch (RuntimeException ex) { return 0; }
        Texture cached = textures.get(key);
        if (cached != null) return cached.id;
        ByteBuffer encoded;
        try {
            Path assetRoot = FMLPaths.CONFIGDIR.get().resolve("customloadingscreen").resolve("assets").normalize();
            Path external = assetRoot.resolve(key).normalize();
            if (external.startsWith(assetRoot) && Files.isRegularFile(external)) {
                long size = Files.size(external);
                if (size < 1 || size > MAX_FILE_BYTES) return failed(key);
                encoded = BufferUtils.createByteBuffer((int) size);
                try (InputStream in = Files.newInputStream(external)) { encoded = readBounded(in, encoded); }
            } else {
                try (InputStream in = TextureLoader.class.getResourceAsStream("/assets/customloadingscreen/startup/" + key)) {
                    if (in == null) return failed(key);
                    encoded = BufferUtils.createByteBuffer(8192);
                    encoded = readBounded(in, encoded);
                }
            }
        } catch (IOException | RuntimeException ex) {
            return failed(key);
        }
        encoded.flip();
        int imageWidth, imageHeight;
        try (var stack = stackPush()) {
            var width = stack.mallocInt(1);
            var height = stack.mallocInt(1);
            var channels = stack.mallocInt(1);
            if (!stbi_info_from_memory(encoded, width, height, channels) || width.get(0) < 1 || height.get(0) < 1 || width.get(0) > 4096 || height.get(0) > 4096 || width.get(0) * (long) height.get(0) > 16_777_216L) {
                return failed(key);
            }
            long pixelBytes = width.get(0) * (long) height.get(0) * 4;
            if (pixelBytes > MAX_CACHED_PIXEL_BYTES - cachedPixelBytes) return failed(key);
            encoded.rewind();
            ByteBuffer pixels = stbi_load_from_memory(encoded, width, height, channels, 4);
            if (pixels == null) return failed(key);
            if (width.get(0) < 1 || height.get(0) < 1 || width.get(0) > 4096 || height.get(0) > 4096 || width.get(0) * (long) height.get(0) > 16_777_216L) {
                stbi_image_free(pixels);
                return failed(key);
            }
            imageWidth = width.get(0);
            imageHeight = height.get(0);
            pixelBytes = imageWidth * (long) imageHeight * 4;
            if (pixelBytes > MAX_CACHED_PIXEL_BYTES - cachedPixelBytes) {
                stbi_image_free(pixels);
                return failed(key);
            }
            int texture = 0;
            try {
                while (glGetError() != GL_NO_ERROR) { }
                texture = glGenTextures();
                if (texture == 0) throw new IllegalStateException("Could not allocate OpenGL texture");
                glBindTexture(GL_TEXTURE_2D, texture);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width.get(0), height.get(0), 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                int error = GL_NO_ERROR;
                int nextError;
                while ((nextError = glGetError()) != GL_NO_ERROR) error = nextError;
                if (error != GL_NO_ERROR) throw new IllegalStateException("OpenGL texture upload failed: " + error);
            } catch (RuntimeException ex) {
                if (texture != 0) glDeleteTextures(texture);
                return failed(key);
            } finally { stbi_image_free(pixels); }
            textures.put(key, new Texture(texture, imageWidth, imageHeight));
            cachedPixelBytes += pixelBytes;
            return texture;
        }
    }

    public int width(String name) { Texture t = cached(name); return t == null ? 0 : t.width; }
    public int height(String name) { Texture t = cached(name); return t == null ? 0 : t.height; }

    private Texture cached(String name) {
        try { return textures.get(safeName(name)); } catch (RuntimeException ex) { return null; }
    }

    private record Texture(int id, int width, int height) {}

    private int failed(String key) {
        if (!textures.containsKey(key)) LOGGER.warn("Could not load startup texture '{}'; using the scene fallback.", key);
        textures.put(key, new Texture(0, 0, 0));
        return 0;
    }

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
        textures.values().forEach(texture -> { if (texture.id != 0) glDeleteTextures(texture.id); });
        textures.clear();
        cachedPixelBytes = 0;
    }
}
