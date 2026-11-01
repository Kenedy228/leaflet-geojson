# Leaflet GeoJSON GIS

Курсовой GIS-проект: просмотр точек, дорог и полигонов на карте OpenStreetMap,
создание и удаление точек, расчёт геодезического расстояния между выбранными точками.
Проект готовится для GitHub-портфолио; публичное развёртывание не требуется.

Сейчас frontend использует JavaScript и Leaflet 1.9.4, backend — Java 25,
Spring Boot 3.5.7 и PostgreSQL/PostGIS. Переход на Svelte 5 + TypeScript + Vite
запланирован в [#21](https://github.com/Kenedy228/leaflet-geojson/issues/21).

## Структура

```text
frontend/    Статическая страница, стили и JavaScript карты
backend/     Maven-проект Spring Boot, API и интеграционные тесты
docs/        Происхождение исходников и документация
AGENTS.md    Правила работы с репозиторием
Makefile     Общие команды запуска и проверки
```

Spring Boot отдаёт frontend и `/api/v1/osm` с одного адреса. Maven копирует
HTML/CSS/JavaScript из `frontend/` в static resources приложения, включая JAR.
Отдельный сервер frontend на этом этапе не нужен.

## Требования

- JDK 25; проверь `java -version` и при необходимости настрой `JAVA_HOME`.
- GNU Make и POSIX shell для корневых команд; альтернативные Maven-команды ниже.
- Node.js 22 для проверок и frontend-регрессий; npm-зависимостей и отдельной frontend-сборки пока нет.
- Доступ к Maven Central при первом запуске. Maven 3.9.11 скачивается wrapper-скриптом.
- Подготовленная PostgreSQL/PostGIS БД с исходной OSM-схемой и данными.

**Ограничение текущего этапа:** полного создания БД, миграций OSM и seed пока нет
([#2](https://github.com/Kenedy228/leaflet-geojson/issues/2)). Flyway создаёт пользовательские
точки в отдельной схеме `app`; для чтения импортированных слоёв пустой PostgreSQL
недостаточен. API использует PostGIS, hstore и таблицы:

| Таблица | Используемые столбцы |
| --- | --- |
| `planet_osm_point` | `osm_id`, `name`, `way` |
| `planet_osm_roads` | `osm_id`, `name`, `highway`, `way` |
| `planet_osm_polygon` | `osm_id`, `name`, `tags` (hstore), `way` |

Столбцы `way` содержат геометрию. Импортированные таблицы должны иметь совместимые
типы и SRID. Новые пользовательские точки пишутся только в `app.user_points`
в EPSG:4326 с UUID, генерируемым БД; OSM-точки через API доступны только для чтения.
Реквизиты должны позволять Flyway создать схему `app` и её объекты.
Импорт OSM выполняется отдельно средствами БД; при его обновлении не сбрасывай `app`.
Старые пользовательские записи переносятся только по явно проверенным ID:
[модель и инструкция переноса](docs/user-points.md).

## Запуск

Из корня репозитория:

```sh
cp .env.example .env
```

Укажи в `.env` реквизиты своей локальной БД. Файл не коммитится. Затем экспортируй
переменные в текущую POSIX shell и запусти приложение:

```sh
set -a
. ./.env
set +a
make run
```

Файл `.env` автоматически не загружается Spring Boot или Make. Используй свой
локальный файл: команда `. ./.env` исполняет shell-содержимое. В PowerShell экспортируй
`DB_URL`, `DB_USER`, `DB_PASSWORD` и при необходимости `SERVER_PORT` через `$env:`.

Открой `http://localhost:8080` (либо порт `SERVER_PORT`). UI обращается к API
по относительному пути `/api/v1/osm`; другие hostname и порты не требуют правки JS.
Остановка — `Ctrl+C`. После изменения frontend перезапусти `make run`, чтобы
Maven скопировал обновлённые ресурсы.

Сборка и запуск JAR с теми же экспортированными переменными:

```sh
make build
java -jar backend/target/gis-0.0.1-SNAPSHOT.jar
```

Без Make: из `backend/` выполни `./mvnw spring-boot:run` или
`./mvnw -DskipTests package`. В Windows используй `mvnw.cmd`.

## Проверки

```sh
make check    # Синтаксис JavaScript и пробельные ошибки в Git diff
make test-frontend  # Регрессии ID черновиков, без БД
make test     # Frontend- и backend-тесты; нужна отдельная тестовая БД
make verify   # Проверки, тесты и сборка JAR
```

Для тестов нужна **отдельная пустая PostGIS-БД**, которую можно менять.
Подготовь тестовые OSM-таблицы (не выполняй это на личной/demo-БД):

```sh
psql -h localhost -p 5432 -U gis -d gis_test -v ON_ERROR_STOP=1 \
  -f backend/src/test/resources/fixtures/osm.sql
```

Экспортируй `DB_URL=jdbc:postgresql://localhost:5432/gis_test`, `DB_USER` и `DB_PASSWORD`.
Flyway создаст `app` при запуске тестов. Тесты проверяют раздачу ресурсов, контракт
идентификаторов, конкурентные вставки, удаление, транзакции и legacy-перенос.
Fixtures описывают старую схему для тестов, не заменяют production-миграции #2.
CI и автоматический контейнер тестовой БД пока не настроены; полный набор
поведения развивается в [#16](https://github.com/Kenedy228/leaflet-geojson/issues/16).

Без Make: проверку JS можно выполнить командой
`for file in frontend/*.js; do node --check "$file" || exit; done`;
frontend-регрессии — `node --test frontend/tests/*.test.cjs`;
из `backend/` доступны `./mvnw test` и `./mvnw verify`.

## Если запуск не работает

- Ошибка datasource: проверь экспорт `DB_PASSWORD`, адрес БД, порт, права и наличие
  схемы. Успешный старт приложения сам по себе не подтверждает наличие OSM-таблиц.
- `relation does not exist` или отсутствующая SQL-функция: проверь таблицы,
  расширения PostGIS/hstore и `search_path` пользователя БД.
- Ошибка скачивания Maven: нужны сеть до Maven Central и доступный для записи
  Maven cache. Для отдельного wrapper-cache можно задать `MAVEN_USER_HOME`;
  для зависимостей — `make build MAVEN_ARGS=-Dmaven.repo.local=/path/to/cache`.
- Не находится JDK 25: проверь `JAVA_HOME` и `PATH`.
- Не загружаются Leaflet/Bootstrap или тайлы: текущая страница использует CDN
  unpkg/jsDelivr и `tile.openstreetmap.org`; для них нужен интернет.

## Состояние проекта

Это этап объединения исходников, а не завершённая production-ready система.
Известные дефекты сохранения, управления состоянием, безопасного вывода и прав
на изменение данных описаны в [roadmap #20](https://github.com/Kenedy228/leaflet-geojson/issues/20).
Проверки объединения и способ переноса истории приведены в [docs/migration.md](docs/migration.md).
Текущая модель и контракт точек описаны в [docs/user-points.md](docs/user-points.md).

Исходники backend: [leaflet-geojson-backend](https://github.com/Kenedy228/leaflet-geojson-backend).
Карта использует данные © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright).
Восстановление видимой атрибуции в UI отслеживается в [#15](https://github.com/Kenedy228/leaflet-geojson/issues/15).
Лицензия кода пока не выбрана; лицензирование данных и сторонних компонентов
учитывается отдельно. Детальная архитектура и OpenAPI относятся к
[#19](https://github.com/Kenedy228/leaflet-geojson/issues/19), полное оформление
README — к [#22](https://github.com/Kenedy228/leaflet-geojson/issues/22).
