package dev.duck.renewal;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.json.*;

/** Durable state machine. Platform calls are injected so tests never need a device or Google. */
final class Renewal {
    static final long HOUR = 3600000L, CHECK = 6 * HOUR, LEAD = 24 * HOUR;
    interface Platform {
        long now();
        default String backend() { return "teesimulator"; }
        boolean supported() throws Exception;
        default boolean manualSupported() throws Exception { return supported(); }
        boolean online() throws Exception;
        JSONObject issue(String protocol) throws Exception;
        JSONObject revocations() throws Exception;
        Object cursor() throws Exception;
        boolean accepted(String id, Object cursor) throws Exception;
        default void reload(Path changed) throws Exception { }
    }
    final Path root, outputs, target;
    final Set<String> pins;
    final Platform platform;
    JSONObject state;

    Renewal(Path root, Path outputs, Path target, Set<String> pins, Platform platform) throws Exception {
        this.root = root; this.outputs = outputs; this.target = target; this.pins = pins; this.platform = platform;
        Keybox.safePath(root); Keybox.safePath(outputs); Keybox.safePath(target);
        state = load();
    }

    JSONObject load() throws Exception {
        Path file = root.resolve("state.json");
        return Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                ? new JSONObject(new String(Keybox.readFile(file, 256 * 1024), StandardCharsets.UTF_8))
                : new JSONObject().put("protocol", "v2").put("phase", "idle").put("result", "disabled")
                    .put("blocked", false).put("attempts", new JSONArray()).put("owned", new JSONArray());
    }

    boolean enabled() throws Exception {
        return requested() && backendMatches();
    }

    boolean backendMatches() {
        return platform.backend().equals(state.optString("backend", "teesimulator"));
    }

    boolean requested() throws Exception {
        Path file = root.resolve("enabled");
        return Files.exists(file, LinkOption.NOFOLLOW_LINKS) && Arrays.equals(Keybox.readFile(file, 8), new byte[]{'1'});
    }

    JSONObject status() throws Exception {
        JSONObject result = new JSONObject().put("enabled", enabled())
                .put("backend", platform.backend()).put("target_path", target.toString())
                .put("protocol", state.optString("protocol", "v2"))
                .put("protocol_configured", state.optBoolean("protocol_configured") || enabled()
                        || state.optLong("last_check") > 0 || state.has("pending"))
                .put("phase", state.optString("phase", "idle"))
                .put("result", requested() && !backendMatches() ? "backend_confirmation_required" : state.optString("result", "disabled"))
                .put("blocked", state.optBoolean("blocked"))
                .put("diagnostic", state.optString("diagnostic", ""))
                .put("diagnostic_phase", state.optString("diagnostic_phase", ""))
                .put("last_check", state.optLong("last_check"))
                .put("last_success", state.optLong("last_success"))
                .put("retry_after", state.optLong("retry_after"))
                .put("check_hours", 6).put("lead_hours", 24)
                .put("pending", state.has("pending")).put("recovery_required", state.has("transaction"))
                .put("target_supported", platform.supported());
        try {
            long expires = StatusCache.expires(root, target, pins, platform.now());
            result.put("expires_at", expires).put("remaining_seconds", (expires - platform.now()) / 1000)
                    .put("certificate_valid", expires > platform.now());
        } catch (Exception ignored) {
            result.put("expires_at", JSONObject.NULL).put("remaining_seconds", JSONObject.NULL)
                    .put("certificate_valid", false);
        }
        return result;
    }

    JSONObject command(String command, String protocol) throws Exception {
        // Disabling is immediate even if another worker is finishing a transaction.
        if ("disable".equals(command)) {
            Keybox.safePath(root.resolve("enabled")); Files.deleteIfExists(root.resolve("enabled"));
            state = load(); return status();
        }
        if ("status".equals(command)) return status();
        if (!Arrays.asList("enable", "protocol", "tick", "resume", "recover", "discard", "install").contains(command)) throw new IOException("unknown_command");
        mkdir(root);
        Path lockPath = root.resolve("operation.lock"); Keybox.safePath(lockPath);
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            permission(lockPath, "rw-------");
            FileLock lock;
            try { lock = channel.tryLock(); } catch (OverlappingFileLockException ignored) { lock = null; }
            if (lock == null) throw new IOException("operation_busy");
            try {
                state = load();
                if ("install".equals(command)) {
                    installManual(protocol.getBytes(StandardCharsets.UTF_8));
                } else if ("recover".equals(command)) {
                    if (!state.has("transaction")) throw new IOException("rollback_required");
                    recover();
                } else if ("protocol".equals(command)) {
                    if (!Arrays.asList("v2", "v1", "v1-native").contains(protocol)) throw new IOException("protocol_required");
                    if (!"idle".equals(state.optString("phase")) || state.has("pending"))
                        throw new IOException("pending_protocol_locked");
                    state.put("protocol", protocol).put("protocol_configured", true);
                    save();
                } else if ("enable".equals(command)) {
                    if (!Arrays.asList("v2", "v1", "v1-native").contains(protocol)) throw new IOException("protocol_required");
                    if (!platform.supported()) throw new IOException("target_unsupported");
                    if (state.has("transaction") || (!backendMatches() && (state.has("pending")
                            || !"idle".equals(state.optString("phase", "idle")))))
                        throw new IOException("backend_changed");
                    Keybox.read(target, pins, platform.now(), false);
                    if (!"idle".equals(state.optString("phase")) && !protocol.equals(state.optString("protocol")))
                        throw new IOException("pending_protocol_locked");
                    state.put("protocol", protocol).put("protocol_configured", true).put("backend", platform.backend());
                    save(); atomic(root.resolve("enabled"), new byte[]{'1'});
                } else if ("resume".equals(command)) {
                    if ("installing".equals(state.optString("phase"))) throw new IOException("rollback_required");
                    if ("clock_invalid".equals(state.optString("result"))) {
                        long now = platform.now();
                        if (now < 1735689600000L) throw new IOException("clock_invalid");
                        // A backwards correction cannot reset the request budget.
                        JSONArray attempts = state.optJSONArray("attempts");
                        JSONArray corrected = new JSONArray();
                        for (int i = 0; attempts != null && i < attempts.length(); i++)
                            corrected.put(Math.min(attempts.getLong(i), now));
                        state.put("attempts", corrected).put("last_check", now)
                                .put("retry_after", now + LEAD);
                    }
                    state.remove("diagnostic"); state.remove("diagnostic_phase");
                    state.put("blocked", false).put("result", "manual_resume");
                    if ("issuing".equals(state.optString("phase"))) state.put("phase", "idle");
                    // Preserve the request count/cooldown even after manual acknowledgement.
                    save();
                } else if ("discard".equals(command)) {
                    if ("installing".equals(state.optString("phase")) || state.has("transaction"))
                        throw new IOException("rollback_required");
                    if (!state.has("pending")) throw new IOException("no_pending");
                    Keybox.readFile(target, 128 * 1024);
                    // Files stay in the ownership manifest; only expired, unchanged files are cleaned.
                    state.remove("pending"); state.remove("source_hash");
                    state.put("phase", "idle").put("blocked", false).put("result", "pending_discarded");
                    save();
                } else {
                    try { tick(); }
                    catch (Exception failure) {
                        state.put("diagnostic", Diagnostics.code(failure))
                                .put("diagnostic_phase", state.optString("phase", "idle"));
                        // Never log exception messages: a child response can contain key material.
                        if ("issuing".equals(state.optString("phase")))
                            state.put("blocked", true).put("result", "submission_unknown");
                        else if ("installing".equals(state.optString("phase"))) {
                            try { recover(); } catch (Exception rollbackFailure) {
                                state.put("blocked", true).put("result", "rollback_unconfirmed");
                            }
                        } else state.put("result", "local_check_failed").put("blocked", true);
                        save();
                    }
                }
                JSONObject result = status();
                if ("install".equals(command)) result.put("backup_path", state.optString("manual_backup"));
                return result;
            } finally { lock.release(); }
        }
    }

    private void tick() throws Exception {
        if ((state.has("pending") || "issuing".equals(state.optString("phase")))
                && !backendMatches()) throw new IOException("backend_changed");
        long now = platform.now();
        // Finish a recorded rollback even if renewal was disabled after the swap.
        if ("installing".equals(state.optString("phase"))) { recover(); return; }
        if ("issuing".equals(state.optString("phase"))) {
            state.put("blocked", true).put("result", "submission_unknown"); save(); return;
        }
        if (!enabled()) return;
        if (state.optBoolean("blocked")) return;
        state.remove("diagnostic"); state.remove("diagnostic_phase");
        if (now < 1735689600000L || now + 300000L < state.optLong("last_check")) {
            state.put("blocked", true).put("result", "clock_invalid"); save(); return;
        }
        state.put("last_check", now); save();
        if (!platform.supported()) { state.put("result", "target_unsupported"); save(); return; }
        Keybox current = Keybox.read(target, pins, now, false);
        if (state.has("pending")) {
            if (now >= state.optLong("retry_after")) finishPending(current);
            return;
        }
        if (current.hash.equals(state.optString("committed_hash")) && current.expires > now) cleanup(current);
        if (current.expires - now > LEAD) { state.put("result", "not_due"); save(); return; }
        JSONArray attempts = state.optJSONArray("attempts");
        JSONArray recent = new JSONArray();
        for (int i = 0; i < attempts.length(); i++)
            if (attempts.getLong(i) > now - 24 * HOUR) recent.put(attempts.getLong(i));
        state.put("attempts", recent);
        if (now < state.optLong("retry_after") || recent.length() >= 2) {
            state.put("result", "rate_limited"); save(); return;
        }
        if (!platform.online()) { state.put("result", "offline"); save(); return; }
        if (!enabled()) return;
        if (state.getJSONArray("owned").length() >= 128) {
            state.put("blocked", true).put("result", "storage_review_required"); save(); return;
        }
        // Persist BEFORE networking. A crash/timeout can never silently trigger a second request.
        recent.put(now);
        state.put("phase", "issuing").put("result", "issuing").put("retry_after", now + CHECK)
                .put("source_hash", current.hash);
        save();
        JSONObject response = platform.issue(state.getString("protocol"));
        if (!response.optBoolean("ok")) {
            String code = response.optJSONObject("error") == null ? "submission_unknown"
                    : response.getJSONObject("error").optString("code", "submission_unknown");
            boolean retry = "challenge_unavailable".equals(code) || "rkp_server_error".equals(code) || "operation_busy".equals(code);
            state.put("phase", retry ? "idle" : "issuing").put("blocked", !retry)
                    .put("result", retry ? "request_failed_retry" : "submission_review_required");
            if ("rkp_server_error".equals(code)) state.put("retry_after", now + 12 * HOUR);
            save(); return;
        }
        JSONObject data = response.getJSONObject("data");
        if (!"device-ta-oracle".equals(data.getString("mode"))) throw new IOException("wrong_issuer");
        JSONObject paths = data.getJSONObject("artifacts");
        Path run = Paths.get(paths.getString("run_dir"));
        ownedRun(run);
        if (!run.resolve("keybox.xml").toString().equals(paths.getString("keybox_path")))
            throw new IOException("wrong_keybox_path");
        Keybox next = Keybox.read(run.resolve("keybox.xml"), pins, now, true);
        if (next.expires <= now + LEAD || next.expires <= current.expires || next.hash.equals(current.hash))
            throw new IOException("new_certificate_not_better");
        JSONObject owner = inventory(run, next.expires);
        state.getJSONArray("owned").put(owner);
        state.put("pending", new JSONObject().put("run", run.toString()).put("hash", next.hash)
                .put("source_hash", current.hash).put("expires", next.expires));
        state.put("phase", "pending").put("result", "validating").put("retry_after", 0);
        save();
        finishPending(current);
    }

    private void finishPending(Keybox current) throws Exception {
        long now = platform.now();
        JSONObject pending = state.getJSONObject("pending");
        Path run = Paths.get(pending.getString("run")); ownedRun(run);
        Keybox next = Keybox.read(run.resolve("keybox.xml"), pins, now, false);
        if (!next.hash.equals(pending.getString("hash")) || !current.hash.equals(pending.getString("source_hash"))) {
            state.put("blocked", true).put("result", "target_changed"); save(); return;
        }
        if (next.expires <= now + LEAD || next.expires <= current.expires) {
            state.put("blocked", true).put("result", "pending_expired"); save(); return;
        }
        if (!platform.online()) { state.put("result", "pending_offline"); save(); return; }
        JSONObject revocations;
        try {
            revocations = platform.revocations().getJSONObject("entries");
            // A truncated or wrong-format list must never count as a revocation check.
            if (revocations.length() == 0) throw new IOException("empty_revocation_list");
        } catch (Exception ignored) {
            state.put("result", "revocation_unavailable").put("retry_after", now + CHECK); save(); return;
        }
        for (java.security.cert.X509Certificate cert : next.chain)
            if (revocations.has(cert.getSerialNumber().toString(16).toLowerCase(Locale.ROOT))) {
                state.put("blocked", true).put("result", "new_certificate_revoked"); save(); return;
            }
        if (!enabled()) return;
        if (!platform.supported() || !Keybox.sha256(Keybox.readFile(target, 128 * 1024)).equals(current.hash)) {
            state.put("blocked", true).put("result", "target_changed"); save(); return;
        }
        Path backup = root.resolve("backups").resolve(UUID.randomUUID().toString() + ".xml");
        mkdir(backup.getParent()); atomic(backup, current.xml);
        state.getJSONArray("owned").put(new JSONObject().put("kind", "backup").put("path", backup.toString())
                .put("hash", current.hash).put("expires", current.expires));
        state.put("transaction", new JSONObject().put("backup", backup.toString()).put("old_hash", current.hash)
                .put("new_hash", next.hash).put("backend", platform.backend()));
        state.put("phase", "installing").put("result", "installing"); save();
        Object cursor = platform.cursor();
        if (!Keybox.sha256(Keybox.readFile(target, 128 * 1024)).equals(current.hash)) {
            state.remove("transaction");
            state.put("phase", "pending").put("blocked", true).put("result", "target_changed");
            save(); return;
        }
        atomic(target, next.xml);
        platform.reload(target);
        boolean accepted = platform.accepted(next.runtimeId, cursor);
        if (accepted && Keybox.sha256(Keybox.readFile(target, 128 * 1024)).equals(next.hash) && platform.supported()) {
            state.put("phase", "idle").put("result", "renewed").put("last_success", platform.now())
                    .put("committed_hash", next.hash).put("retry_after", 0);
            state.remove("pending"); state.remove("transaction"); save();
            cleanup(next);
        } else recover();
    }

    /** A crash anywhere after recording a transaction restores the previous exact bytes. */
    private void recover() throws Exception {
        JSONObject transaction = state.getJSONObject("transaction");
        if (!platform.backend().equals(transaction.optString("backend", "teesimulator")))
            throw new IOException("backend_changed");
        Path restoreTarget = Paths.get(transaction.optString("target", target.toString()));
        Keybox.safePath(restoreTarget);
        if (!restoreTarget.startsWith(target.getParent())) throw new IOException("unowned_backup");
        String live = Files.exists(restoreTarget, LinkOption.NOFOLLOW_LINKS)
                ? Keybox.sha256(Keybox.readFile(restoreTarget, 128 * 1024)) : "";
        if (!transaction.optBoolean("old_exists", true)) {
            if (!live.isEmpty() && !live.equals(transaction.getString("new_hash"))) {
                state.put("blocked", true).put("result", "target_changed"); save(); return;
            }
            Files.deleteIfExists(restoreTarget);
            // There was no old certificate to reload. Never claim runtime rollback proof.
            state.remove("transaction");
            state.put("phase", "idle").put("blocked", true).put("result", "rollback_absent_unconfirmed");
            save(); return;
        }
        Path backup = Paths.get(transaction.getString("backup"));
        ownedBackup(backup);
        byte[] old = Keybox.readFile(backup, 128 * 1024);
        if (!Keybox.sha256(old).equals(transaction.getString("old_hash"))) throw new IOException("backup_changed");
        if (!live.equals(transaction.getString("new_hash")) && !live.equals(transaction.getString("old_hash"))) {
            state.put("blocked", true).put("result", "target_changed"); save(); return;
        }
        String previousId = Keybox.runtimeId(old);
        Object cursor = platform.cursor();
        atomic(restoreTarget, old);
        platform.reload(restoreTarget);
        boolean confirmed = platform.accepted(previousId, cursor);
        state.put("blocked", true).put("result", confirmed ? "rolled_back" : "rollback_unconfirmed");
        if (confirmed) { state.put("phase", state.has("pending") ? "pending" : "idle"); state.remove("transaction"); }
        save();
    }

    private void installManual(byte[] xml) throws Exception {
        if (state.has("transaction") || "issuing".equals(state.optString("phase"))) throw new IOException("rollback_required");
        if (!platform.manualSupported()) throw new IOException("target_unsupported");
        ManualKeybox.validate(xml, platform.now());
        mkdir(target.getParent());
        // Every Duck TEESimulator writer enters through this same FileLock/transaction.
        boolean existed = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        byte[] old = existed ? Keybox.readFile(target, 128 * 1024) : null;
        String oldHash = existed ? Keybox.sha256(old) : "", nextHash = Keybox.sha256(xml);
        Path backup = root.resolve("backups").resolve(UUID.randomUUID().toString() + ".xml");
        if (existed) { mkdir(backup.getParent()); atomic(backup, old); }
        state.put("manual_backup", existed ? backup.toString() : "");
        state.put("transaction", new JSONObject().put("backup", backup.toString())
                .put("old_hash", oldHash).put("old_exists", existed).put("target", target.toString())
                .put("new_hash", nextHash).put("kind", "manual").put("backend", platform.backend()));
        state.put("phase", "installing").put("result", "installing"); save();
        try {
            Object cursor = platform.cursor();
            String live = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    ? Keybox.sha256(Keybox.readFile(target, 128 * 1024)) : "";
            if (!live.equals(oldHash)) throw new IOException("target_changed");
            atomic(target, xml);
            platform.reload(target);
            if (!platform.accepted(Keybox.runtimeId(xml), cursor) || !platform.manualSupported()
                    || !Keybox.sha256(Keybox.readFile(target, 128 * 1024)).equals(nextHash)) {
                recover(); return;
            }
            state.remove("transaction");
            state.put("phase", state.has("pending") ? "pending" : "idle")
                    .put("blocked", state.has("pending")).put("result", "manual_installed"); save();
        } catch (Exception failure) {
            state.put("diagnostic", Diagnostics.code(failure)).put("diagnostic_phase", "manual_install");
            recover();
        }
    }

    private void ownedRunPath(Path path) throws Exception {
        Keybox.safePath(path);
        if (!path.getParent().equals(outputs) || !path.getFileName().toString().matches("rkp-device-[A-Za-z0-9_-]+"))
            throw new IOException("unowned_run_path");
    }
    private void ownedRun(Path path) throws Exception {
        ownedRunPath(path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("unowned_run_path");
    }
    private void ownedBackup(Path path) throws Exception {
        Keybox.safePath(path);
        if (!path.getParent().equals(root.resolve("backups"))
                || !path.getFileName().toString().matches("[a-f0-9-]{36}\\.xml")) throw new IOException("unowned_backup");
    }
    private JSONObject inventory(Path run, long expires) throws Exception {
        JSONObject files = new JSONObject();
        for (String name : Arrays.asList("keybox.xml", "private_key.pem", "csr.cbor", "cert_chain.der"))
            files.put(name, Keybox.sha256(Keybox.readFile(run.resolve(name), 2 * 1024 * 1024)));
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(run)) {
            for (Path child : stream) if (!files.has(child.getFileName().toString())) throw new IOException("unexpected_run_file");
        }
        return new JSONObject().put("kind", "run").put("path", run.toString()).put("expires", expires).put("files", files);
    }

    private void cleanup(Keybox current) throws Exception {
        // Only our manifest, only expired, only unchanged files, and never a pending/current box.
        JSONArray kept = new JSONArray(), owned = state.getJSONArray("owned");
        for (int i = 0; i < owned.length(); i++) {
            JSONObject entry = owned.getJSONObject(i);
            boolean deleted = false;
            if (entry.getLong("expires") <= platform.now()) {
                try {
                    Path path = Paths.get(entry.getString("path"));
                    if ("backup".equals(entry.getString("kind"))) {
                        ownedBackup(path);
                        if (!entry.getString("hash").equals(current.hash)) {
                            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) deleted = true;
                            else if (Keybox.sha256(Keybox.readFile(path, 128 * 1024)).equals(entry.getString("hash")))
                                deleted = Files.deleteIfExists(path);
                        }
                    } else if ("run".equals(entry.getString("kind"))) {
                        ownedRunPath(path);
                        JSONObject files = entry.getJSONObject("files");
                        boolean matches = !files.getString("keybox.xml").equals(current.hash);
                        if (state.has("pending") && path.toString().equals(state.getJSONObject("pending").getString("run"))) matches = false;
                        if (matches && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                            deleted = true;
                        } else {
                        try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                            for (Path child : children) {
                                String name = child.getFileName().toString();
                                if (!files.has(name) || !Keybox.sha256(Keybox.readFile(child, 2 * 1024 * 1024)).equals(files.getString(name))) matches = false;
                            }
                        }
                        if (matches) {
                            for (String name : Arrays.asList("keybox.xml", "private_key.pem", "csr.cbor", "cert_chain.der")) {
                                Path child = path.resolve(name); Keybox.safePath(child); Files.deleteIfExists(child);
                            }
                            Files.delete(path); deleted = true;
                        }
                        }
                    }
                } catch (Exception ignored) { /* Keep an altered or unsafe entry for manual review. */ }
            }
            if (!deleted) kept.put(entry);
        }
        state.put("owned", kept); save();
    }

    private void save() throws Exception {
        atomic(root.resolve("state.json"), state.toString().getBytes(StandardCharsets.UTF_8));
    }
    static void mkdir(Path path) throws Exception {
        Keybox.safePath(path); Files.createDirectories(path); permission(path, "rwx------");
    }
    static void permission(Path path, String mode) throws Exception {
        try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode)); }
        catch (UnsupportedOperationException ignored) { /* Host tests on Windows only. */ }
    }
    static void atomic(Path target, byte[] data) throws Exception {
        Keybox.safePath(target); Keybox.safePath(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".duck-renewal-", ".tmp");
        try {
            permission(temporary, "rw-------");
            try (FileOutputStream file = new FileOutputStream(temporary.toFile())) {
                file.write(data); file.getFD().sync();
            }
            FileMetadata.preserve(target, temporary);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            // Persist the rename too. Directory fsync is supported by Android's Linux provider.
            try (FileChannel directory = FileChannel.open(target.getParent(), StandardOpenOption.READ)) { directory.force(true); }
            catch (AccessDeniedException | UnsupportedOperationException ignored) { /* Windows host. */ }
        } finally { Files.deleteIfExists(temporary); }
    }
}
