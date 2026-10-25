//! Offline material extraction. Input decoding, keybox validation, RKP identity checks
//! and private output ownership are independent of device acquisition and provisioning.

mod cli;
mod error;
mod files;
mod keybox;
mod pem;
mod seed;
mod xml;

pub use cli::FEATURE;
pub use keybox::validate_install;

#[cfg(test)]
mod tests;

mod legacy_rsa;
