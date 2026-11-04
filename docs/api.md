# HTTP API и проверяемые примеры

Канонический контракт — [openapi.json](openapi.json), OpenAPI 3.1.
Он описывает геослои, запись точек, distance, сессию, формы входа/выхода и
health. Статические файлы UI не являются API ресурсов. Spring обслуживает
UI и API на одном origin; необязательный dev proxy описан в [frontend.md](frontend.md).

## Как читать контракт

- `/api/v1/osm/points`, `/roads`, `/polygons` возвращают массивы DTO. Поле
  `geojson` — строка с JSON геометрии EPSG:4326, не FeatureCollection и не
  вложенный объект. Внутри неё координаты идут `[lng, lat]`.
- Точки имеют строковый `id`: `osm:<osm_id>` или `user:<uuid>`, и `source`.
  Для записи используются именованные числовые поля `lat` и `lng`.
  `osm_id` дорог/полигонов в v1 остаётся JSON integer; клиентам нужен разбор
  без потери точности bigint. Встроенный UI не использует это число как ID записи.
- Distance принимает последовательность `[lng, lat]` и возвращает соседние
  `lines` плюс историческое поле **`totalDistance`**. Его camelCase сохраняется
  в v1; это не alias `total_distance`. Все расстояния — метры на сфероиде WGS84.
- Ошибки API содержат только `code` и безопасный `message`. Корреляция —
  заголовок `X-Request-ID`, а не дополнительное поле body. Ошибки HTTP до
  servlet-фильтров заголовок не гарантируют. [Ошибки](api-errors.md),
  [диагностика](diagnostics.md).
- Запись требует `EDITING_ENABLED=true`, сессию EDITOR и свежий CSRF.
  Одного cookie или видимой кнопки недостаточно. POST distance публичен и
  не требует CSRF, поскольку не изменяет данные. [Доступ](access-control.md).

## Чтение и расстояние

После `make dev` из корня репозитория:

```sh
API_BASE=http://localhost:8080
curl -i "$API_BASE/api/v1/osm/points?bbox=35.3,52.3,35.4,52.4&zoom=14"
curl -i "$API_BASE/api/v1/osm/roads?bbox=35.3,52.3,35.4,52.4&zoom=14"
curl -i "$API_BASE/api/v1/osm/polygons?bbox=35.3,52.3,35.4,52.4&zoom=14"
# 400 invalid_spatial_query:
curl -i "$API_BASE/api/v1/osm/roads?bbox=bad"

curl -i -H 'Content-Type: application/json' \
  --data '{"points":[[0,0],[1,0]]}' "$API_BASE/api/v1/osm/distances"
# 400 invalid_distance_request: нужна как минимум пара точек:
curl -i -H 'Content-Type: application/json' \
  --data '{"points":[[0,0]]}' "$API_BASE/api/v1/osm/distances"
```

Успешный distance имеет один сегмент от `[0,0]` до `[1,0]`; его длина и
`totalDistance` равны примерно 111319.490793 м. Независимый эталон и допуск
0.02 м описаны в [testing.md](testing.md). Эти координаты не зависят от demo.
`bbox` с west>east пересекает антимеридиан; лимиты действуют и без bbox.
При 413 сузь область вместо ожидания частичного успешного массива.
Политика деталей/лимитов — [spatial-queries.md](spatial-queries.md).

## Вход, создание и удаление

Сначала `make init-editor`, перезапусти `make dev`. Имя/пароль берутся из своей
приватной `.env`; файл не нужно исполнять как shell. Следующий пример запрашивает
реквизиты через терминал и хранит cookie, форму входа и CSRF во временном приватном
каталоге. Выполняй его в интерактивном shell; эти файлы не публикуй.

```sh
umask 077
API_EXAMPLE_DIR=$(mktemp -d)
trap 'rm -rf -- "$API_EXAMPLE_DIR"' EXIT
curl -fsS -c "$API_EXAMPLE_DIR/cookies" \
  "$API_BASE/api/v1/session" > "$API_EXAMPLE_DIR/session.json"

python3 -c '
import getpass, json, pathlib, sys, urllib.parse
p = pathlib.Path(sys.argv[1])
s = json.loads((p / "session.json").read_text())
form = urllib.parse.urlencode({"username": input("Имя редактора: "),
    "password": getpass.getpass("Пароль редактора: "), "_csrf": s["csrf_token"]})
(p / "login.form").write_text(form)
' "$API_EXAMPLE_DIR"

curl -sS -o /dev/null -b "$API_EXAMPLE_DIR/cookies" -c "$API_EXAMPLE_DIR/cookies" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-binary @"$API_EXAMPLE_DIR/login.form" "$API_BASE/login"
rm -f -- "$API_EXAMPLE_DIR/login.form"
# После login нужен новый CSRF. Сам redirect ещё не доказывает успешный вход.
curl -fsS -b "$API_EXAMPLE_DIR/cookies" -c "$API_EXAMPLE_DIR/cookies" \
  "$API_BASE/api/v1/session" > "$API_EXAMPLE_DIR/session.json"
python3 -c '
import json, pathlib, sys
p = pathlib.Path(sys.argv[1]); s = json.loads((p / "session.json").read_text())
if not s["can_edit"]: raise SystemExit("Вход не подтверждён: проверь реквизиты и режим редактора")
(p / "csrf.header").write_text(s["csrf_header"] + ": " + s["csrf_token"] + "\n")
' "$API_EXAMPLE_DIR"

# 201: полный пакет созданных точек, inserted и points.
curl -fsS -b "$API_EXAMPLE_DIR/cookies" -H @"$API_EXAMPLE_DIR/csrf.header" \
  -H 'Content-Type: application/json' \
  --data '[{"name":"Контракт","lat":52.34,"lng":35.35}]' \
  "$API_BASE/api/v1/osm/points/add" > "$API_EXAMPLE_DIR/created.json"
cat "$API_EXAMPLE_DIR/created.json"
API_POINT_ID=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["points"][0]["id"])' \
  "$API_EXAMPLE_DIR/created.json")

# 400 invalid_point_batch: пустой пакет.
curl -i -b "$API_EXAMPLE_DIR/cookies" -H @"$API_EXAMPLE_DIR/csrf.header" \
  -H 'Content-Type: application/json' --data '[]' "$API_BASE/api/v1/osm/points/add"
# 400 invalid_point_id: неверный UUID.
curl -i -X DELETE -b "$API_EXAMPLE_DIR/cookies" -H @"$API_EXAMPLE_DIR/csrf.header" \
  "$API_BASE/api/v1/osm/points/user:bad"
# 204 без тела, затем 404 point_not_found при повторе.
curl -i -X DELETE -b "$API_EXAMPLE_DIR/cookies" -H @"$API_EXAMPLE_DIR/csrf.header" \
  "$API_BASE/api/v1/osm/points/$API_POINT_ID"
curl -i -X DELETE -b "$API_EXAMPLE_DIR/cookies" -H @"$API_EXAMPLE_DIR/csrf.header" \
  "$API_BASE/api/v1/osm/points/$API_POINT_ID"
# 302 redirect выхода; не следуй ему с принудительным POST.
curl -sS -o /dev/null -X POST -b "$API_EXAMPLE_DIR/cookies" \
  -H @"$API_EXAMPLE_DIR/csrf.header" "$API_BASE/logout"
```

Ошибка отсутствующего/старого CSRF — 403 `invalid_csrf`; анонимная запись с
валидным CSRF — 401 `authentication_required`. В readonly запись запрещена
независимо от роли; OSM-точки всегда readonly. При неопределённом результате
POST после потери соединения сначала проверь слой: idempotency key в v1 нет.

## Что проверяется автоматически

Примеры запросов хранятся также в исполняемом [api-examples.json](api-examples.json):
чтение трёх слоёв, неверный bbox, distance и его неверные пакеты/типы, CSRF,
анонимный доступ, создание, неверное создание/ID, запрет OSM, удаление и повтор.
ID успешного создания подставляется в последующее удаление; credentials и CSRF
добавляются runner только при исполнении, в fixture их нет.

```sh
make check-openapi   # Структура OpenAPI, ссылки и schema-примеры, без Docker
make test-contract  # Собственный JAR/PostGIS: реальные ответы против OpenAPI
```

Проверка контракта не заменяет пространственные эталоны, rollback, browser UI
или outage-проверки: они остаются в `make ci`. Покрытие контракта — набор
проверенных сценариев; бизнес-ограничения Unicode и топологии дополнительно
проверяет backend. Инструменты и условия запуска — [ci.md](ci.md).
