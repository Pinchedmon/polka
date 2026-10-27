# Полка · Polka

Android-приложение, чтобы закидывать книги и заставки на **Xteink X4 / X4 Pro** по Wi-Fi — без проводов, браузера и IP-адресов. Работает с прошивками [CrossPoint](https://github.com/crosspoint-reader/crosspoint-reader) и [inkMOD](https://github.com/alpzoloto-sudo/inkmod).

**Сайт:** https://pinchedmon.github.io/polka/ · **Скачать:** [Polka.apk](../../releases/latest/download/Polka.apk)

*English version below.*

## Что умеет

- **Сама находит читалку** в сети (UDP-поиск прошивки, последний адрес, `crosspoint.local`, хотспот читалки). Не нашла — нажми на нарисованную читалку или введи IP вручную.
- **Книги в один тап**: EPUB, FB2, TXT, PDF — что угодно. Можно выбрать заранее — улетят, как только X4 появится.
- **«Поделиться → На X4»** из Telegram, браузера, «Файлов» и любого другого приложения.
- **Полка на карте**: папки, «кидать сюда», создание и удаление.
- **Заставки**: любая картинка → BMP 480×800 в `/sleep`. Редактор кадра (щипок, перетаскивание, поворот, белые/чёрные поля), превью в оттенках серого — как на e-ink. «Поставить» закрепляет одну картинку (`/sleep.bmp`), остальные меняются случайно. Режим «Своя картинка» на читалке включается сам.
- Языки: English, Русский, Українська, Deutsch, Español. Светлая и тёмная тема.

## Установка

1. Скачай [Polka.apk](../../releases/latest/download/Polka.apk) на телефон (Android 8+).
2. Открой файл и разреши установку из этого источника.
3. На X4: **File Transfer** → подключи к той же Wi-Fi, что и телефон (или подключи телефон к хотспоту читалки `CrossPoint-Reader`).
4. Открой Полку — читалка найдётся сама.

Нужна прошивка CrossPoint или inkMOD: у заводской прошивки Xteink нет API передачи файлов.

## Сборка

```sh
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

Без своего ключа (`~/.android/polka-keystore.properties`) release-сборка подписывается debug-ключом.

## Поддержать проект

Если Полка сэкономила тебе пару вечеров с проводами — можно [поддержать автора](https://new.donatepay.ru/donate/1536180). Спасибо!

---

## English

An Android app to send books and sleep screens to the **Xteink X4 / X4 Pro** over Wi-Fi — no cables, no browser, no IP addresses. Works with [CrossPoint](https://github.com/crosspoint-reader/crosspoint-reader) and [inkMOD](https://github.com/alpzoloto-sudo/inkmod) firmware.

- **Finds the reader by itself** (firmware UDP discovery, last address, `crosspoint.local`, the reader's hotspot). If not — tap the drawn reader or type the IP.
- **Books in one tap**: EPUB, FB2, TXT, PDF — anything. Queue them while offline; they fly when the X4 shows up.
- **Share → «To X4»** from Telegram, a browser, Files or any app.
- **Shelf**: browse folders on the card, pick the target folder, create and delete.
- **Sleep screens**: any picture → 480×800 BMP in `/sleep`, with a framing editor (pinch, drag, rotate, white/black margins) and a grayscale preview. «Set» pins one picture (`/sleep.bmp`); the rest rotate randomly. Custom sleep mode is switched on automatically.
- Languages: English, Русский, Українська, Deutsch, Español. Light and dark theme.

**Install:** download [Polka.apk](../../releases/latest/download/Polka.apk), open it on the phone (Android 8+). On the X4 open **File Transfer** and join the same Wi-Fi. Stock Xteink firmware is not supported — it has no file transfer API.

**Support:** if Polka saved you some cable time, you can [support the author](https://new.donatepay.ru/donate/1536180).

License: GPL-3.0.
