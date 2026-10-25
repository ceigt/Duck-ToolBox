package dev.duck.renewal;

import java.util.*;
import java.util.regex.*;

/** TEESimulator fcf1738: parse/build/commit execute serially on the native control thread.
 * Require both TEE and StrongBox parses, staging, that thread's commit epoch, and its ACK.
 * Missing/rotated/interleaved evidence is failure, never permission to guess success.
 */
final class LoadProof {
    static final Pattern LINE = Pattern.compile("^\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d+\\s+(\\d+)\\s+(\\d+)\\s+[VDIWEF]\\s+(.+)$");
    static final Pattern PARSE = Pattern.compile("CertSignInfo::new: keybox parsed[^\\r\\n]*\\bid=([a-f0-9]{16})\\b");
    static final Pattern COMMIT = Pattern.compile("HandleConnection: applied config epoch=(\\d+) profiles=(\\d+)/(\\d+)");
    static final Pattern ACK = Pattern.compile("Control: ack epoch=(\\d+) ok=(true|false) applied=(\\d+) failed=(\\d+)");

    static boolean accepted(String text, String wanted) {
        String thread = null;
        int parses = 0;
        boolean staged = false;
        String committed = null;
        Map<String, Boolean> acknowledgements = new HashMap<>();
        for (String line : text.split("\\r?\\n")) {
            // Log loss invalidates the whole observation window.
            if (line.contains("dropped") || line.contains("chatty") || line.contains("rate limit")) return false;
            Matcher frame = LINE.matcher(line);
            if (!frame.matches()) continue;
            String who = frame.group(1) + ":" + frame.group(2), message = frame.group(3);
            Matcher ack = ACK.matcher(message);
            if (ack.find()) {
                boolean ok = "true".equals(ack.group(2)) && "1".equals(ack.group(3)) && "0".equals(ack.group(4));
                if (acknowledgements.put(ack.group(1), ok) != null) return false;
                if (!ok) { parses = 0; staged = false; committed = null; }
            }
            Matcher parsed = PARSE.matcher(message);
            if (parsed.find()) {
                if (!wanted.equals(parsed.group(1))) { parses = 0; staged = false; committed = null; thread = null; }
                else {
                    if (!who.equals(thread) || staged || committed != null) parses = 0;
                    thread = who; parses++; staged = false; committed = null;
                }
            }
            if (who.equals(thread)) {
                if (message.contains("failed") || message.contains("undecodable")) {
                    parses = 0; staged = false; committed = null;
                }
                if (message.contains("teesim_cfg_add_profile: staged profile")) {
                    staged = parses == 2 && message.contains("staged profile 'default' (mode=patch,");
                    if (!staged) parses = 0;
                }
                Matcher commit = COMMIT.matcher(message);
                if (commit.find()) {
                    committed = staged && "1".equals(commit.group(2)) && "1".equals(commit.group(3)) ? commit.group(1) : null;
                    parses = 0; staged = false;
                }
            }
        }
        return committed != null && Boolean.TRUE.equals(acknowledgements.get(committed));
    }
}
