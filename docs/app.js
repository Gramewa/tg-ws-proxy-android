(() => {
  const REPO = "Gramewa/tg-ws-proxy-android";
  const FALLBACK_RELEASE = {
    version: "v1.3.1",
    sizeMb: "1.9",
    apkUrl: "https://github.com/Gramewa/tg-ws-proxy-android/releases/download/v1.3.1/tg-ws-proxy-android-v1.3.1.apk",
    releaseUrl: "https://github.com/Gramewa/tg-ws-proxy-android/releases/latest",
    sha256: "14f5dea6d71ec7167aab845ed72329724b09ab2586054d739b397db197ad9e61"
  };

  const I18N = {
    ru: {
      title: "TG WS Proxy для Android — локальный прокси для Telegram",
      navSetup: "Подключение",
      navEngine: "Как устроен",
      navAudit: "Что внутри APK",
      heroTitle: "Локальный прокси для Telegram без VPN",
      heroLead:
        "Поднимает MTProto WebSocket-прокси прямо на телефоне по протоколу Flowseal/tg-ws-proxy. Чистый Kotlin (~1.9 МБ), без root и чужих серверов, с автопереходом на HTTP :80 за 750 мс.",
      downloadBtn: "Скачать APK",
      obtainiumBtn: "Obtainium",
      metaAndroid: "Android 8.0+ · Universal APK",
      shaLabel: "SHA-256",
      shaCopied: "Скопировано",
      routeUpstream: "CF :80 (авто)",
      tabSimple: "Простой",
      tabPro: "Pro",
      tabLight: "Светлая",
      capSimple: "Простой режим: запуск, живой маршрут и проверка пинга",
      capPro: "Pro-режим: счётчики соединений, HTTP/2 и маршрутизация",
      capLight: "Настройки: DoH (1.1.1.1), чистый IP Cloudflare и приоритет IP",
      setupTitle: "Как подключить",
      setupSub: "Обновления ставятся поверх прошлой версии без потери настроек.",
      step1Title: "Установите APK",
      step1Body: "Один универсальный файл для Android 8.0+ — выбирать архитектуру процессора не нужно.",
      step2Title: "Запустите прокси",
      step2Body: "Нажмите кнопку запуска на главном экране — откроется локальный MTProto-порт 127.0.0.1:1443.",
      step3Title: "Откройте в Telegram",
      step3Body: "Кнопка «Открыть в Telegram» передаст прокси через tg://proxy в официальный клиент, AyuGram, NekoGram или Plus Messenger.",
      troubleSummary: "Если плохо грузятся медиа или прокси останавливается в фоне",
      trouble1: "При сбое TLS <code>:443</code> подождите ~1 секунду — прокси сам перейдёт на <strong>CF :80 (авто)</strong>.",
      trouble2: "Чтобы сразу ходить через 80-й порт, включите тумблер <strong>Резервный порт 80</strong> (внутреннее шифрование MTProto сохраняется).",
      trouble3: "В сложных сетях откройте <strong>Pro → Настройки</strong>: включите <strong>DoH (1.1.1.1)</strong>, укажите чистый IP Cloudflare или нажмите <strong>Очистить (всё в CF)</strong> в списке DC.",
      trouble4: "Чтобы Android не усыплял сервис при выключенном экране, нажмите кнопку снятия ограничений батареи на главном экране.",
      engineTitle: "Как устроен прокси",
      engineSub: "Полная совместимость с Flowseal/tg-ws-proxy v1.12.0+ без VPN-слота Android.",
      s1Key: "~1.9 МБ APK",
      s1Title: "Чистый Kotlin вместо тяжёлых обёрток",
      s1Body: "Без встроенных Rust, Python или Go-бинарников. Zero-copy криптодвижок AES-CTR, прямой SocketChannel I/O и низкий расход памяти.",
      s2Key: "~750 мс · :80",
      s2Title: "Автопереход на HTTP :80 и HTTP/2 для медиа",
      s2Body: "Если TLS на 443-м порту режется, параллельная гонка переключает Cloudflare Proxy и ваши CF Workers на 80-й порт меньше чем за секунду. Шифрование MTProto сохраняется.",
      s3Key: "DoH · Fake TLS",
      s3Title: "Сетевой стек под сложные провайдеры",
      s3Body: "Встроенный DNS-over-HTTPS (1.1.1.1 / 8.8.8.8 / 9.9.9.9), Happy Eyeballs, опциональный чистый IP Cloudflare, балансировка доменов по EWMA и Fake TLS (ee-секрет с X25519MLKEM768).",
      s4Key: "Wi-Fi ↔ LTE",
      s4Title: "Сделано под поведение Android в фоне",
      s4Body: "Сброс зависших сокетов при смене сети или VPN, плитка в шторке с активным маршрутом, hot-reload настроек без перезапуска сокета и JSON-экспорт конфига.",
      auditTitle: "Что внутри APK",
      auditSub: "Все ограничения и сборку можно проверить прямо по исходникам.",
      a1Key: "Адрес по умолчанию",
      a1Val: "Только <code>127.0.0.1:1443</code> внутри устройства. При смене на <code>0.0.0.0</code> показывается предупреждение.",
      a2Key: "Зависимости",
      a2Val: "Только AndroidX, Compose, Kotlin Coroutines и OkHttp. Без рекламы, аналитики и Google Play Services.",
      a3Key: "Права и бэкап",
      a3Val: "<code>allowBackup=\"false\"</code>, секреты и домены вырезаются из логов, право <code>REQUEST_INSTALL_PACKAGES</code> не запрашивается.",
      a4Key: "Сборка релизов",
      a4Val: "Публичный GitHub Actions workflow с файлом <code>SHA256SUMS.txt</code> и отключённым блоком <code>dependenciesInfo</code>.",
      allReleases: "Все релизы",
      footerNote: "MIT License · Протокол Flowseal/tg-ws-proxy",
      langBtn: "EN"
    },
    en: {
      title: "TG WS Proxy for Android — Local Telegram Proxy",
      navSetup: "Setup",
      navEngine: "Under the hood",
      navAudit: "Inside the APK",
      heroTitle: "Local Telegram proxy without VPN",
      heroLead:
        "Runs an MTProto WebSocket proxy directly on your phone using the Flowseal/tg-ws-proxy protocol. Pure Kotlin (~1.9 MB), no root or third-party servers, with 750 ms auto fallback to HTTP :80.",
      downloadBtn: "Download APK",
      obtainiumBtn: "Obtainium",
      metaAndroid: "Android 8.0+ · Universal APK",
      shaLabel: "SHA-256",
      shaCopied: "Copied",
      routeUpstream: "CF :80 (auto)",
      tabSimple: "Simple",
      tabPro: "Pro",
      tabLight: "Light",
      capSimple: "Simple mode: one-tap start, live route strip, and latency check",
      capPro: "Pro mode: connection counters, HTTP/2 stats, and routing toggles",
      capLight: "Settings: built-in DoH (1.1.1.1), Clean Cloudflare IP, IPv4/IPv6",
      setupTitle: "Setup",
      setupSub: "Updates install in place without resetting your configuration.",
      step1Title: "Install the APK",
      step1Body: "One universal build for Android 8.0+ — no need to pick an ABI.",
      step2Title: "Start the proxy",
      step2Body: "Tap the power button on the home screen to open the local MTProto listener on 127.0.0.1:1443.",
      step3Title: "Open in Telegram",
      step3Body: "Tap “Open in Telegram” to pass the tg://proxy link into official Telegram, AyuGram, NekoGram, or Plus Messenger.",
      troubleSummary: "If media loads slowly or Android pauses the proxy in the background",
      trouble1: "When TLS <code>:443</code> fails, wait ~1 second — the proxy switches to <strong>CF :80 (auto)</strong> on its own.",
      trouble2: "To always use port 80 without waiting for TLS checks, enable <strong>Fallback port 80</strong> (MTProto encryption stays end-to-end).",
      trouble3: "On stricter networks, open <strong>Pro → Settings</strong>: turn on <strong>DoH (1.1.1.1)</strong>, set a Clean Cloudflare IP, or clear DC overrides (<strong>all via CF</strong>).",
      trouble4: "Use the battery-optimization button on the main screen so Android keeps the service alive with the screen off.",
      engineTitle: "Under the hood",
      engineSub: "Full Flowseal/tg-ws-proxy v1.12.0+ protocol parity without taking Android's VPN slot.",
      s1Key: "~1.9 MB APK",
      s1Title: "Pure Kotlin, no bundled runtime",
      s1Body: "No embedded Rust, Python, or Go binaries. Zero-copy AES-CTR cipher path, direct SocketChannel I/O, and low memory overhead.",
      s2Key: "~750 ms · :80",
      s2Title: "Automatic HTTP :80 fallback & HTTP/2 media",
      s2Body: "When port 443 TLS is blocked, a staggered race moves both Cloudflare Proxy and custom CF Workers to port 80 in under a second while keeping MTProto encryption intact.",
      s3Key: "DoH · Fake TLS",
      s3Title: "Built for restrictive mobile networks",
      s3Body: "Built-in DNS-over-HTTPS (1.1.1.1 / 8.8.8.8 / 9.9.9.9), Happy Eyeballs, optional Clean Cloudflare IP, EWMA domain balancing, and Fake TLS (ee secret with X25519MLKEM768).",
      s4Key: "Wi-Fi ↔ LTE",
      s4Title: "Tuned for Android background behavior",
      s4Body: "Resets stale sockets across Wi-Fi/LTE/VPN handoffs, Quick Settings tile with active route status, hot-reload settings without restarting the socket, and JSON config export.",
      auditTitle: "Inside the APK",
      auditSub: "Every claim can be checked directly in the source tree and CI logs.",
      a1Key: "Default bind",
      a1Val: "<code>127.0.0.1:1443</code> localhost only. Switching to <code>0.0.0.0</code> displays an explicit LAN warning.",
      a2Key: "Dependencies",
      a2Val: "AndroidX, Compose, Kotlin Coroutines, and OkHttp only. No ads, analytics, or Google Play Services.",
      a3Key: "Permissions & backup",
      a3Val: "<code>allowBackup=\"false\"</code>, secrets and domains redacted from logs, no <code>REQUEST_INSTALL_PACKAGES</code> permission.",
      a4Key: "Release builds",
      a4Val: "Public GitHub Actions workflow publishing <code>SHA256SUMS.txt</code> with <code>dependenciesInfo</code> disabled.",
      allReleases: "All releases",
      footerNote: "MIT License · Flowseal/tg-ws-proxy protocol",
      langBtn: "RU"
    }
  };

  let currentLang =
    localStorage.getItem("tgws_lang") ||
    ((navigator.language || "").toLowerCase().startsWith("ru") ? "ru" : "en");
  if (!I18N[currentLang]) currentLang = "ru";
  let currentScreen = "simple";
  let releaseData = { ...FALLBACK_RELEASE };

  const root = document.documentElement;
  root.setAttribute("data-theme", localStorage.getItem("tgws_theme") || "dark");

  function updateThemeIcon() {
    const isLight = root.getAttribute("data-theme") === "light";
    const btn = document.getElementById("theme-toggle");
    if (!btn) return;
    btn.setAttribute("aria-label", isLight ? "Switch to dark theme" : "Switch to light theme");
    btn.innerHTML = isLight
      ? '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>'
      : '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="5"/><line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/><line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/></svg>';
  }

  function applyLang(lang) {
    currentLang = lang;
    localStorage.setItem("tgws_lang", lang);
    root.setAttribute("lang", lang);
    const dict = I18N[lang];
    document.title = dict.title;

    document.querySelectorAll("[data-i18n]").forEach((el) => {
      const key = el.getAttribute("data-i18n");
      if (dict[key] !== undefined) el.textContent = dict[key];
    });
    document.querySelectorAll("[data-i18n-html]").forEach((el) => {
      const key = el.getAttribute("data-i18n-html");
      if (dict[key] !== undefined) el.innerHTML = dict[key];
    });

    updateReleaseUI();
    updateScreenCaption();
  }

  function updateReleaseUI() {
    const dict = I18N[currentLang];
    const dlBtn = document.getElementById("download-apk-btn");
    const dlText = document.getElementById("download-apk-text");
    const verMeta = document.getElementById("release-version-meta");
    const shaLabel = document.getElementById("sha-label");

    if (dlBtn) dlBtn.href = releaseData.apkUrl;
    if (dlText) {
      const unit = currentLang === "ru" ? "МБ" : "MB";
      dlText.textContent = `${dict.downloadBtn} (${releaseData.version} · ${releaseData.sizeMb} ${unit})`;
    }
    if (verMeta) verMeta.textContent = releaseData.version;
    if (shaLabel) shaLabel.textContent = `${dict.shaLabel}: ${releaseData.sha256.slice(0, 10)}…`;
  }

  function updateScreenCaption() {
    const dict = I18N[currentLang];
    const capEl = document.getElementById("screen-caption");
    if (!capEl) return;
    const map = { simple: dict.capSimple, pro: dict.capPro, light: dict.capLight };
    capEl.textContent = map[currentScreen] || dict.capSimple;
  }

  function selectScreen(screen) {
    currentScreen = screen;
    document.querySelectorAll(".phone-screen img").forEach((img) => {
      img.classList.toggle("is-active", img.dataset.screen === screen);
    });
    document.querySelectorAll(".screen-tab").forEach((tab) => {
      const active = tab.dataset.screen === screen;
      tab.classList.toggle("is-active", active);
      tab.setAttribute("aria-selected", String(active));
    });
    updateScreenCaption();
  }

  async function loadLatestRelease() {
    try {
      const res = await fetch(`https://api.github.com/repos/${REPO}/releases/latest`, {
        headers: { Accept: "application/vnd.github+json" }
      });
      if (!res.ok) return;
      const json = await res.json();
      const apk = (json.assets || []).find((a) => a.name && a.name.endsWith(".apk"));
      if (!apk) return;
      releaseData = {
        version: json.tag_name || FALLBACK_RELEASE.version,
        sizeMb: (apk.size / (1024 * 1024)).toFixed(1),
        apkUrl: apk.browser_download_url || FALLBACK_RELEASE.apkUrl,
        releaseUrl: json.html_url || FALLBACK_RELEASE.releaseUrl,
        sha256:
          apk.digest && apk.digest.startsWith("sha256:")
            ? apk.digest.slice(7)
            : FALLBACK_RELEASE.sha256
      };
      updateReleaseUI();
    } catch (_) {
      // Keep static fallback release
    }
  }

  document.addEventListener("DOMContentLoaded", () => {
    updateThemeIcon();
    applyLang(currentLang);
    selectScreen("simple");
    loadLatestRelease();

    const themeBtn = document.getElementById("theme-toggle");
    if (themeBtn) {
      themeBtn.addEventListener("click", () => {
        const next = root.getAttribute("data-theme") === "light" ? "dark" : "light";
        root.setAttribute("data-theme", next);
        localStorage.setItem("tgws_theme", next);
        updateThemeIcon();
      });
    }

    const langBtn = document.getElementById("lang-toggle");
    if (langBtn) {
      langBtn.addEventListener("click", () => {
        applyLang(currentLang === "ru" ? "en" : "ru");
      });
    }

    document.querySelectorAll(".screen-tab").forEach((tab) => {
      tab.addEventListener("click", () => selectScreen(tab.dataset.screen));
    });

    const shaBtn = document.getElementById("copy-sha-btn");
    if (shaBtn) {
      shaBtn.addEventListener("click", async () => {
        try {
          await navigator.clipboard.writeText(releaseData.sha256);
          const lbl = document.getElementById("sha-label");
          shaBtn.classList.add("copied");
          if (lbl) lbl.textContent = I18N[currentLang].shaCopied;
          setTimeout(() => {
            shaBtn.classList.remove("copied");
            updateReleaseUI();
          }, 2000);
        } catch (_) {
          window.open(releaseData.releaseUrl, "_blank", "noopener");
        }
      });
    }
  });
})();
