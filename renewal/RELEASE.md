Duck ToolBox v0.2.1-renewal.1 is a testing fork of upstream v0.2.1-Stable.

- Adds opt-in RKP renewal inside the existing module, with selectable V2 / V1 AIDL / V1 native interfaces.
- Checks expiry locally, renews within 24 hours of expiry, limits requests, and keeps pending certificates for retry without requesting again.
- Validates certificates locally, backs up before replacement, confirms TEESimulator loading from correlated logs, and restores the previous file on failure.
- Serializes native configuration edits, manual imports and renewal transactions.
- Preserves upstream MTK support and the independently licensed Play Integrity engine. The bundled engine is disabled on first install; existing settings are preserved.

Arm64, root and supported TEESimulator or ITxiao6666/OhMyKeymint configuration are required. Renewal is off by default on first use. Existing renewal settings survive an update; changing backend requires enable confirmation again. Keep the Ace 3 attestation product correction module enabled if your phone needs it.

Version 0.2.0-renewal.3 was installed and rebooted on an Ace 3 on 2026-10-26; current-keybox loading passed, and the user confirmed BASIC, DEVICE and STRONG. Check each version's device test record for its own installation result. Real due-time automatic issuance, automatic replacement and long-term operation still need device testing. CI uses synthetic certificates and contains no device keys.

Install this ZIP as an update to duck-toolbox in your root manager and reboot. See LOCAL-RENEWAL.txt inside the ZIP for the Chinese guide, limits and rollback steps.

Audit fixes: safe external repository browsing; manual import/recovery compatibility; complete issued-chain validation; bounded network and local reads; shared settings locks; private URL diagnostics; isolated Qualcomm worker. Existing device keys and settings are retained on update.

The root WebUI never falls back to window.open when the system browser cannot be launched; an explicit error is shown.

Follow-up fixes: invalid download URLs cannot expose credentials in errors or logs; manual imports accept safe relative target filenames, including uppercase extensions and extensionless names. Targets outside the daemon's filename watcher trigger a reload by atomically republishing unchanged configuration. Path safety and certificate validation remain enforced.

OhMyKeymint renewal support (ITxiao6666 code >= 222): fixed target, explicit backend-bound consent, shared writer lock, metadata-preserving atomic replacement, watcher-log evidence with stable process/configuration/file checks and file rollback. Migration never reuses TEESimulator consent or redirects its pending recovery to OMK.

OMK changes can retire attestation keys bound to the old Keybox identity; affected apps may need enrollment again. Restoring the Keybox file cannot undo that retirement. The enable dialog explains this before future automatic replacements are authorized. OMK has no candidate-digest ACK; watcher evidence is not an independent attestation or Play Integrity verdict. Do not modify/import with another root tool during a Duck transaction.

Includes upstream 0.2.1 Pixel metadata, partition patch previews, durable activation diagnostics and language additions. The unreleased kernel-module manager is excluded. Existing audit fixes and the disabled-on-first-install bundled PIF policy are retained.

Pre-release validation on 2026-10-27: 117 host and 118 Android synthetic assertions passed; 275 focused UI/locale tests, type checking and focused lint passed. The connected Ace 3 runs OMK 1.3.5 code 222; its current certificate validates locally. A disposable Android replacement retained uid/gid and SELinux context. The active OMK Keybox and both configuration files remained hash-identical. No live replacement or issuance was performed for this version.
