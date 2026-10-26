Duck ToolBox v0.2.0-renewal.3 is a testing fork of upstream v0.2.0-Stable.

- Adds opt-in RKP renewal inside the existing module, with selectable V2 / V1 AIDL / V1 native interfaces.
- Checks expiry locally, renews within 24 hours of expiry, limits requests, and keeps pending certificates for retry without requesting again.
- Validates certificates locally, backs up before replacement, confirms TEESimulator loading from correlated logs, and restores the previous file on failure.
- Serializes native configuration edits, manual imports and renewal transactions.
- Preserves upstream MTK support and the independently licensed Play Integrity engine. The bundled engine is disabled on first install; existing settings are preserved.

Arm64, root and supported TEESimulator configuration are required. Renewal is off by default on first use. Existing renewal settings survive an update. Keep the Ace 3 attestation product correction module enabled if your phone needs it.

Built and checked on GitHub Actions. This release has not been installed or tested on a phone; real automatic issuance and long-term operation still need device testing. CI uses synthetic certificates and contains no device keys.

Install this ZIP as an update to duck-toolbox in your root manager and reboot. See LOCAL-RENEWAL.txt inside the ZIP for the Chinese guide, limits and rollback steps.

Audit fixes: safe external repository browsing; manual import/recovery compatibility; complete issued-chain validation; bounded network and local reads; shared settings locks; private URL diagnostics; isolated Qualcomm worker. Existing device keys and settings are retained on update.

The root WebUI never falls back to window.open when the system browser cannot be launched; an explicit error is shown.
