package com.example.payments.notification;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

final class NotificationFiles {
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private NotificationFiles() { }

    static void directory(Path directory) throws IOException {
        if (directory == null) throw new IOException("Notification directory unavailable");
        Files.createDirectories(directory);
        permissions(directory, "rwx------");
    }

    static <T> T read(ObjectMapper mapper, Path file, Class<T> type) throws IOException {
        try (InputStream stream = Files.newInputStream(file)) {
            byte[] bytes = stream.readNBytes(32 * 1024 + 1);
            if (bytes.length > 32 * 1024) throw new IOException("Notification file too large");
            return mapper.readValue(bytes, type);
        }
    }

    static void write(ObjectMapper mapper, Path file, Object value) throws IOException {
        directory(file.getParent());
        Path temporary = Files.getFileStore(file.getParent()).supportsFileAttributeView("posix")
                ? Files.createTempFile(file.getParent(), ".telegram-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(file.getParent(), ".telegram-", ".tmp");
        try {
            mapper.writeValue(temporary.toFile(), value);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            // Refuse a partial replacement on filesystems without atomic rename support.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            permissions(file, "rw-------");
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static <T> T locked(Path file, boolean wait, IoAction<T> action) throws IOException {
        directory(file.getParent());
        ReentrantLock local = LOCKS.computeIfAbsent(file.toAbsolutePath().normalize(), ignored -> new ReentrantLock());
        if (wait) local.lock();
        else if (!local.tryLock()) return null;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            permissions(file, "rw-------");
            try (FileLock lock = wait ? channel.lock() : channel.tryLock()) {
                return lock == null ? null : action.run();
            }
        } finally {
            local.unlock();
        }
    }

    private static void permissions(Path file, String mode) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(mode));
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // Windows and some mounted filesystems do not expose POSIX permissions.
        }
    }

    interface IoAction<T> { T run() throws IOException; }
}
