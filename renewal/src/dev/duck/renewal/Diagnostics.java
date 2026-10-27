package dev.duck.renewal;

import java.io.IOException;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.util.*;

/** Only fixed codes cross the UI boundary; exception text may contain a child response. */
final class Diagnostics {
    static final Set<String> SAFE = new HashSet<>(Arrays.asList(
        "operation_busy", "protocol_required", "pending_protocol_locked", "rollback_required",
        "target_unsupported", "backend_changed", "clock_invalid", "no_pending", "invalid_invocation", "unknown_command",
        "unsafe_log", "unsafe_or_oversized_file", "oversized_file", "unowned_run_path", "unowned_backup",
        "backup_changed", "private_key_mismatch", "untrusted_root", "certificate_not_yet_valid",
        "trust_roots_missing", "single_ec_keybox_required", "invalid_chain_length", "invalid_key_encoding",
        "invalid_keybox", "xml_dtd", "external_entity", "p256_required", "wrong_issuer", "wrong_keybox_path",
        "new_certificate_not_better", "unexpected_run_file", "submission_unknown", "target_changed"));
    static String code(Exception failure) {
        if (failure instanceof IOException && SAFE.contains(failure.getMessage())) return failure.getMessage();
        if (failure instanceof AccessDeniedException || failure instanceof SecurityException) return "access_denied";
        if (failure instanceof NoSuchFileException) return "file_missing";
        if (failure instanceof GeneralSecurityException || failure instanceof org.xml.sax.SAXException
                || failure instanceof IllegalArgumentException) return "certificate_invalid";
        if (failure instanceof org.json.JSONException) return "state_or_response_invalid";
        if (failure instanceof IOException) return "storage_io_failed";
        return "local_operation_failed";
    }
}
