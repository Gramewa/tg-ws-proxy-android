# Security Policy

## Supported Versions

Only the latest release of **TG WS Proxy Android** on GitHub Releases receives security updates.

## Built-in Hardening

- **Localhost binding by default:** The MTProto proxy listens on `127.0.0.1:1443` by default, isolating access to apps on the same device. Selecting `0.0.0.0` shows an explicit warning in Settings.
- **No cloud backups of credentials:** `android:allowBackup="false"` prevents Android backup extraction of the 32-hex proxy secret and configuration.
- **Zero cleartext HTTP client traffic:** `android:usesCleartextTraffic="false"` is enforced in `AndroidManifest.xml`; update API calls and `cfproxy-domains.txt` refreshes verify HTTPS certificates and hostnames. (The optional port `80` fallback uses raw TCP `Socket` WebSockets matching upstream `--no-secure`.)
- **Secret redaction:** Logs (`ProxyLogger` / `DomainCensor`) strip `secret=` parameters and mask non-Telegram domains before writing to the circular buffer or Android `logcat`. Copied `tg://proxy` links are marked `ClipDescription.EXTRA_IS_SENSITIVE` on Android 13+.
- **SSRF & resource guards:** Fake TLS domain probing and fallback proxying resolve and require public unicast IP addresses, enforce socket timeouts, and bound incoming TLS record / splitter buffer allocations and concurrent client connections.
- **Trusted update links:** Release update checks only accept HTTPS download URLs hosted on `github.com/Gramewa/tg-ws-proxy-android/` or official GitHub release asset CDNs, without declaring `REQUEST_INSTALL_PACKAGES`.

## Reporting a Vulnerability

Please open a private GitHub Security Advisory on `Gramewa/tg-ws-proxy-android` or report non-sensitive bugs via [GitHub Issues](https://github.com/Gramewa/tg-ws-proxy-android/issues).
