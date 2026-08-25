# What Fits? — RU prototype 0.0.1

Первый технический фундамент проекта **What Fits?**: PostgreSQL-схема, формат seed-каталога, RU-specific seed и минимальный FastAPI.

## Что уже есть

- PostgreSQL schema v0.1 с `device → replacement → evidence`.
- Поиск моделей с `pg_trgm`.
- Хранение рынка (`RU`) отдельно от модели и детали.
- Статус совместимости и обязательное доказательство-источник.
- Минимальный слой `Мои устройства` и история замен.
- Seed из 50 моделей Pantum, для которых связи с расходниками взяты с официального российского сайта Pantum.
- API:
  - `GET /health`
  - `GET /v1/devices/search?q=P2500W`
  - `GET /v1/devices/{id}/replacements?market=RU`

## Почему seed пока только Pantum

Это сознательно. Цель первого seed — проверить **региональную модель данных**, а не имитировать полноту каталога. На российском сайте Pantum уже видны реальные региональные различия: например, P2500W использует PC-211P, а P2502/P2502W — PC-212EV. Поэтому HP/Canon/Epson/Brother стоит добавлять только после отдельной RU-проверки, а не переносить европейские коды расходников автоматически.

## Структура

```text
what-fits-prototype/
├── backend/
│   ├── app/main.py
│   └── requirements.txt
├── data/
│   ├── seed_format.schema.json
│   └── seed_ru_printers_v0.1.jsonl
├── db/
│   └── schema.sql
├── tools/
│   ├── load_seed.py
│   └── validate_seed.py
├── docker-compose.yml
└── README.md
```

## Быстрый запуск

```bash
docker compose up -d db
python -m venv .venv
# Linux/macOS:
source .venv/bin/activate
# Windows PowerShell:
# .\.venv\Scripts\Activate.ps1

pip install psycopg[binary] jsonschema
python tools/validate_seed.py

# Windows PowerShell:
$env:DATABASE_URL="postgresql://whatfits:whatfits@localhost:5432/whatfits"
python tools/load_seed.py

# API через Docker:
docker compose up -d api
```

Swagger:

```text
http://localhost:8000/docs
```

Пример:

```text
GET http://localhost:8000/v1/devices/search?q=P2500W
```

Берём `id` из ответа:

```text
GET http://localhost:8000/v1/devices/{id}/replacements?market=RU
```

## Seed format

Одна строка JSONL = одна точная модель устройства. Внутри перечислены только подтверждённые для RU replacement edges.

```json
{
  "brand": "Pantum",
  "category": "printer",
  "canonical_name": "Pantum P2500W",
  "model_code": "P2500W",
  "aliases": ["P2500 W", "Pantum P2500 W"],
  "market": "RU",
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

## Правило VERIFIED

`VERIFIED` разрешается только когда source tier позволяет это подтвердить. В seed v0.1 источник везде — официальный российский сайт производителя (`manufacturer_official`). Marketplace, отзывы и AI-ответ сами по себе не могут создавать `VERIFIED` edge.

## Следующий шаг

1. Добавить RU-verified HP.
2. Затем Canon/Epson/Brother.
3. После 150–200 моделей — сделать ручной Flutter-поиск.
4. Только затем добавить OCR/камеру.

## GitHub Pages

Папка `docs/` уже содержит минимальную страницу проекта. После загрузки репозитория на GitHub:

1. Settings → Pages.
2. Source: `Deploy from a branch`.
3. Branch: `main`, folder: `/docs`.

Отдельный домен для прототипа не нужен.
