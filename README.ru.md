<div align="center">

<img src="docs/images/icon.png" width="112" alt="Иконка Clear Content">

# Clear Content

**Офлайн-приложение для Android: удаляет служебные метки и скрытые символы из фото, видео и текста.**

[![CI](https://github.com/izhddm/clear-content-android/actions/workflows/ci.yml/badge.svg)](https://github.com/izhddm/clear-content-android/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/izhddm/clear-content-android)](https://github.com/izhddm/clear-content-android/releases/latest)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Без доступа в интернет](https://img.shields.io/badge/permissions-no%20INTERNET-success)

[English](README.md) · Русский

</div>

Вы немного поправили своё фото в ChatGPT, Gemini (Nano Banana), Firefly или другом редакторе. Редактор записал в файл Content Credentials (C2PA) и IPTC-метку «создано ИИ», и Instagram из-за них ставит пометку **AI info**.

Clear Content удаляет эти метаданные файла, а также EXIF/GPS, XMP, параметры генерации и метаданные видео. Из текста он убирает невидимые символы и артефакты чат-ботов. Всё работает на телефоне: разрешения `INTERNET` у приложения нет.

<p align="center">
  <img src="docs/images/screenshot-media-empty.png" width="200" alt="Экран медиа">
  <img src="docs/images/screenshot-media-results.png" width="200" alt="Результаты очистки">
  <img src="docs/images/screenshot-text-hidden.png" width="200" alt="Подсветка скрытых символов">
  <img src="docs/images/screenshot-text-result.png" width="200" alt="Очищенный текст">
  <img src="docs/images/screenshot-settings.png" width="200" alt="Настройки">
</p>

## Возможности

- **Очистка фото без потери качества** для JPEG, PNG, WebP и GIF: удаляются только блоки метаданных, пиксели копируются байт в байт. Поворот и цветовой профиль сохраняются.
- **HEIC / AVIF / TIFF / BMP** перекодируются. Можно уменьшить размер и сменить формат (JPEG / PNG / WebP).
- **Видео MP4 / MOV**: удаляются `uuid` (C2PA), `udta`/`meta` (ключи Apple, китайская метка TC260 AIGC, геолокация). Видео не перекодируется, а таблицы смещений `stco`/`co64` пересчитываются. Фрагментированные MP4 и WebM пересобираются.
- **Текст**:
  - удаляются zero-width символы, bidi, узкие неразрывные пробелы U+202F, теги Unicode (ASCII smuggling), данные в селекторах вариантов;
  - удаляются остатки цитат ChatGPT (`citeturn0search0`, `【4:0†source】`) и `utm_source=chatgpt.com`;
  - эмодзи с ZWJ, флаги-регионы, персидский и индийские языки, CJK-варианты не повреждаются;
  - опционально: Markdown в простой текст, типографика, «стилизованные» буквы.
- **Детектор 25+ генераторов**: OpenAI, Google Gemini/Imagen, Adobe Firefly, Midjourney, Stable Diffusion/ComfyUI, Flux, Meta AI, Grok, Kling, Doubao/Jimeng, Apple Image Playground и другие.
- **Проверка результата**: каждый файл сканируется повторно и не публикуется, пока в нём остаётся хоть одна метка.
- **Быстрый доступ**:
  - меню «Поделиться»;
  - плитка «Очистить буфер» в шторке;
  - пункт «Очистить текст» в меню выделения;
  - ярлыки на иконке;
  - отправка в Instagram одной кнопкой.
- **Замена оригиналов** (опция, Android 11+): очищенная копия встаёт на место исходника — тот же альбом и та же дата съёмки. Исходник удаляется после системного подтверждения, дублей в галерее нет.
- Очередь с foreground-сервисом: «Поделиться → Назад» не прерывает обработку большого видео.

## Установка

1. Скачайте `ClearContent-<версия>.apk` в разделе [**Releases**](https://github.com/izhddm/clear-content-android/releases/latest).
2. Откройте файл на телефоне и разрешите установку из этого источника. Play Protect может предупредить о неизвестном приложении — выберите «Всё равно установить». На Samsung иногда нужно временно отключить «Автоблокировщик».
3. По желанию: в Настройках нажмите «Добавить плитку в шторку».

**Проверка подлинности.** Release-сборки подписаны сертификатом:

```
SHA-256: 09a5e0b3873a20ad1e6a25b8379381327aeac2c13bc85e76c3b22cdaaa9da346
```

```bash
apksigner verify --print-certs ClearContent-1.1.0.apk
gh attestation verify ClearContent-1.1.0.apk --repo izhddm/clear-content-android   # для релизов, собранных в CI
```

## Как пользоваться

| Способ | Что происходит |
|---|---|
| **«Поделиться» → Clear Content** из галереи, ChatGPT, Gemini, Telegram | Файлы очищаются и сохраняются в `Pictures/ClearContent` (видео — в `Movies/ClearContent`). Одна кнопка отправляет их в Instagram |
| **Плитка «Очистить буфер»** в шторке | Очищает скопированный текст или картинку и кладёт результат обратно в буфер |
| **Выделить текст → «Очистить текст»** в любом приложении | Выделенный фрагмент заменяется очищенным; из полей только для чтения результат копируется в буфер |
| **Вкладка «Текст»** | Показывает скрытые символы и очищенный результат, есть кнопки «Копировать» и «Поделиться» |
| **Долгое нажатие на иконку** | Ярлыки «Буфер», «Фото и видео», «Текст» |

В Настройках можно сделать так, чтобы после «Поделиться» сразу открывался Instagram или системное меню «Поделиться».

### Замена оригиналов (без дублей)

Настройки → «Заменять оригиналы в галерее» (Android 11+):

- **Альбом и дата.** Очищенная копия сохраняется в тот же альбом (например, `DCIM/Camera`) под тем же именем. Из метаданных в ней остаются только дата съёмки и часовой пояс (`DateTimeOriginal`, `OffsetTimeOriginal`), поэтому фото остаётся на своём месте в ленте.
- **Удаление исходника.** Исходник удаляется через системный диалог Android — один на пачку, с миниатюрами. Если отказаться, в списке остаётся кнопка «Удалить оригиналы из галереи».
- **Безопасность.** Оригинал удаляется только если очищенная копия прошла проверку и уже сохранена в галерее.
- **Из «Поделиться» в Samsung Галерее** сохраняются и альбом, и имя.
- **Из системного выбора фото** сохраняется дата. Android скрывает имя и папку исходника, поэтому копия получает нейтральное имя и попадает в `Pictures/ClearContent`.
- **Файлы не из галереи** (кэш Telegram или ChatGPT) не удаляются.

## Что удаляется

| Формат | Удаляется | Сохраняется |
|---|---|---|
| JPEG | APP1 EXIF/XMP, APP11 JUMBF (C2PA), APP13 IPTC, комментарии, миниатюры JFIF/JFXX, доп. кадры MPF, данные после EOI | ICC-профиль, цветовое преобразование Adobe APP14, поворот |
| PNG | `caBX`, `tEXt`/`zTXt`/`iTXt` (промпты A1111/ComfyUI, XMP), `eXIf`, `tIME`, неизвестные вспомогательные блоки, данные после IEND | блоки отрисовки, `iCCP`, поворот |
| WebP | `EXIF`, `XMP `, `C2PA`, неизвестные блоки (флаги VP8X и размер RIFF пересчитываются) | кадры и анимация, `ICCP` |
| GIF | комментарии, расширения XMP и C2PA | кадры, зацикливание, ICC |
| MP4/MOV | верхнеуровневые `uuid`/`meta`/`free`, `udta` и `meta` в `moov`/`trak`, атомы `©` | аудио и видео (без перекодирования) |
| Текст | невидимые, bidi, теги, управляющие и служебные символы, остатки цитат чат-ботов, метки ИИ в ссылках | всё видимое |

## Ограничения и ответственное использование

Приложение удаляет **метаданные файла** и **скрытые символы** — то же, что удаляют инструменты приватности вроде ExifTool.

- **Водяные знаки в пикселях и словах не удаляются.** Такие знаки, например [SynthID](https://deepmind.google/science/synthid/), который ставят Google и OpenAI, приложение не удаляет и не изменяет. В манифестах таких файлов есть действие `c2pa.watermarked.unbound`.
- **Нет гарантии,** что платформа не распознает ИИ-контент другими способами.
- **Соблюдайте правила платформ и закон.** Если публикуете сгенерированный или существенно изменённый ИИ контент, следуйте правилам раскрытия платформы (например, политике Meta о маркировке ИИ) и законодательству. Не используйте приложение, чтобы вводить людей в заблуждение.

## Сборка из исходников

Нужны полноценный JDK 17 или новее и Android SDK с `platforms;android-37.0` и `build-tools;37.0.0`.

```bash
./gradlew :core:test                     # модульные, эталонные и фаззинг-тесты (JVM)
./gradlew :app:assembleDebug             # отладочный APK
./gradlew :app:connectedDebugAndroidTest # полный конвейер на устройстве или эмуляторе
./scripts/build-release.sh               # тесты + подписанный APK → dist/
```

Подпись release-сборки берётся из `keystore.properties`; если файла нет, используется отладочный ключ. Подробнее — в [CONTRIBUTING.md](CONTRIBUTING.md#releases).

### Консольная утилита (ПК)

```bash
./gradlew :core:installDist
core/build/install/clear-content/bin/clear-content scan  photo.jpg video.mp4
core/build/install/clear-content/bin/clear-content clean -o out/ photo.jpg video.mp4
echo "текст" | core/build/install/clear-content/bin/clear-content text --markdown
```

## Архитектура

```
core/   чистый Kotlin (JVM), без зависимостей
  media/   сниффинг форматов; разбор, сканирование и очистка без потерь JPEG/PNG/WebP/GIF/ISO BMFF;
           анализ меток ИИ; потоковое чтение больших видео
  text/    контекстная очистка Unicode, артефакты чат-ботов, удаление Markdown
  cli/     консольная утилита
app/    Android (Kotlin, Jetpack Compose, Material 3)
  media/   MediaCleaner (скан → очистка → повторная проверка → публикация), ImageReencoder, VideoRemuxer,
           SourceReader, OutputStore (MediaStore, FileProvider), GalleryOriginals
  queue/   очередь уровня приложения CleanQueue + ProcessingService (foreground)
  system/  буфер обмена, отправка, разбор входящих Intent
  ui/      экраны Compose, PROCESS_TEXT, очистка буфера, плитка шторки
design/ исходники иконки и иллюстраций (SVG)
```

## Тесты

- **JVM** (`./gradlew :core:test`):
  - синтетические JPEG, PNG, WebP, GIF, MP4, включая пересчёт смещений и защиту от глубокой вложенности;
  - текст;
  - фаззинг повреждёнными файлами;
  - 31 эталонный файл в [`core/src/test/resources/fixtures`](core/src/test/resources/fixtures): настоящие C2PA-подписи и синтетика генераторов.
- **На устройстве** — весь конвейер Android: все эталонные файлы, перекодирование HEIC/AVIF, перемуксирование, MediaStore и FileProvider, режим замены оригиналов.
- **Независимая проверка**: очищенные файлы проверены ExifTool, c2pa-python/c2patool и ffprobe. Скрипты лежат в [`fixtures/_tools`](core/src/test/resources/fixtures/_tools).

CI запускает сборку, модульные тесты, lint и тесты на эмуляторе для каждого pull request.

## Участие в разработке

Issues и pull requests приветствуются — см. [CONTRIBUTING.md](CONTRIBUTING.md). **Не прикладывайте личные фото** к issues. Об уязвимостях сообщайте приватно — см. [SECURITY.md](SECURITY.md).

## Благодарности

**Стандарты**
- [C2PA](https://c2pa.org/specifications/)
- [IPTC Digital Source Type](https://cv.iptc.org/newscodes/digitalsourcetype/)
- ISO/IEC 14496-12 (ISO BMFF)
- [PNG Third Edition](https://www.w3.org/TR/png-3/)
- [контейнер WebP](https://developers.google.com/speed/webp/docs/riff_container)
- Unicode [UTS #51](https://unicode.org/reports/tr51/) и Default_Ignorable_Code_Point

**Исследования и похожие проекты** (только идеи, код не заимствовался)
- [guillaumemeyer/watermarks-remover](https://github.com/guillaumemeyer/watermarks-remover) — список default-ignorable символов
- [wiltodelta/remove-ai-watermarks](https://github.com/wiltodelta/remove-ai-watermarks) — обзор носителей метаданных
- [LeonardSEO/chatgpt-watermark-remover](https://github.com/LeonardSEO/chatgpt-watermark-remover) — скрытые символы в тексте чат-ботов

**Эталонные файлы и инструменты проверки**
- [contentauth/c2pa-rs](https://github.com/contentauth/c2pa-rs) (примеры, c2patool)
- [c2pa-python](https://github.com/contentauth/c2pa-python)
- [c2pa-node](https://github.com/contentauth/c2pa-node)
- [ExifTool](https://exiftool.org/) (Phil Harvey)
- [FFmpeg](https://ffmpeg.org/)
- [Pillow](https://python-pillow.org/) и [pillow-heif](https://github.com/bigcat88/pillow_heif)

**Библиотеки**
- [Kotlin](https://kotlinlang.org/) и [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines)
- [AndroidX](https://developer.android.com/jetpack/androidx): Core, Activity, Lifecycle, DataStore
- [Jetpack Compose](https://developer.android.com/compose) с Material 3 и Material Icons
- [Coil](https://github.com/coil-kt/coil)
- JUnit 4 и AndroidX Test

**Графика**
- Иконка и иллюстрации созданы для проекта с помощью OpenAI Codex (векторные ресурсы, исходники в [`design/`](design)).

Уведомления о сторонних материалах собраны в [NOTICE](NOTICE).

## Лицензия

[Apache License 2.0](LICENSE) © 2026 Dmitriy ([@izhddm](https://github.com/izhddm))
