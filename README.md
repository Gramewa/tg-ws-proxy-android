# tg-ws-proxy-android

Android-версия локального MTProto WebSocket-прокси на базе протокола [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy). Сделана с нуля на Kotlin, без тяжёлых нативных библиотек — прошлый сторонний Android-форк ушёл в архив и перестал догонять обновления оригинала.

- **Скачать APK:** [последний релиз на GitHub](https://github.com/Gramewa/tg-ws-proxy-android/releases/latest) (Android 8.0+)
- **Обновлять через Obtainium:** [добавить приложение в один клик](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22io.github.tgwsproxy.android%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FGramewa%2Ftg-ws-proxy-android%22%2C%22author%22%3A%22Gramewa%22%2C%22name%22%3A%22TG%20WS%20Proxy%22%7D)

## Как пользоваться

1. Скачайте и установите `tg-ws-proxy-android-*.apk`.
2. На главном экране нажмите **«Подключить»**.
3. Нажмите **«Открыть в Telegram»** — настройки прокси применятся сами (работает в обычном Telegram, AyuGram, NekoGram, Plus Messenger и других клиентах).
4. Если Telegram всё равно зависает на «Соединение…» или не грузит картинки, включите тумблер **«Резервный канал»** — он переводит Cloudflare-подключения на 80-й порт без TLS (как `--no-secure` на ПК).

Чтобы Android не выгружал прокси в фоне, на главном экране есть карточка отключения ограничений батареи.

## Что умеет

- Два режима в переключателе сверху: **Простой** (кнопка подключения, ссылка в Telegram и резервный канал) и **Эксперт** (все настройки, проверка доменов и логи).
- Полная совместимость с `Flowseal/tg-ws-proxy` (`v1.11.4+`): прямой пул подключений к DC с SNI-fronting, откат на Cloudflare Proxy с автообновлением доменов, HTTP/2 для медиа, свои Cloudflare Worker и Fake TLS (`ee`-секрет с `X25519MLKEM768`).
- Быстрая плитка в шторке Android, опциональный автозапуск после перезагрузки и проверка новых версий с GitHub.
- Несколько цветовых тем на выбор, включая тёмную «Обсидиан» и цвета системы (Android 12+).

## Если не грузятся фото и видео

- Включите **«Резервный канал»** на главном экране (в режиме «Эксперт» это тумблер **«Выключить TLS для CF-прокси и CF-worker»**).
- Если не помогает — зайдите в **Эксперт → Настройки → Датацентры Telegram (DC → IP)**, нажмите **«Очистить (всё через CF)»** и сохраните настройки. Проверить, какие домены открываются в вашей сети, можно кнопкой `Test` там же в настройках.

---

## English

**Telegram MTProto WebSocket Proxy for Android** — an up-to-date pure-Kotlin Android port of [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy), built as an actively maintained replacement for the archived Android fork.

- **Download APK:** [Latest GitHub Release](https://github.com/Gramewa/tg-ws-proxy-android/releases/latest) (Android 8.0 / API 26+)
- **Obtainium:** [Add to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22io.github.tgwsproxy.android%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FGramewa%2Ftg-ws-proxy-android%22%2C%22author%22%3A%22Gramewa%22%2C%22name%22%3A%22TG%20WS%20Proxy%22%7D)

Supports full protocol parity with desktop `tg-ws-proxy` (`v1.11.4+`): direct WebSocket DC pools with SNI fronting, Cloudflare Proxy fallback with auto-updated domain pool, HTTP/2 media multiplexing, plain port `80` fallback (`--no-secure` toggle when TLS handshakes to Cloudflare are blocked), custom Cloudflare Workers, Fake TLS (`ee` secret with `X25519MLKEM768`), Quick Settings tile, Simple/Pro UI modes, and English/Russian localization.

## Лицензия / License

MIT ([LICENSE](LICENSE)), based on [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy).
