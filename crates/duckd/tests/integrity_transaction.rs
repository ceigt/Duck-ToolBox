#![cfg(all(unix, feature = "play-integrity"))]
use serde_json::{Value, json};
use std::{
    fs,
    io::Write,
    process::{Command, Stdio},
    time::{Duration, Instant},
};

#[test]
fn process_termination_leaves_a_confirmable_recovery_record() {
    use std::os::unix::fs::PermissionsExt;
    let root = tempfile::tempdir().unwrap();
    fs::create_dir_all(root.path().join("play-integrity")).unwrap();
    fs::create_dir(root.path().join("bin")).unwrap();
    fs::write(root.path().join("module.prop"), "id=duck-toolbox\n").unwrap();
    let engine = root.path().join("play-integrity/pif-engine");
    fs::write(
        &engine,
        r#"#!/bin/sh
base=$(dirname "$0")
cat >/dev/null
case "$2" in
 power) printf '{"ok":true,"api":1,"data":{"stop_revision":"initial"}}' ;;
 prepare) printf '{"ok":true,"api":1,"data":{"backup":"fixture-backup"}}' ;;
 apply)
   printf changed > "$base/state"
   touch "$base/blocked"
   while [ -f "$base/blocked" ]; do sleep 0.01; done
   printf '{"ok":true,"api":1,"data":{"backup":"fixture-backup"}}'
   ;;
 restore)
   rm -f "$base/state"
   printf '{"ok":true,"api":1,"data":{"restart_required":true}}'
   ;;
esac
"#,
    )
    .unwrap();
    fs::set_permissions(&engine, fs::Permissions::from_mode(0o700)).unwrap();
    let command = || {
        let mut cmd = Command::new(env!("CARGO_BIN_EXE_duckd"));
        cmd.env("DUCK_TOOLBOX_ROOT", root.path())
            .env("DUCK_TOOLBOX_DATA_ROOT", root.path().join("data"))
            .env("DUCK_TOOLBOX_SYSROOT", root.path().join("sys"));
        cmd
    };
    let mut child = command()
        .args(["integrity-transaction", "apply", "--stdin-json"])
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .spawn()
        .unwrap();
    child
        .stdin
        .take()
        .unwrap()
        .write_all(br#"{"engine":{"content":"MODEL=A","revision":"fixture"}}"#)
        .unwrap();
    let blocked = root.path().join("play-integrity/blocked");
    let deadline = Instant::now() + Duration::from_secs(5);
    while !blocked.exists() {
        assert!(Instant::now() < deadline, "apply did not reach publication");
        std::thread::sleep(Duration::from_millis(10));
    }
    child.kill().unwrap();
    child.wait().unwrap();
    fs::remove_file(blocked).unwrap();
    let journal_path = root
        .path()
        .join("data/var/integrity-transaction/journal.json");
    let journal: Value = serde_json::from_str(&fs::read_to_string(&journal_path).unwrap()).unwrap();
    assert_eq!(journal["phase"], "applying");
    assert_eq!(journal["engine_started"], true);
    let status: Value = serde_json::from_slice(
        &command()
            .args(["integrity-transaction", "status"])
            .output()
            .unwrap()
            .stdout,
    )
    .unwrap();
    assert_eq!(status["data"]["pending"], true);
    let mut recover = command()
        .args(["integrity-transaction", "recover", "--stdin-json"])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .spawn()
        .unwrap();
    serde_json::to_writer(recover.stdin.take().unwrap(), &json!({"confirmed":true})).unwrap();
    let output = recover.wait_with_output().unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stdout)
    );
    assert!(!root.path().join("play-integrity/state").exists());
    let journal: Value = serde_json::from_str(&fs::read_to_string(journal_path).unwrap()).unwrap();
    assert_eq!(journal["phase"], "restored");
}

#[test]
fn a_new_stop_during_publication_rolls_back_the_configuration_transaction() {
    use std::os::unix::fs::PermissionsExt;
    let root = tempfile::tempdir().unwrap();
    fs::create_dir_all(root.path().join("play-integrity")).unwrap();
    fs::create_dir(root.path().join("bin")).unwrap();
    fs::write(root.path().join("module.prop"), "id=duck-toolbox\n").unwrap();
    let engine = root.path().join("play-integrity/pif-engine");
    fs::write(&engine,r#"#!/bin/sh
base=${0%/*}
cat >/dev/null
case "$2" in
power)
 revision=initial; [ ! -f "$base/stopped" ] || revision=later
 printf '{"ok":true,"api":1,"data":{"stop_revision":"%s"}}' "$revision" ;;
prepare) printf '{"ok":true,"api":1,"data":{"backup":"fixture-backup"}}' ;;
apply)
 touch "$base/config" "$base/stopped"
 printf '{"ok":true,"api":1,"data":{"backup":"fixture-backup","config":{},"restart_required":true}}' ;;
restore)
 rm -f "$base/config"
 printf '{"ok":true,"api":1,"data":{"restart_required":true}}' ;;
esac
"#).unwrap();
    fs::set_permissions(&engine, fs::Permissions::from_mode(0o700)).unwrap();
    let mut child = Command::new(env!("CARGO_BIN_EXE_duckd"))
        .env("DUCK_TOOLBOX_ROOT", root.path())
        .env("DUCK_TOOLBOX_DATA_ROOT", root.path().join("data"))
        .args(["integrity-transaction", "apply", "--stdin-json"])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .spawn()
        .unwrap();
    serde_json::to_writer(
        child.stdin.take().unwrap(),
        &json!({"engine":{"content":"MODEL=A","revision":"fixture"}}),
    )
    .unwrap();
    let output = child.wait_with_output().unwrap();
    assert!(!output.status.success());
    assert!(String::from_utf8_lossy(&output.stdout).contains("engine was stopped"));
    assert!(!root.path().join("play-integrity/config").exists());
    let journal: Value = serde_json::from_slice(
        &fs::read(
            root.path()
                .join("data/var/integrity-transaction/journal.json"),
        )
        .unwrap(),
    )
    .unwrap();
    assert_eq!(journal["phase"], "restored");
}

#[test]
#[cfg(feature = "tricky-store")]
fn keystore_recovery_is_independent_of_a_failed_engine_activation_marker() {
    use std::os::unix::fs::PermissionsExt;
    let root = tempfile::tempdir().unwrap();
    let ctx = duck_core::Context {
        paths: duck_core::AppPaths::for_root(root.path(), &root.path().join("data")),
        sysroot: duck_core::Sysroot::new(root.path().join("sys")),
    };
    let module = ctx.sysroot.path("/data/adb/modules/oh_my_keymint");
    fs::create_dir_all(&module).unwrap();
    fs::write(
        module.join("module.prop"),
        "id=oh_my_keymint\nversionCode=1\n",
    )
    .unwrap();
    let config = ctx.sysroot.path("/data/misc/keystore/omk/config.toml");
    fs::create_dir_all(config.parent().unwrap()).unwrap();
    let original = "[trust]\nboot_patchlevel=\"2026-09-05\"\n";
    fs::write(&config, original).unwrap();
    let mut request = duck_tricky_store::patch::Request {
        patch: "2026-10-05".into(),
        ..Default::default()
    };
    request.revision = duck_tricky_store::patch::preview(&ctx, &request).unwrap()["revision"]
        .as_str()
        .unwrap()
        .into();
    let prepared = duck_tricky_store::patch::prepare(&ctx, &request).unwrap();
    let token = prepared["backup"].as_str().unwrap();
    duck_tricky_store::patch::commit_prepared(&ctx, token).unwrap();
    let journal = ctx.paths.var_dir.join("integrity-transaction/journal.json");
    fs::create_dir_all(journal.parent().unwrap()).unwrap();
    fs::write(&journal,json!({"api":1,"phase":"applying","engine_backup":"unused","patch_backup":token,"engine_started":false,"patch_started":true,"restart_required":false}).to_string()).unwrap();
    fs::create_dir_all(root.path().join("bin")).unwrap();
    fs::write(root.path().join("module.prop"), "id=duck-toolbox\n").unwrap();
    let engine = root.path().join("play-integrity/pif-engine");
    fs::create_dir_all(engine.parent().unwrap()).unwrap();
    fs::write(&engine,"#!/bin/sh\ncat >/dev/null\nprintf '{\"ok\":false,\"api\":1,\"error\":{\"message\":\"marker unavailable\"}}'\n").unwrap();
    fs::set_permissions(&engine, fs::Permissions::from_mode(0o700)).unwrap();
    let recover = || {
        let mut child = Command::new(env!("CARGO_BIN_EXE_duckd"))
            .env("DUCK_TOOLBOX_ROOT", root.path())
            .env("DUCK_TOOLBOX_DATA_ROOT", root.path().join("data"))
            .env("DUCK_TOOLBOX_SYSROOT", root.path().join("sys"))
            .args(["integrity-transaction", "recover", "--stdin-json"])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .spawn()
            .unwrap();
        serde_json::to_writer(child.stdin.take().unwrap(), &json!({"confirmed":true})).unwrap();
        child.wait_with_output().unwrap()
    };
    let first = recover();
    assert!(
        !first.status.success(),
        "{}",
        String::from_utf8_lossy(&first.stdout)
    );
    assert_eq!(fs::read_to_string(&config).unwrap(), original);
    assert_eq!(
        serde_json::from_str::<Value>(&fs::read_to_string(&journal).unwrap()).unwrap()["patch_started"],
        true
    );
    fs::write(
        &engine,
        "#!/bin/sh\ncat >/dev/null\nprintf '{\"ok\":true,\"api\":1,\"data\":{}}'\n",
    )
    .unwrap();
    let second = recover();
    assert!(
        second.status.success(),
        "{}",
        String::from_utf8_lossy(&second.stdout)
    );
    assert_eq!(
        serde_json::from_str::<Value>(&fs::read_to_string(&journal).unwrap()).unwrap()["phase"],
        "restored"
    );
}
