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

    static final Pattern STAGE = Pattern.compile("teesim_cfg_add_profile: staged profile '([^']+)' \\(mode=(patch|generation),");

    static boolean accepted(String text, String wanted) {
        return accepted(text, Collections.singletonMap("default", "patch\0" + wanted));
    }

    static boolean accepted(String text, Map<String, String> expected) {
        String thread = null, parsedId = null, committed = null;
        int parses = 0;
        Set<String> staged = new HashSet<>();
        Map<String, Boolean> acknowledgements = new HashMap<>();
        for (String line : text.split("\\r?\\n")) {
            if (line.contains("dropped") || line.contains("chatty") || line.contains("rate limit")) return false;
            Matcher frame = LINE.matcher(line);
            if (!frame.matches()) continue;
            String who = frame.group(1) + ":" + frame.group(2), message = frame.group(3);
            Matcher ack = ACK.matcher(message);
            if (ack.find()) {
                boolean ok = "true".equals(ack.group(2)) && Integer.toString(expected.size()).equals(ack.group(3)) && "0".equals(ack.group(4));
                if (acknowledgements.put(ack.group(1), ok) != null) return false;
                if (!ok) { parses = 0; staged.clear(); committed = null; }
            }
            Matcher parsed = PARSE.matcher(message);
            if (parsed.find()) {
                if (!who.equals(thread) || committed != null) { staged.clear(); parses = 0; }
                if (!parsed.group(1).equals(parsedId)) parses = 0;
                thread = who; parsedId = parsed.group(1); parses++; committed = null;
            }
            if (who.equals(thread)) {
                if (message.contains("failed") || message.contains("undecodable")) {
                    parses = 0; staged.clear(); committed = null;
                }
                Matcher stage = STAGE.matcher(message);
                if (stage.find()) {
                    String evidence = stage.group(2) + "\0" + parsedId;
                    if (parses != 2 || !evidence.equals(expected.get(stage.group(1))) || !staged.add(stage.group(1))) {
                        staged.clear(); committed = null;
                    }
                    parses = 0; parsedId = null;
                }
                Matcher commit = COMMIT.matcher(message);
                if (commit.find()) {
                    String count = Integer.toString(expected.size());
                    committed = !expected.isEmpty() && staged.equals(expected.keySet()) && count.equals(commit.group(2)) && count.equals(commit.group(3)) ? commit.group(1) : null;
                    parses = 0; staged.clear();
                }
            }
        }
        return committed != null && Boolean.TRUE.equals(acknowledgements.get(committed));
    }
}
