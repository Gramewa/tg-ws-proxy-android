<div align="center">

# TG WS Proxy Android

[![Release](https://img.shields.io/github/v/release/Gramewa/tg-ws-proxy-android?style=for-the-badge&logo=github)](https://github.com/Gramewa/tg-ws-proxy-android/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Gramewa/tg-ws-proxy-android/releases/latest)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose%20M3-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://github.com/Gramewa/tg-ws-proxy-android)
[![License](https://img.shields.io/badge/License-MIT-blue?style=for-the-badge)](LICENSE)

**Чистый нативный Android-форк локального MTProto WebSocket-прокси [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy)**

[⬇️ Скачать последнюю версию APK](https://github.com/Gramewa/tg-ws-proxy-android/releases/latest)

</div>

---

## Возможности

Приложение написано с нуля на **Kotlin + Coroutines + Jetpack Compose (Material 3)** без тяжёлых нативных библиотек и полностью повторяет актуальный протокол десктопного `Flowseal/tg-ws-proxy` (`v1.11.4+`):

- **Дизайн Cyber-Glass и 5 цветовых палитр:** анимированный фон Aurora с радиальной сеткой, стеклянные карточки с направленной рамкой, реакторный переключатель подключения, плавающая панель навигации и палитры (**Обсидиан**, **Океан**, **Изумруд**, **Аметист**, **Закат**) + Material You (Android 12+).
- **Два режима интерфейса — «Простой» и «Эксперт»:** переключатель в шапке позволяет выбрать минималистичный главный экран («Подключить», «Открыть в Telegram» и «Резервный канал») либо полный режим с живой топологией маршрутов, статистикой трафика, тестами доменов и терминальными логами.
- **Быстрый тумблер «Выключить TLS для CF-прокси и CF-worker»** (в простом режиме — **«Резервный канал»**, порт `80` вместо `443`, аналог `--no-secure`). При переключении на работающем прокси режим применяется мгновенно.
- **Полная совместимость с протоколом:** прямой пул `DC → IP` с **SNI-fronting**, **Cloudflare Proxy** fallback с автообновлением доменов, **мультиплексирование медиа HTTP/2**, **Cloudflare Worker**, **Fake TLS (`ee-secret` с `X25519MLKEM768`)**, тест доступности маршрутов, `Foreground Service`, Quick Settings Tile и автозапуск.
- **Проверка обновлений без лишних разрешений:** автопроверка новых релизов GitHub со списком изменений и открытием загрузки через системный браузер (без `REQUEST_INSTALL_PACKAGES`).
- **Усиленная безопасность:** `allowBackup="false"`, `usesCleartextTraffic="false"`, маскирование секрета в логах и защищённом буфере обмена (Android 13+), SSRF-фильтр публичных IP для Fake TLS и лимиты буферов (см. [SECURITY.md](SECURITY.md)).

---

## Установка и быстрый старт

1. Скачайте актуальный `tg-ws-proxy-android-*.apk` со страницы **[Releases](https://github.com/Gramewa/tg-ws-proxy-android/releases/latest)** и установите на Android-устройство.
2. Откройте **TG WS Proxy**.
3. Нажмите **«Подключить»** (или на центральный круг).
4. Нажмите **«Открыть в Telegram»** (поддерживаются официальный Telegram, AyuGram, NekoGram, Plus Messenger и др.) или скопируйте ссылку кнопкой рядом.
5. Если Telegram всё равно зависает на «Соединение…» или не грузит медиа — включите **«Резервный канал»** (в режиме Эксперт — **«Выключить TLS для CF-прокси и CF-worker»**).

---

## Решение частых проблем

- **Не подключается или зависает загрузка:** включите **«Резервный канал»** на главном экране (переводит Cloudflare-соединения на порт `80`).
- **Не грузятся фото/видео, но работает CF-прокси:** переключитесь на **Эксперт → Настройки → Датацентры Telegram (DC → IP)** и нажмите **«Очистить (всё через CF)»**, затем сохраните настройки.
- **Android останавливает прокси в фоне:** нажмите **«Отключить ограничения»** на карточке энергосбережения на главном экране.

---

## Лицензия

Проект распространяется под лицензией **MIT** ([LICENSE](LICENSE)) на основе оригинального [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy).
