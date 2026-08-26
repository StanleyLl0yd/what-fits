# What Fits? — Android RU prototype v0.0.7

**What Fits?** помогает определить модель принтера или МФУ и показывает совместимые расходники вместе с источником, подтверждающим совместимость.

```text
модель устройства → совместимый расходник → официальный источник
```

Конечный продукт — нативное Android-приложение для публикации в RuStore. Web-интерфейс и FastAPI остаются вспомогательным прототипом и сервисом обновления каталога.

## Что уже работает

- нативный Android-клиент на Kotlin и Jetpack Compose;
- локальный поиск по встроенному RU-каталогу без подключения к интернету;
- CameraX для съёмки шильдика и выбор готовой фотографии;
- Tesseract4Android OCR с английской моделью, встроенной в APK;
- отсутствие runtime-зависимостей от Google Play Services, Firebase, ML Kit и Google Billing;
- единственное обязательное Android-разрешение — доступ к камере;
- Android unit-тесты, аудит зависимостей и автоматическая сборка debug APK в CI;
- браузерный интерфейс на русском языке по адресу `/`;
- единая точка поиска `GET /v1/fit`;
- точное определение модели по коду или алиасу;
- поиск модели внутри длинного OCR-подобного текста со шильдика;
- безопасная обработка неоднозначного и неуспешного поиска;
- вывод нескольких типов расходников, включая картриджи и фотобарабаны;
- PostgreSQL-схема `device → replacement → evidence`;
- региональная привязка совместимости к рынку `RU`;
- seed-каталог из 50 моделей Pantum;
- подтверждающая ссылка, издатель и дата проверки для каждой совместимости;
- unit-тесты логики API и правил каталога;
- интеграционные тесты всех seed-моделей на PostgreSQL 16;
- автоматический CI для каждого push и pull request;
- съёмка шильдика задней камерой телефона;
- выбор готового JPEG, PNG или WebP до 10 МБ;
- локальный предпросмотр, пересъёмка и удаление фотографии до распознавания;
- OCR шильдика через Tesseract в собственном FastAPI-контейнере;
- передача распознанного текста в ту же безопасную логику `/v1/fit`;
- автоматические проверки загрузки, приватности ответа и реального OCR в CI.

Фото остаётся в браузере до явного нажатия «Распознать модель». После нажатия оно отправляется только в собственный FastAPI, обрабатывается Tesseract в памяти и не сохраняется. Сырой OCR-текст не возвращается браузеру: API передаёт его в `/v1/fit` и отдаёт только безопасный результат `EXACT`, `AMBIGUOUS` или `NOT_FOUND`. Ручной ввод модели остаётся доступным всегда.

В Android сценарий строже: фото и распознанный текст вообще не покидают устройство. Приложение использует тот же исходный JSONL-каталог, который проверяется backend-тестами, поэтому ручной поиск, OCR и результат совместимости доступны без интернета. Интернет нужен только для открытия внешней страницы источника и будущего обновления каталога.

## Android и RuStore

Android-клиент находится в `android/` и проектируется под следующие обязательные условия:

- распространение через RuStore в виде подписанного APK или AAB;
- полноценный нативный интерфейс, а не WebView-обёртка;
- работоспособность на устройствах без Google Play Services;
- отсутствие Firebase, FCM, Google Billing, Google Analytics и Google In-App Updates;
- offline-first ядро: камера, OCR, ручной поиск и встроенный каталог работают без сети;
- фотографии, сырой OCR-текст и серийные номера не передаются на backend и не записываются в логи.

Сборка использует CameraX `1.6.1`, Tesseract4Android `4.9.0`, Compose BOM `2026.06.00`, AndroidX Core `1.17.0`, Lifecycle `2.9.4`, Android Gradle Plugin `9.2.0`, Gradle `9.4.1` и JDK 17. Версии AndroidX закреплены на совместимой с `compileSdk 36` линии. Английская `tessdata_fast`-модель загружается по закреплённому commit SHA во время сборки, проверяется по SHA-256 и включается в APK. После установки никакая OCR-модель не скачивается.

Открыть папку `android/` в Android Studio или собрать из командной строки:

```bash
./android/gradlew -p android --no-daemon \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:verifyNoGoogleRuntime \
  :app:assembleDebug
```

Для командной сборки нужны JDK 17, Android SDK Platform 36 и Build Tools 36.1.0. Версию Gradle 9.4.1 загрузит проектный Wrapper с проверкой SHA-256. Готовый файл появится в `android/app/build/outputs/apk/debug/app-debug.apk`. CI сохраняет его как артефакт `what-fits-v0.0.7-debug` на 14 дней.

## Главный принцип

Приложение не угадывает совместимость.

- Точное совпадение возвращает `EXACT` и совместимые расходники.
- Несколько точных или только похожие совпадения возвращают `AMBIGUOUS`; пользователь должен выбрать модель.
- Недостаточно надёжный запрос возвращает `NOT_FOUND`.
- Статус `VERIFIED` допускается только при наличии подходящего подтверждающего источника.

Marketplace, отзывы, поисковые сниппеты и ответы AI сами по себе не могут создавать `VERIFIED`-связь.

## Быстрый запуск

Требования: Docker с Compose и Python 3.12+.

Запустите базу данных:

```bash
docker compose up -d db
```

Подготовьте Python-окружение, проверьте и загрузите каталог:

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements-dev.txt
python tools/validate_seed.py
DATABASE_URL=postgresql://whatfits:whatfits@localhost:5432/whatfits python tools/load_seed.py
```

Соберите и запустите API с Tesseract:

```bash
docker compose up -d --build api
```

После запуска доступны:

- приложение: `http://localhost:8000/`;
- Swagger: `http://localhost:8000/docs`;
- проверка состояния: `http://localhost:8000/health`.

`db/schema.sql` выполняется автоматически только при создании нового PostgreSQL volume. Изменения этого файла не обновляют существующую базу сами по себе.

## Проверка

```bash
curl -fsS http://localhost:8000/health
curl -fsS "http://localhost:8000/v1/fit?q=P2500W&market=RU"
curl -fsS "http://localhost:8000/v1/fit?q=PANTUM%20P2500W%20220-240V%2050Hz&market=RU"
curl -fsS "http://localhost:8000/v1/fit?q=ZXQ999&market=RU"
```

Ожидаемый основной результат:

```text
P2500W → Pantum P2500W → PC-211P
```

Перед коммитом выполните базовые проверки:

```bash
python tools/validate_seed.py
python -m compileall -q backend tools tests
python -m pytest
```

При изменении Android-кода дополнительно выполните Android-команду из раздела выше. Она запускает JVM-тесты и Android Lint. Задача `verifyNoGoogleRuntime` проверяет обе runtime-конфигурации и останавливает сборку при появлении Google Play Services, Firebase, ML Kit или Google Billing. Собранный APK не объявляет `INTERNET` или `ACCESS_NETWORK_STATE`; камера остаётся единственным пользовательским разрешением.

Интеграционные тесты требуют уже инициализированную и загруженную тестовую базу:

```bash
RUN_DB_TESTS=1 DATABASE_URL=postgresql://whatfits:whatfits@localhost:5432/whatfits python -m pytest
```

Реальный OCR-тест дополнительно требует Tesseract и шрифт DejaVu:

```bash
RUN_DB_TESTS=1 RUN_OCR_TESTS=1 DATABASE_URL=postgresql://whatfits:whatfits@localhost:5432/whatfits python -m pytest
```

GitHub Actions выполняет полный сценарий автоматически на чистой PostgreSQL 16, распознаёт сгенерированный тестовый шильдик и проверяет сборку API-образа.

## API

| Метод | Путь | Назначение |
| --- | --- | --- |
| `GET` | `/health` | Проверка состояния API |
| `GET` | `/v1/fit?q=P2500W&market=RU` | Основной безопасный сценарий поиска |
| `POST` | `/v1/ocr?market=RU` | OCR изображения в теле запроса с его `Content-Type` и безопасный поиск совместимости |
| `GET` | `/v1/devices/search?q=P2500W&market=RU` | Список похожих моделей |
| `GET` | `/v1/devices/{id}/replacements?market=RU` | Расходники выбранной модели |

Сокращённый пример ключевых полей точного ответа `/v1/fit`:

```json
{
  "status": "EXACT",
  "query": "P2500W",
  "market": "RU",
  "device": {
    "brand": "Pantum",
    "canonical_name": "Pantum P2500W",
    "model_code": "P2500W"
  },
  "fits": [
    {
      "replacement_type": "toner_cartridge",
      "status": "VERIFIED",
      "part_number": "PC-211P",
      "source_publisher": "Pantum Russia"
    }
  ]
}
```

## Структура проекта

```text
what-fits/
├── .github/workflows/ci.yml           # GitHub Actions с PostgreSQL
├── AGENTS.md                         # правила работы с репозиторием
├── android/                          # нативное offline-first приложение для RuStore
│   ├── app/src/main/java/app/whatfits/
│   ├── app/src/test/                 # тесты локального каталога и matching
│   └── app/build.gradle.kts          # сборка, OCR-модель и GMS-аудит
├── backend/
│   ├── Dockerfile                    # API-образ с Tesseract OCR
│   ├── app/main.py                   # FastAPI и логика поиска
│   ├── static/index.html             # браузерный интерфейс
│   └── requirements.txt
├── data/
│   ├── seed_format.schema.json       # схема seed-записей
│   └── seed_ru_printers_v0.1.jsonl   # RU-каталог
├── db/schema.sql                     # PostgreSQL-схема
├── docs/
│   ├── DATA_NOTES.md                 # источники и ограничения данных
│   └── index.html                    # страница GitHub Pages
├── tools/
│   ├── demo_search.py
│   ├── load_seed.py
│   └── validate_seed.py
├── tests/                            # unit-, camera-, OCR- и integration-тесты
│   └── fixtures/camera/              # manifest будущих OCR-фотографий
├── docker-compose.yml
├── pytest.ini
├── requirements-dev.txt
└── README.md
```

Подробные обязательные инструкции для агентов находятся в [`AGENTS.md`](AGENTS.md).

## Формат каталога

Одна физическая строка JSONL соответствует одной точной модели устройства. Внутри перечислены только совместимости для явно указанного рынка.

```json
{
  "brand": "Pantum",
  "category": "printer",
  "canonical_name": "Pantum P2500W",
  "model_code": "P2500W",
  "aliases": ["P2500 W", "Pantum P2500 W"],
  "market": "RU",
  "source_checked_at": "2026-08-25",
  "replacements": [
    {
      "type": "toner_cartridge",
      "part_number": "PC-211P",
      "canonical_name": "Pantum PC-211P",
      "yield_pages": 1600,
      "status": "VERIFIED",
      "confidence": 1.0,
      "source": {
        "type": "manufacturer_official",
        "publisher": "Pantum Russia",
        "title": "Картридж PC-211P",
        "url": "https://www.pantum.ru/options-and-supplies/kartridzh-pc-211p/",
        "market": "RU",
        "checked_at": "2026-08-25"
      }
    }
  ]
}
```

Почему seed пока только Pantum: первый набор данных проверяет региональную модель и доказательность, а не имитирует полноту каталога. Например, для рынка РФ P2500W использует PC-211P, а P2502/P2502W — PC-212EV. Другие бренды следует добавлять только после отдельной проверки российских кодов расходников.

## Ближайшие направления

1. Установить CI-сборку APK на GMS-free эмулятор и реальный Android-телефон; проверить камеру на синтетических шильдиках, показанных на мониторе или распечатанных на бумаге.
2. Подготовить подписанную internal-сборку и чек-лист предварительной модерации RuStore.
3. Разделить выбор типа расходника, когда у устройства доступны картридж и фотобарабан.
4. Добавить безопасное обновление локального каталога с проверкой подписи и сохранением встроенного fallback-набора.
5. Расширить RU-verified каталог моделями HP, Canon, Epson и Brother.

## Поддержка README и версий

`README.md` является частью каждого изменения проекта. После изменений кода, API, интерфейса, схемы, зависимостей, каталога, команд запуска или версии README нужно проверить и обновить в том же изменении до отправки в GitHub.

При обновлении версии необходимо синхронно изменить:

- версию FastAPI в `backend/app/main.py`;
- версию в браузерном интерфейсе `backend/static/index.html`;
- `versionName` и `versionCode` Android-приложения;
- версию на странице GitHub Pages `docs/index.html`;
- текущую версию и описание состояния в `README.md`.

## GitHub Pages

Папка `docs/` содержит отдельную минимальную страницу проекта. Для публикации: **Settings → Pages → Deploy from a branch → main → /docs**.
