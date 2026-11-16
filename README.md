# TG WS Proxy Android

Чистый нативный Android-порт локального MTProto WebSocket-прокси **[Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy)** на **Kotlin + Coroutines + Jetpack Compose (Material 3)** без NDK/Rust и под лицензией **MIT**.

Проект воспроизводит актуальный протокол оригинального десктопного проекта (ветка `main` / `v1.11.4+`):
- Прямой пул WebSocket-соединений к датацентрам Telegram (`DC → IP`) с поддержкой **SNI-fronting**.
- **Cloudflare Proxy** fallback с автоматическим обновлением и проверкой пула доменов, а также поддержкой пользовательских доменов.
- **HTTP/2 мультиплексирование медиа** (`CfH2Pool`) для быстрой загрузки фото/видео/файлов через Cloudflare.
- **Cloudflare Worker** fallback (`/apiws?dst=...&dc=...`) с пулом подключений.
- **Переключатель «Выключить TLS для CF-прокси и CF-worker»** (аналог флага `--no-secure` / порт `80` вместо `443`) прямо на главном экране и в разделе настроек **«Логи и производительность»**.
- **Fake TLS (`ee-secret`)** с HMAC-SHA256 проверкой `ClientHello`, зеркалированием cipher suites / key shares (включая `X25519MLKEM768`), профилированием размера записей маскируемого сайта, защитой от replay-атак и прозрачным проксированием на сайт маскировки при активной проверке.
- Поддержка тестовых DC (`10001+` и `--force-test-dc`), `PROXY protocol v1`, проверка доступности маршрутов (`Test`), Foreground Service, плитка быстрых настроек (Quick Settings Tile) и автозапуск после перезагрузки.

---

## Как получить готовый APK через GitHub Actions (без установки Android Studio на ПК)

1. Создайте новый репозиторий на GitHub (например `tg-ws-proxy-android`).
2. Загрузите содержимое этой папки в ветку `main`:
   ```bash
   git add .
   git commit -m "Initial release: pure Kotlin TG WS Proxy Android"
   git branch -M main
   git remote add origin https://github.com/<ВАШ_НИК>/tg-ws-proxy-android.git
   git push -u origin main
   ```
3. Перейдите на вкладку **Actions** в вашем репозитории на GitHub — workflow **`Build Android APK`** автоматически запустит unit-тесты и соберёт оба APK:
   - `tg-ws-proxy-android-release.apk`
   - `tg-ws-proxy-android-debug.apk`
4. Скачайте архив **`tg-ws-proxy-android-apks`** из блока **Artifacts** завершённой сборки.
5. *(По желанию)* Чтобы получать APK прямо во вкладке **Releases**, создайте и отправьте тег вида `v*`:
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```
6. *(По желанию, для подписи вашим ключом)* Добавьте в `Settings → Secrets and variables → Actions` вашего репозитория секреты:
   - `KEYSTORE_BASE64` — ваш `.jks`/`.keystore` в Base64
   - `KEYSTORE_PASSWORD`
   - `KEY_ALIAS`
   - `KEY_PASSWORD`
   Если секреты не заданы, workflow автоматически соберёт полностью рабочий и готовый к установке Release APK с тестовой подписью.

---

## Быстрый старт на смартфоне

1. Установите `tg-ws-proxy-android-release.apk`.
2. Откройте приложение и при необходимости включите тумблер **«Выключить TLS для CF-прокси и CF-worker»** прямо на главном экране (использует порт `80`, помогает при блокировке TLS-рукопожатий к Cloudflare).
3. Нажмите **«Запустить прокси»**.
4. Нажмите **«Подключить в Telegram»** (поддерживаются официальный Telegram, AyuGram, NekoGram, Plus Messenger и др.) или **«Скопировать ссылку»**.

---

## Лицензия

Распространяется под лицензией **MIT** (см. [LICENSE](LICENSE)), как и оригинальный проект [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy).
