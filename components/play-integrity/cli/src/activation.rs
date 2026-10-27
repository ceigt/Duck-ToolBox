// SPDX-License-Identifier: GPL-3.0-only
//! Durable activation requirements, separate from configuration and boot eligibility.
use crate::{Engine, files};
use anyhow::Result;
use serde_json::{Value, json};

fn boot(engine: &Engine) -> Result<String> {
    Ok(
        files::read(&engine.device("/proc/sys/kernel/random/boot_id"))?
            .trim()
            .into(),
    )
}
pub fn pending(engine: &Engine) -> Result<Value> {
    let saved = files::json_file(&engine.data.join("activation.json"))?;
    let current = boot(engine)?;
    // An unavailable boot identity never counts as evidence that a reboot happened.
    let active = saved.is_object()
        && saved.get("revision").is_some()
        && (current.is_empty() || saved["boot_id"] == "" || saved["boot_id"] == current);
    Ok(
        json!({"process_restart_required":active && saved["scope"]=="process",
        "reboot_required":active && saved["scope"]=="device","reason":if active {saved["reason"].clone()} else {Value::Null}}),
    )
}
pub fn require(engine: &Engine, revision: &str, device: bool) -> Result<()> {
    let existing = pending(engine)?;
    let device = device || existing["reboot_required"] == true;
    files::write_json(
        &engine.data.join("activation.json"),
        &json!({"revision":revision,
        "boot_id":boot(engine)?,"scope":if device {"device"} else {"process"},
        "reason":if device {"patch_policy"} else {"configuration"}}),
    )
}
pub fn restarted(engine: &Engine, revision: &str) -> Result<()> {
    let path = engine.data.join("activation.json");
    let saved = files::json_file(&path)?;
    if saved["revision"] == revision && saved["scope"] == "process" {
        std::fs::remove_file(path)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn process_restart_cannot_acknowledge_device_or_newer_requirements() {
        let root = tempfile::tempdir().unwrap();
        let engine = Engine {
            data: root.path().join("data"),
            module: root.path().join("module"),
            sysroot: root.path().join("sys"),
        };
        let path = engine.device("/proc/sys/kernel/random/boot_id");
        files::atomic(&path, b"boot-a").unwrap();
        require(&engine, "a", false).unwrap();
        restarted(&engine, "b").unwrap();
        assert_eq!(pending(&engine).unwrap()["process_restart_required"], true);
        require(&engine, "b", true).unwrap();
        require(&engine, "c", false).unwrap();
        restarted(&engine, "c").unwrap();
        assert_eq!(pending(&engine).unwrap()["reboot_required"], true);
        files::atomic(&path, b"boot-b").unwrap();
        assert_eq!(pending(&engine).unwrap()["reboot_required"], false);
    }
}
