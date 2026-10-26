//! Files the tools wrote to the shared outputs directory.

use anyhow::{Context as _, Result};
use duck_core::{AppPaths, fs::modified_unix};
use serde::Serialize;
use std::{cmp::Reverse, collections::BinaryHeap};

#[derive(Debug, Serialize)]
pub struct ArtifactFile {
    pub name: String,
    pub path: String,
    pub size: u64,
    pub modified_unix: u64,
}

#[derive(Debug, Serialize)]
pub struct ArtifactsData {
    pub outputs: Vec<ArtifactFile>,
    pub outputs_dir: String,
    pub profile_path: String,
    pub profile_secrets_path: String,
    pub log_path: String,
}

pub fn list(paths: &AppPaths) -> Result<ArtifactsData> {
    paths.ensure_runtime_dirs()?;
    // The dashboard consumes twelve items. Keep only those while walking history.
    let mut latest = BinaryHeap::new();
    let mut directories = vec![paths.outputs_dir.clone()];
    while let Some(directory) = directories.pop() {
        for entry in std::fs::read_dir(&directory)? {
            let entry = entry?;
            let kind = entry.file_type()?;
            if kind.is_dir() {
                directories.push(entry.path());
                continue;
            }
            if !kind.is_file() {
                continue;
            }
            let metadata = entry.metadata().context("read output metadata")?;
            latest.push(Reverse((
                modified_unix(&metadata),
                entry.path(),
                metadata.len(),
            )));
            if latest.len() > 12 {
                latest.pop();
            }
        }
    }
    let mut outputs = latest
        .into_iter()
        .map(|Reverse((modified, path, size))| ArtifactFile {
            name: path
                .strip_prefix(&paths.outputs_dir)
                .unwrap_or(&path)
                .display()
                .to_string(),
            path: path.display().to_string(),
            size,
            modified_unix: modified,
        })
        .collect::<Vec<_>>();

    outputs.sort_by_key(|file| std::cmp::Reverse(file.modified_unix));

    Ok(ArtifactsData {
        outputs,
        outputs_dir: paths.outputs_dir.display().to_string(),
        profile_path: paths.profile_path.display().to_string(),
        profile_secrets_path: paths.profile_secrets_path.display().to_string(),
        log_path: paths.log_path.display().to_string(),
    })
}

#[cfg(test)]
mod tests {
    #[test]
    fn dashboard_returns_only_twelve_output_files() {
        let root = std::env::temp_dir().join(format!("duck-artifacts-{}", std::process::id()));
        let paths = duck_core::AppPaths::for_root(&root, &root);
        paths.ensure_runtime_dirs().unwrap();
        for index in 0..40 {
            std::fs::write(paths.outputs_dir.join(format!("{index}.txt")), "x").unwrap();
        }
        assert_eq!(super::list(&paths).unwrap().outputs.len(), 12);
        std::fs::remove_dir_all(root).unwrap();
    }
}
