package dev.duck.renewal;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;

/** Native config writers hold the same Java lock as renewal until their pipe closes. */
final class ConfigLease {
    static void hold(Path data, InputStream owner, OutputStream reply) throws Exception {
        Path root = data.resolve("var/renewal");
        Renewal.mkdir(root);
        Path path = root.resolve("operation.lock"); Keybox.safePath(path);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            Renewal.permission(path, "rw-------");
            FileLock lock;
            try { lock = channel.tryLock(); } catch (OverlappingFileLockException e) { lock = null; }
            if (lock == null) throw new IOException("operation_busy");
            try {
                reply.write(new byte[]{'L','O','C','K','E','D','\n'}); reply.flush();
                while (owner.read() != -1) { /* Owner lifetime is the pipe lifetime. */ }
            } finally { lock.release(); }
        }
    }
}
