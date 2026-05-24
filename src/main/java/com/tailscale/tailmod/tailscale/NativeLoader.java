package com.tailscale.tailmod.tailscale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Распаковывает libtailscale из jar во временную директорию и загружает через System.load().
 *
 * Нативки хранятся по пути:
 *   /natives/<os>-<arch>/libtailscale.so  (Linux)
 *   /natives/<os>-<arch>/tailscale.dll    (Windows)
 *   /natives/<os>-<arch>/libtailscale.dylib (macOS)
 */
public final class NativeLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger("tailmod");
    private static volatile boolean loaded = false;

    public static synchronized void load() {
        if (loaded) return;

        String os   = osName();
        String arch = archName();
        String lib  = libFileName(os);
        String resource = "/natives/" + os + "-" + arch + "/" + lib;

        try (InputStream in = NativeLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new UnsatisfiedLinkError(
                        "Native library not bundled: " + resource +
                        "\nBuild libtailscale and place it at src/main/resources" + resource);
            }
            // Extract with the EXACT filename so JNA can find it by name
            Path tmpDir = Files.createTempDirectory("tailmod-natives-");
            tmpDir.toFile().deleteOnExit();
            Path tmp = tmpDir.resolve(lib);  // e.g. libtailscale.so (not libtailscale-XXXX.so)
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            tmp.toFile().deleteOnExit();
            // Register the directory so Native.load("tailscale", ...) finds it
            com.sun.jna.NativeLibrary.addSearchPath("tailscale", tmpDir.toString());
            LOGGER.info("[tailmod] Loaded native library from {}", resource);
        } catch (IOException e) {
            throw new RuntimeException("Failed to extract native library", e);
        }
        loaded = true;
    }

    private static String osName() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win"))   return "windows";
        if (os.contains("mac"))   return "macos";
        return "linux";
    }

    private static String archName() {
        String arch = System.getProperty("os.arch").toLowerCase();
        if (arch.equals("amd64") || arch.equals("x86_64")) return "amd64";
        if (arch.equals("aarch64") || arch.equals("arm64")) return "arm64";
        return arch;
    }

    private static String libFileName(String os) {
        return switch (os) {
            case "windows" -> "tailscale.dll";
            case "macos"   -> "libtailscale.dylib";
            default        -> "libtailscale.so";
        };
    }
}
