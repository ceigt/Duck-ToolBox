package dev.duck.renewal;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** ITxiao6666/OhMyKeymint bafe5ba: fixed target and parent-directory rename watcher. */
final class Omk extends Main {
    final Path sysroot;
    Omk(Path module, Path data, Path sysroot) {
        super(module, data, sysroot.resolve("data/misc/keystore/omk"));
        this.sysroot = sysroot;
    }
    @Override public String backend() { return "ohmykeymint"; }
    @Override public boolean supported() throws Exception {
        Path modules = sysroot.resolve("data/adb/modules");
        Path omk = modules.resolve("oh_my_keymint");
        if (!active(omk) || !active(module) || active(modules.resolve("teesim"))
                || active(modules.resolve("tricky_store"))) return false;
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(Keybox.readFile(omk.resolve("module.prop"), 16 * 1024)));
        return Integer.parseInt(properties.getProperty("versionCode", "0")) >= 222
                && Files.isRegularFile(tee.resolve("config.toml"), LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(tee.resolve("injector.toml"), LinkOption.NOFOLLOW_LINKS);
    }
    // Ordinary OMK imports retain their native upstream path; this helper handles renewal only.
    @Override public boolean manualSupported() { return false; }
    @Override public void reload(Path changed) throws Exception {
        if (!changed.equals(tee.resolve("keybox.xml"))) throw new IOException("target_unsupported");
    }
    String configHash() throws Exception {
        return Keybox.sha256(Keybox.readFile(tee.resolve("config.toml"), 256 * 1024))
                + Keybox.sha256(Keybox.readFile(tee.resolve("injector.toml"), 256 * 1024));
    }
    String process(Path pid) throws Exception {
        String stat = new String(Keybox.readFile(pid.resolve("stat"), 8192), StandardCharsets.UTF_8);
        String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split("\\s+");
        return fields[19]; // /proc stat field 22: starttime, independent of the parenthesized name.
    }
    static final class Evidence {
        Object key; long size; String config;
        byte[] prefix;
        Map<Path, String> processes = new HashMap<>();
    }
    @Override public Object cursor() throws Exception {
        if (!supported()) throw new IOException("target_unsupported");
        Evidence cursor = new Evidence();
        Path log = tee.resolve("logs/keymint.log"); Keybox.safePath(log);
        BasicFileAttributes attrs = Files.readAttributes(log, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile()) throw new IOException("unsafe_log");
        cursor.key = attrs.fileKey(); cursor.size = attrs.size(); cursor.config = configHash();
        cursor.prefix = prefix(log, cursor.size);
        Set<String> executables = new HashSet<>();
        for (String name : Arrays.asList("data/adb/omk/keymint", "data/adb/modules/oh_my_keymint/keymint",
                "data/adb/modules/oh_my_keymint/libs/arm64-v8a/keymint"))
            executables.add(sysroot.resolve(name).toString());
        try (DirectoryStream<Path> children = Files.newDirectoryStream(sysroot.resolve("proc"))) {
            for (Path pid : children) {
                if (!pid.getFileName().toString().matches("[0-9]+")) continue;
                try {
                    String command = new String(Keybox.readFile(pid.resolve("cmdline"), 8192), StandardCharsets.UTF_8);
                    int end = command.indexOf('\0');
                    if (end > 0 && executables.contains(command.substring(0, end)))
                        cursor.processes.put(pid, process(pid));
                } catch (IOException vanished) { /* Unrelated processes can exit while enumerating. */ }
            }
        }
        if (cursor.processes.isEmpty()) throw new IOException("target_unsupported");
        return cursor;
    }
    static byte[] prefix(Path log, long size) throws Exception {
        byte[] bytes = new byte[(int) Math.min(size, 1024)];
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            file.seek(size - bytes.length); file.readFully(bytes);
        }
        return bytes;
    }
    static boolean loaded(String tail, String path) {
        boolean success = false;
        for (String line : tail.split("\n")) {
            if (line.contains("keymint::keybox - active keybox identity updated from " + path + " (fallback=false)")
                    || line.contains("keymint::keybox - keybox reload completed without identity change (fallback=false)"))
                success = true;
            if (line.contains("keymint::keybox -") && (line.contains("fallback=true")
                    || line.contains("invalid keybox.xml") || line.contains("failed to reload keybox.xml"))) return false;
        }
        return success;
    }
    @Override public boolean accepted(String id, Object value) throws Exception {
        Evidence cursor = (Evidence) value;
        Path target = tee.resolve("keybox.xml"), log = tee.resolve("logs/keymint.log");
        byte[] xml = Keybox.readFile(target, 128 * 1024);
        if (!id.equals(Keybox.runtimeId(xml))) return false;
        String hash = Keybox.sha256(xml);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        do {
            if (!supported() || !cursor.config.equals(configHash())) return false;
            for (Map.Entry<Path, String> p : cursor.processes.entrySet())
                if (!p.getValue().equals(process(p.getKey()))) return false;
            Keybox.safePath(log);
            BasicFileAttributes attrs = Files.readAttributes(log, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            long length = attrs.size() - cursor.size;
            if (!attrs.isRegularFile() || !Objects.equals(cursor.key, attrs.fileKey()) || length < 0 || length > 512 * 1024)
                return false;
            if (!Arrays.equals(cursor.prefix, prefix(log, cursor.size))) return false;
            byte[] tail = new byte[(int) length];
            try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
                file.seek(cursor.size); file.readFully(tail);
            }
            if (loaded(new String(tail, StandardCharsets.UTF_8), target.toString())
                    && hash.equals(Keybox.sha256(Keybox.readFile(target, 128 * 1024)))
                    && cursor.config.equals(configHash())) return true;
            Thread.sleep(500);
        } while (System.nanoTime() < deadline);
        return false;
    }
}
