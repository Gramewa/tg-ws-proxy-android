# tg-ws-proxy-android

Android-версия локального MTProto WebSocket-прокси по протоколу [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy). Написана на чистом Kotlin без тяжёлых нативных библиотек как живая замена заброшенному стороннему форку.

- **Скачать APK:** [последний релиз на GitHub](https://github.com/Gramewa/tg-ws-proxy-android/releases/latest) (Android 8.0+)
- **Обновлять через Obtainium:** [добавить в один клик](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22io.github.tgwsproxy.android%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FGramewa%2Ftg-ws-proxy-android%22%2C%22author%22%3A%22Gramewa%22%2C%22name%22%3A%22TG%20WS%20Proxy%22%7D)

## Как подключить

1. Установите `tg-ws-proxy-android-*.apk`.
2. Нажмите кнопку запуска на главном экране.
3. Нажмите **Открыть в Telegram** (подходят официальный клиент, AyuGram, NekoGram, Plus Messenger и другие).
4. Если висит подключение или не грузятся фото и видео, включите **Резервный порт 80** или запустите встроенную проверку **Проверить маршрут** прямо на главной.

Чтобы система не останавливала прокси при выключенном экране, на главном экране есть быстрая кнопка снятия ограничений батареи.

## Возможности

- Два режима в шапке: **Простой** (запуск, передача прокси в Telegram, порт 80 и быстрый пинг маршрутов) и **Pro** (подробная статистика соединений, гибкие настройки, экспорт/импорт JSON-конфига и логи).
- Полная совместимость с `Flowseal/tg-ws-proxy` (`v1.11.4+`): прямой пул WebSocket к DC с SNI-fronting, резерв через Cloudflare Proxy с автообновлением доменов, мультиплексирование медиа по HTTP/2, свои Cloudflare Worker и Fake TLS (`ee`-секрет с `X25519MLKEM768`).
- Автоматический сброс зависших сокетов при переключении Wi-Fi и мобильной сети, спящий режим пула в простое ночью, плитка в шторке Android, автозапуск и проверка обновлений с GitHub.
- Тёмная и светлая тема оформления плюс поддержка системных цветов Material You (Android 12+).

## Если плохо грузятся медиа

- Включите **Резервный порт 80** на главном экране (аналог флага `--no-secure` на ПК, переводит Cloudflare-подключения на HTTP порт 80 при сохранении шифрования MTProto).
- Если не помогло, переключитесь в режим **Pro -> Настройки -> Датацентры (DC:IP)**, нажмите **Очистить (всё в CF)** и нажмите **Применить**.

---

## English

**Telegram MTProto WebSocket Proxy for Android** is an actively maintained pure-Kotlin port of [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy).

- **Download APK:** [Latest GitHub Release](https://github.com/Gramewa/tg-ws-proxy-android/releases/latest) (Android 8.0 / API 26+)
- **Obtainium:** [Add to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22io.github.tgwsproxy.android%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FGramewa%2Ftg-ws-proxy-android%22%2C%22author%22%3A%22Gramewa%22%2C%22name%22%3A%22TG%20WS%20Proxy%22%7D)

Features full protocol parity with desktop `tg-ws-proxy` (`v1.11.4+`): direct WebSocket DC pools with SNI fronting, Cloudflare Proxy fallback with auto-updated domains, HTTP/2 media multiplexing, plain port `80` fallback (`--no-secure`), custom Cloudflare Workers, Fake TLS (`ee` secret with `X25519MLKEM768`), inline route latency diagnostics, automatic Wi-Fi/cellular pool recovery, JSON config export/import, Quick Settings tile, Simple/Pro modes, Material You support, and English/Russian UI.

## License

MIT ([LICENSE](LICENSE)), based on [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy).
