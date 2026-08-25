# What Fits? — RU prototype v0.0.6

**What Fits?** помогает определить модель принтера или МФУ и показывает совместимые расходники вместе с источником, подтверждающим совместимость.

```text
модель устройства → совместимый расходник → официальный источник
```

Текущая версия — работающий браузерный прототип на FastAPI и PostgreSQL для российского рынка.

## Что уже работает

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

1. Проверить OCR на реальных обезличенных фотографиях шильдиков и пополнить regression-набор сложными ракурсами, бликами и низким светом.
2. Разделить выбор типа расходника, когда у устройства доступны картридж и фотобарабан.
3. Добавить сохранение «Моих устройств» поверх уже подготовленной схемы БД.
4. Расширить RU-verified каталог моделями HP, Canon, Epson и Brother.
5. Добавить метрики качества OCR без хранения фотографий, сырого текста и серийных номеров.

## Поддержка README и версий

`README.md` является частью каждого изменения проекта. После изменений кода, API, интерфейса, схемы, зависимостей, каталога, команд запуска или версии README нужно проверить и обновить в том же изменении до отправки в GitHub.

При обновлении версии необходимо синхронно изменить:

- версию FastAPI в `backend/app/main.py`;
- версию в браузерном интерфейсе `backend/static/index.html`;
- версию на странице GitHub Pages `docs/index.html`;
- текущую версию и описание состояния в `README.md`.

## GitHub Pages

Папка `docs/` содержит отдельную минимальную страницу проекта. Для публикации: **Settings → Pages → Deploy from a branch → main → /docs**.
