<div align="center">

# TG WS Proxy Android

[![Release](https://img.shields.io/github/v/release/Aethrus/tg-ws-proxy-android?style=for-the-badge&logo=github)](https://github.com/Aethrus/tg-ws-proxy-android/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Aethrus/tg-ws-proxy-android/releases/latest)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose%20M3-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://github.com/Aethrus/tg-ws-proxy-android)
[![License](https://img.shields.io/badge/License-MIT-blue?style=for-the-badge)](LICENSE)

**Чистый нативный Android-форк локального MTProto WebSocket-прокси [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy)**

[⬇️ Скачать последнюю версию APK](https://github.com/Aethrus/tg-ws-proxy-android/releases/latest)

</div>

---

## Возможности

Приложение написано с нуля на **Kotlin + Coroutines + Jetpack Compose (Material 3)** без тяжёлых нативных библиотек и полностью повторяет актуальный протокол десктопного `Flowseal/tg-ws-proxy` (`v1.11.4+`):

- **Быстрый тумблер «Выключить TLS для CF-прокси и CF-worker»** (порт `80` вместо `443`, аналог `--no-secure`) — вынесен прямо на главный экран и синхронизирован с разделом **Настройки → Логи и производительность**. При переключении на работающем прокси режим применяется мгновенно.
- **Прямой пул WebSocket-соединений (`DC → IP`)** с поддержкой **SNI-fronting** и автоматическим переключением при сбоях.
- **Cloudflare Proxy fallback** с автообновлением списка доменов и возможностью задать собственные домены.
- **Мультиплексирование медиа через HTTP/2 (`CfH2Pool`)** для быстрой загрузки фото, видео и файлов через Cloudflare.
- **Cloudflare Worker fallback** с пулом соединений (`/apiws?dst=...&dc=...`).
- **Fake TLS (`ee-secret`)** с HMAC-SHA256 проверкой `ClientHello`, поддержкой `X25519MLKEM768`, профилированием маскируемого сайта, защитой от replay-атак и прозрачным проксированием активных проверок.
- **Тест доступности маршрутов (`Проверить`)** прямо в настройках с учётом выбранного режима порта (`443 TLS` / `80 HTTP`).
- **Фоновая работа:** `Foreground Service` со статистикой трафика и кнопками в уведомлении, плитка быстрых настроек (Quick Settings Tile), автозапуск после загрузки и опциональный WakeLock / Wi-Fi Lock.
- **Два языка и темы:** русский / английский, светлая / тёмная тема и Dynamic Colors (Android 12+).

---

## Установка и быстрый старт

1. Скачайте актуальный `tg-ws-proxy-android-*.apk` со страницы **[Releases](https://github.com/Aethrus/tg-ws-proxy-android/releases/latest)** и установите на Android-устройство.
2. Откройте **TG WS Proxy**.
3. Если подключения через Cloudflare не проходят по TLS (ошибки/таймауты), включите на главном экране переключатель **«Выключить TLS для CF-прокси и CF-worker»**.
4. Нажмите **«Запустить прокси»**.
5. Нажмите **«Подключить в Telegram»** (поддерживаются официальный Telegram, AyuGram, NekoGram, Plus Messenger и др.) или **«Скопировать ссылку»**, отправьте в *Избранное* и нажмите по ней.

---

## Решение частых проблем

- **Не подключается или зависает загрузка:** включите тумблер **«Выключить TLS для CF-прокси и CF-worker»** на главном экране.
- **Не грузятся фото/видео, но работает CF-прокси:** откройте **Настройки → Датацентры Telegram (DC → IP)** и нажмите **«Очистить (всё через CF)»**, затем сохраните настройки.
- **Android останавливает прокси в фоне:** нажмите **«Отключить ограничения»** на карточке энергосбережения на главном экране.

---

## Лицензия

Проект распространяется под лицензией **MIT** ([LICENSE](LICENSE)) на основе оригинального [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy).
