package com.boongboongus.backend.domain.media;

import static com.boongboongus.backend.domain.media.MediaService.MediaError.invalidPhoto;
import static com.boongboongus.backend.domain.media.MediaService.MediaError.notFinished;
import static com.boongboongus.backend.domain.media.MediaService.MediaError.tooLarge;
import static com.boongboongus.backend.domain.media.MediaService.MediaError.unavailable;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.FileImageOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class PhotoStorage {
    static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final long MAX_PIXELS = 25_000_000;
    private static final Map<String, String> FORMATS = Map.of(
            "image/jpeg", "JPEG", "image/png", "PNG", "image/webp", "WebP");

    private final Path root;

    PhotoStorage(@Value("${app.media.storage-root}") String root) {
        this.root = root.isBlank() ? null : Path.of(root).toAbsolutePath().normalize();
    }

    void requireWritable() {
        if (root == null || !Files.isDirectory(root) || !Files.isWritable(root)) {
            throw unavailable();
        }
    }

    static String relativePath(String id) {
        return id + ".ready";
    }

    void put(String id, InputStream input, int expected) throws IOException {
        Path part = file(id, ".part");
        try {
            int received = 0;
            byte[] buffer = new byte[8192];
            try (OutputStream output = Files.newOutputStream(part)) {
                for (int count; (count = input.read(buffer)) != -1;) {
                    received += count;
                    if (received > MAX_BYTES) {
                        throw tooLarge();
                    }
                    if (received > expected) {
                        throw invalidPhoto();
                    }
                    output.write(buffer, 0, count);
                }
            }
            if (received != expected) {
                throw invalidPhoto();
            }
            Files.move(part, file(id, ".upload"), ATOMIC_MOVE, REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    Photo verify(String id, String storagePath, String type, int bytes, String expectedHash) throws IOException {
        Path original = file(id, ".upload");
        if (Files.notExists(original)) {
            throw notFinished();
        }
        if (Files.size(original) != bytes || !sha256(original).equals(expectedHash)) {
            throw invalidPhoto();
        }
        BufferedImage image = decode(original, FORMATS.get(type));
        String cleanType = "image/webp".equals(type) ? "image/png" : type;
        Path part = file(id, ".part");
        try {
            write(image, FORMATS.get(cleanType), part);
            long size = Files.size(part);
            if (size <= 0 || size > MAX_BYTES) {
                throw invalidPhoto();
            }
            Files.move(part, resolve(storagePath), ATOMIC_MOVE, REPLACE_EXISTING);
            return new Photo(cleanType, (int) size, image.getWidth(), image.getHeight());
        } finally {
            image.flush();
            Files.deleteIfExists(part);
        }
    }

    byte[] read(String storagePath, int expected) throws IOException {
        try (InputStream input = Files.newInputStream(resolve(storagePath))) {
            byte[] bytes = input.readNBytes(expected + 1);
            if (bytes.length != expected) {
                throw unavailable();
            }
            return bytes;
        }
    }

    void clean(String id, String storagePath, boolean deleted) throws IOException {
        Files.deleteIfExists(file(id, ".part"));
        Files.deleteIfExists(file(id, ".upload"));
        if (deleted) {
            Files.deleteIfExists(resolve(storagePath));
        }
    }

    private Path file(String id, String extension) {
        return resolve(id + extension);
    }

    private Path resolve(String relativePath) {
        requireWritable();
        Path name = Path.of(relativePath);
        Path file = root.resolve(name).normalize();
        if (name.isAbsolute() || name.getNameCount() != 1 || !root.equals(file.getParent())) {
            throw unavailable();
        }
        return file;
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static BufferedImage decode(Path path, String format) throws IOException {
        try (var input = new FileImageInputStream(path.toFile())) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalidPhoto();
            }
            ImageReader reader = readers.next();
            try {
                if (!format.equalsIgnoreCase(reader.getFormatName())) {
                    throw invalidPhoto();
                }
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
                    throw invalidPhoto();
                }
                reader.addIIOReadWarningListener((source, warning) -> {
                    throw invalidPhoto();
                });
                return reader.read(0);
            } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException error) {
                throw invalidPhoto();
            } finally {
                reader.dispose();
            }
        }
    }

    private static void write(BufferedImage image, String format, Path target) throws IOException {
        Files.deleteIfExists(target);
        ImageWriter writer = ImageIO.getImageWritersByFormatName(format).next();
        // Copy pixels into a standard color model so encoder metadata cannot come from the upload.
        int pixelType = image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage pixels = new BufferedImage(image.getWidth(), image.getHeight(), pixelType);
        var graphics = pixels.createGraphics();
        try {
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        try (var output = new FileImageOutputStream(target.toFile())) {
            writer.setOutput(output);
            writer.write(pixels);
        } finally {
            writer.dispose();
            pixels.flush();
        }
    }

    record Photo(String contentType, int bytes, int width, int height) {
    }
}
