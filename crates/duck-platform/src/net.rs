//! Shared HTTP client and download helpers.
//!
//! Android's system trust store is not readable through rustls, so on device the client
//! ships Mozilla's root set (`webpki-roots`) instead. Features download through the helpers
//! here rather than using the HTTP library directly, so replacing it touches one file.

use std::time::Duration;

use anyhow::{Context, Result, bail};
use reqwest::Client;
use rustls::{ClientConfig, RootCertStore};

const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(20);

/// Upper bound for downloads that are held in memory (keyboxes, lists, module zips).
pub const MAX_DOWNLOAD_BYTES: usize = 64 * 1024 * 1024;

pub fn http_client() -> Result<Client> {
    let builder = Client::builder()
        .connect_timeout(CONNECT_TIMEOUT)
        .timeout(REQUEST_TIMEOUT)
        .user_agent(concat!("duck-toolbox/", env!("CARGO_PKG_VERSION")));

    let builder = if cfg!(target_os = "android") {
        builder.tls_backend_preconfigured(embedded_rustls_config())
    } else {
        builder
    };

    builder.build().context("build HTTP client")
}

pub fn embedded_rustls_config() -> ClientConfig {
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());

    let mut config = ClientConfig::builder()
        .with_root_certificates(roots)
        .with_no_client_auth();
    config.alpn_protocols = vec![b"h2".to_vec(), b"http/1.1".to_vec()];
    config
}

/// Accepts only absolute `http(s)` URLs.
pub fn is_web_url(url: &str) -> bool {
    reqwest::Url::parse(url).is_ok_and(|parsed| matches!(parsed.scheme(), "http" | "https"))
}

/// Downloads `url`, failing on non-success statuses and bodies above `max_bytes`.
pub async fn fetch_bytes(url: &str, max_bytes: usize) -> Result<Vec<u8>> {
    if !is_web_url(url) {
        bail!("not an http(s) URL: {url}");
    }
    let mut response = http_client()?
        .get(url)
        .send()
        .await
        .with_context(|| format!("request {url}"))?;
    let status = response.status();
    if !status.is_success() {
        bail!("{url} returned HTTP {status}");
    }
    if response
        .content_length()
        .is_some_and(|length| length > max_bytes as u64)
    {
        bail!("{url} is larger than {max_bytes} bytes");
    }
    let mut body = Vec::new();
    while let Some(chunk) = response
        .chunk()
        .await
        .with_context(|| format!("read {url}"))?
    {
        if chunk.len() > max_bytes.saturating_sub(body.len()) {
            bail!("{url} is larger than {max_bytes} bytes");
        }
        body.extend_from_slice(&chunk);
    }
    Ok(body)
}

pub async fn fetch_text(url: &str, max_bytes: usize) -> Result<String> {
    let bytes = fetch_bytes(url, max_bytes).await?;
    String::from_utf8(bytes).with_context(|| format!("{url} is not UTF-8 text"))
}

/// Tries each URL in order (e.g. a GitHub raw URL, then a mirror) and returns the first
/// successful body together with the URL that served it.
pub async fn fetch_text_first(urls: &[String], max_bytes: usize) -> Result<(String, String)> {
    let mut errors = Vec::new();
    for url in urls {
        match fetch_text(url, max_bytes).await {
            Ok(body) => return Ok((body, url.clone())),
            Err(error) => errors.push(format!("{error:#}")),
        }
    }
    bail!("all sources failed: {}", errors.join("; "))
}

#[cfg(test)]
mod tests {
    use super::{embedded_rustls_config, is_web_url};

    #[tokio::test]
    async fn chunked_body_without_content_length_stops_at_limit() {
        use std::{
            io::{Read, Write},
            net::TcpListener,
            thread,
        };
        let server = TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}/large", server.local_addr().unwrap());
        let worker = thread::spawn(move || {
            let (mut socket, _) = server.accept().unwrap();
            let mut request = [0; 4096];
            let _ = socket.read(&mut request);
            let _ = socket.write_all(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n8\r\n12345678\r\n8\r\nABCDEFGH\r\n0\r\n\r\n");
        });
        let error = super::fetch_bytes(&url, 10).await.unwrap_err();
        assert!(error.to_string().contains("larger than 10"));
        worker.join().unwrap();
    }

    #[test]
    fn embedded_config_offers_h2_and_http11() {
        assert_eq!(
            embedded_rustls_config().alpn_protocols,
            vec![b"h2".to_vec(), b"http/1.1".to_vec()]
        );
    }

    #[test]
    fn only_web_urls_are_accepted() {
        assert!(is_web_url("https://example.com/keybox.xml"));
        assert!(is_web_url("http://example.com"));
        assert!(!is_web_url("file:///data/adb/keybox.xml"));
        assert!(!is_web_url("intent://scan/#Intent;end"));
        assert!(!is_web_url("not a url"));
    }
}
