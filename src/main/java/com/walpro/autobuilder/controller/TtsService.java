package com.walpro.autobuilder.controller;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

/**
 * Offline TTS via Piper (https://github.com/rhasspy/piper). Runs Piper as a child process
 * per synthesis; binary + voice model are fetched to a local cache on first start, so the
 * only user-visible action is `mvn spring-boot:run`.
 */
@Service
public class TtsService {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);

    @Value("${autobuilder.tts.cache-dir:${user.home}/.cache/autobuilder-piper}")
    private String cacheDirStr;

    @Value("${autobuilder.tts.voice:en_US-amy-medium}")
    private String voice;

    @Value("${autobuilder.tts.piper-version:2023.11.14-2}")
    private String piperVersion;

    // voice model URL template — rhasspy hosts voices on HuggingFace in a predictable layout.
    // Example voice id "en_US-amy-medium" -> lang=en, region=en_US, name=amy, quality=medium.
    @Value("${autobuilder.tts.voice-base-url:https://huggingface.co/rhasspy/piper-voices/resolve/main}")
    private String voiceBaseUrl;

    @Value("${autobuilder.tts.piper-base-url:https://github.com/rhasspy/piper/releases/download}")
    private String piperBaseUrl;

    private Path piperBinary;
    private Path voiceModel;
    private boolean ready;

    @PostConstruct
    void init() {
        try {
            Path cacheDir = Path.of(cacheDirStr.replace("${user.home}", System.getProperty("user.home")));
            Files.createDirectories(cacheDir);
            piperBinary = ensurePiperInstalled(cacheDir);
            voiceModel = ensureVoiceInstalled(cacheDir);
            ready = true;
            log.info("Piper TTS ready (binary={}, voice={})", piperBinary, voiceModel);
        } catch (Exception e) {
            log.warn("Piper TTS init failed — /tts/synthesize will return 503 until fixed. Reason: {}", e.getMessage(), e);
        }
    }

    public byte[] synthesizeWav(String text) throws IOException, InterruptedException {
        if (!ready) {
            throw new IllegalStateException("Piper not initialised — see startup logs.");
        }
        Path piperDir = piperBinary.getParent();
        ProcessBuilder pb = new ProcessBuilder(
                piperBinary.toString(),
                "--model", voiceModel.toString(),
                "--output_file", "-"
        );
        pb.directory(piperDir.toFile());
        // Piper's libs ship alongside the binary; Linux builds use $ORIGIN rpath but set LD_LIBRARY_PATH
        // defensively so distros without working rpath (and containers with odd loaders) still link.
        pb.environment().merge("LD_LIBRARY_PATH", piperDir.toString(),
                (old, add) -> add + java.io.File.pathSeparator + old);
        pb.redirectErrorStream(false);

        Process p = pb.start();

        // Drain stderr concurrently so the buffer can't fill and so we always have the real error
        // to attach to a broken-pipe failure on stdin (Piper exits fast on misconfig).
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        Thread errPump = Thread.ofVirtual().start(() -> {
            try (var err = p.getErrorStream()) { err.transferTo(errBuf); } catch (IOException ignored) {}
        });

        try {
            try (var stdin = p.getOutputStream()) {
                stdin.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                stdin.write('\n');
            } catch (IOException brokenPipe) {
                // Piper already exited — fall through so we can report its stderr instead.
            }

            byte[] wav;
            try (var stdout = p.getInputStream()) {
                wav = stdout.readAllBytes();
            }
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("Piper timed out");
            }
            errPump.join(500);
            if (p.exitValue() != 0) {
                throw new IOException("Piper exit " + p.exitValue() + ": "
                        + errBuf.toString(java.nio.charset.StandardCharsets.UTF_8));
            }
            return wav;
        } finally {
            errPump.interrupt();
        }
    }

    // ---- install/cache helpers ----

    private Path ensurePiperInstalled(Path cacheDir) throws IOException, InterruptedException {
        Path piperDir = cacheDir.resolve("piper-" + piperVersion);
        Path binary = piperDir.resolve("piper").resolve(isWindows() ? "piper.exe" : "piper");
        if (Files.isExecutable(binary)) return binary;

        String asset = piperAssetName();
        String url = piperBaseUrl + "/" + piperVersion + "/" + asset;
        log.info("Downloading Piper runtime from {}", url);
        Path tarGz = cacheDir.resolve(asset);
        download(url, tarGz);

        Files.createDirectories(piperDir);
        extractTarGz(tarGz, piperDir);
        Files.deleteIfExists(tarGz);

        if (!Files.isExecutable(binary)) {
            // Some tarballs strip the +x bit during extraction; restore it on POSIX.
            try {
                Set<PosixFilePermission> perms = EnumSet.copyOf(Files.getPosixFilePermissions(binary));
                perms.add(PosixFilePermission.OWNER_EXECUTE);
                perms.add(PosixFilePermission.GROUP_EXECUTE);
                perms.add(PosixFilePermission.OTHERS_EXECUTE);
                Files.setPosixFilePermissions(binary, perms);
            } catch (UnsupportedOperationException ignored) {}
        }
        if (!Files.isExecutable(binary)) {
            throw new IOException("Piper binary not executable after extract: " + binary);
        }
        return binary;
    }

    private Path ensureVoiceInstalled(Path cacheDir) throws IOException, InterruptedException {
        Path voiceDir = cacheDir.resolve("voices").resolve(voice);
        Path onnx = voiceDir.resolve(voice + ".onnx");
        Path json = voiceDir.resolve(voice + ".onnx.json");
        if (Files.exists(onnx) && Files.exists(json)) return onnx;

        Files.createDirectories(voiceDir);
        String[] parts = voice.split("-", 3);       // e.g. "en_US", "amy", "medium"
        if (parts.length != 3) {
            throw new IOException("Voice id must be '<locale>-<name>-<quality>', got: " + voice);
        }
        String locale = parts[0];
        String lang = locale.split("_")[0];
        String name = parts[1];
        String quality = parts[2];
        String prefix = voiceBaseUrl + "/" + lang + "/" + locale + "/" + name + "/" + quality + "/" + voice;

        log.info("Downloading Piper voice '{}' from {}", voice, prefix);
        download(prefix + ".onnx", onnx);
        download(prefix + ".onnx.json", json);
        return onnx;
    }

    private static void download(String url, Path dest) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .GET().build();
        HttpResponse<Path> res = client.send(req, HttpResponse.BodyHandlers.ofFile(dest,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                java.nio.file.StandardOpenOption.WRITE));
        if (res.statusCode() != 200) {
            Files.deleteIfExists(dest);
            throw new IOException("Download failed: HTTP " + res.statusCode() + " for " + url);
        }
    }

    private static void extractTarGz(Path tarGz, Path destDir) throws IOException {
        try (InputStream fi = Files.newInputStream(tarGz);
             GZIPInputStream gz = new GZIPInputStream(fi);
             TarArchiveInputStream tar = new TarArchiveInputStream(gz)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                Path out = destDir.resolve(entry.getName()).normalize();
                if (!out.startsWith(destDir)) throw new IOException("Tar entry escapes dest: " + entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else if (entry.isSymbolicLink()) {
                    // Piper's tarball relies on symlinks for soversion chains (libespeak-ng.so → .so.1 → .so.1.52.0.1).
                    // Treating these as regular files leaves 0-byte stubs and the loader gives up silently.
                    Files.createDirectories(out.getParent());
                    Files.deleteIfExists(out);
                    Files.createSymbolicLink(out, Path.of(entry.getLinkName()));
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(tar, out, StandardCopyOption.REPLACE_EXISTING);
                    int mode = entry.getMode();
                    if ((mode & 0111) != 0) {
                        try {
                            Files.setPosixFilePermissions(out, PosixFilePermissions.fromString("rwxr-xr-x"));
                        } catch (UnsupportedOperationException ignored) {}
                    }
                }
            }
        }
    }

    private static String piperAssetName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (os.contains("linux")) {
            if (arch.contains("aarch64") || arch.contains("arm64")) return "piper_linux_aarch64.tar.gz";
            if (arch.contains("arm"))                                 return "piper_linux_armv7l.tar.gz";
            return "piper_linux_x86_64.tar.gz";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            if (arch.contains("aarch64") || arch.contains("arm64")) return "piper_macos_aarch64.tar.gz";
            return "piper_macos_x64.tar.gz";
        }
        throw new UnsupportedOperationException("Unsupported OS for Piper auto-install: " + os + "/" + arch
                + ". Override autobuilder.tts.cache-dir with a pre-installed Piper.");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
