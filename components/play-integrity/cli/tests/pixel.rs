// SPDX-License-Identifier: GPL-3.0-only
use serde_json::{Value, json};
use std::{
    io::Write,
    path::Path,
    process::{Command, Stdio},
    time::{SystemTime, UNIX_EPOCH},
};
fn request(root: &Path, operation: &str, input: Value) -> Value {
    let mut child = Command::new(env!("CARGO_BIN_EXE_pif-engine"))
        .args(["request", operation])
        .env("PIF_ENGINE_DATA", root.join("data"))
        .env("PIF_ENGINE_SYSROOT", root.join("sys"))
        .env("PIF_ENGINE_MODULE", root.join("module"))
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .spawn()
        .unwrap();
    child
        .stdin
        .take()
        .unwrap()
        .write_all(input.to_string().as_bytes())
        .unwrap();
    serde_json::from_slice(&child.wait_with_output().unwrap().stdout).unwrap()
}
#[test]
fn pixel_preview_and_snapshot_preserve_power_and_report_unknown_patch() {
    let dir = tempfile::tempdir().unwrap();
    let root = dir.path();
    assert_eq!(
        request(root, "pixel-devices", json!({}))["data"]["default"],
        "bluejay"
    );
    request(root, "disable", json!({}));
    std::fs::write(root.join("data/pixel-bluejay.json"),json!({"source":"https://flash.android.com/","device":"bluejay","fetched":SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs(),"builds":[{"id":"ID.1","incremental":"123","release":"16","fingerprint":"google/bluejay/bluejay:16/ID.1/123:user/release-keys"}]}).to_string()).unwrap();
    let before = request(root, "config", json!({}));
    let preview = request(root, "pixel-preview", json!({"build":"ID.1"}));
    assert_eq!(preview["ok"], true, "{preview}");
    let candidate = &preview["data"];
    assert_eq!(candidate["entries"]["MODEL"], "Pixel 6a");
    assert!(candidate["entries"].get("SECURITY_PATCH").is_none());
    assert!(candidate["suggested_patch"].is_null());
    let saved = request(
        root,
        "profile-save",
        json!({"name":"Release","content":candidate["json"],"provenance":candidate["provenance"]}),
    );
    assert_eq!(saved["ok"], true, "{saved}");
    assert_eq!(saved["data"]["profiles"][0]["provenance"]["build"], "ID.1");
    assert_eq!(request(root, "config", json!({})), before);
    assert_eq!(request(root, "power", json!({}))["data"]["enabled"], false);
    assert_eq!(
        request(root, "pixel-preview", json!({"build":"other"}))["ok"],
        false
    );
    let cache_path = root.join("data/pixel-bluejay.json");
    let mut cache: Value =
        serde_json::from_str(&std::fs::read_to_string(&cache_path).unwrap()).unwrap();
    cache["builds"][0]["release"] = json!("15");
    std::fs::write(&cache_path, cache.to_string()).unwrap();
    assert_eq!(
        request(root, "pixel-preview", json!({"build":"ID.1"}))["ok"],
        false
    );
    let mut corrupt = candidate["provenance"].clone();
    corrupt["build"] = json!("wrong");
    assert_eq!(
        request(
            root,
            "profile-save",
            json!({"name":"Wrong","content":candidate["json"],"provenance":corrupt})
        )["ok"],
        false
    );
}
#[test]
fn activation_and_sanitized_report_survive_independent_requests() {
    let dir = tempfile::tempdir().unwrap();
    let root = dir.path();
    let p = request(root, "preview", json!({"content":"MODEL=Test"}));
    let mut apply = p["data"].clone();
    apply["content"] = apply["json"].clone();
    apply["format"] = json!("json");
    assert_eq!(request(root, "apply", apply)["ok"], true);
    assert_eq!(
        request(root, "status", json!({}))["data"]["process_restart_required"],
        true
    );
    let revision = request(root, "config", json!({}))["data"]["revision"].clone();
    let result = request(
        root,
        "record-import",
        json!({"revision":revision,"report":{"requestDetails":{"requestPackageName":"example.checker","nonce":"do-not-store"},"deviceIntegrity":{"deviceRecognitionVerdict":["MEETS_DEVICE_INTEGRITY"]},"accountDetails":{"appLicensingVerdict":"UNLICENSED"}}}),
    );
    assert_eq!(result["ok"], true, "{result}");
    assert_eq!(
        result["data"]["records"][0]["results"],
        json!(["omitted", "pass", "omitted"])
    );
    assert!(
        !std::fs::read_to_string(root.join("data/test-records.json"))
            .unwrap()
            .contains("do-not-store")
    );
    assert_eq!(
        request(
            root,
            "record-import",
            json!({"revision":"old","report":{"requestDetails":{"requestPackageName":"example.checker"},"deviceIntegrity":{}}})
        )["ok"],
        false
    );
}
