# Leaflet GeoJSON GIS

Курсовой GIS-проект: просмотр точек, дорог и полигонов на карте, создание и удаление
пользовательских точек, расчёт геодезического расстояния. Проект готовится для
GitHub-портфолио; публичное развёртывание не требуется.

Сейчас используются Leaflet 1.9.4 и JavaScript, Java 25 / Spring Boot 3.5.7,
PostgreSQL/PostGIS. Переход на Svelte 5 + TypeScript + Vite —
[#21](https://github.com/Kenedy228/leaflet-geojson/issues/21).

## Быстрый запуск

Требуются полный JDK 25, Node.js 22, Python 3.10+, GNU Make, Docker и Docker Compose
с `up --wait` (2.20+). Первый запуск скачивает Maven/dependencies и PostGIS-образ.

```sh
make init-env
make dev
```

`init-env` создаёт приватную `.env` со случайным паролем и сохраняет существующую
конфигурацию. `dev` поднимает локальный PostGIS, ждёт готовности, применяет миграции
и небольшой demo-снимок OSM. Открой **http://localhost:8080**: frontend и API работают
с одного адреса. Для Leaflet/Bootstrap CDN и OSM-тайлов нужен интернет; demo-данные
уже включены в репозиторий и не скачиваются при старте.

По умолчанию сервер запрещает запись. Для локальной демонстрации редактирования
выполни `make init-editor`, перезапусти `make dev` и выбери «Войти как редактор».
Пароль читается локально из приватного `.env`; команда его не выводит.
[Модель доступа и CSRF](docs/access-control.md).

`Ctrl+C` останавливает приложение. `make db-down` останавливает БД, сохраняя данные.
Повторный `make dev` использует тот же volume и не дублирует demo-объекты.

Порты меняются в `.env` через `DB_PORT` и `SERVER_PORT`. Конфигурация читается как
данные: shell-подстановки не выполняются; окружение имеет приоритет. Внешний
`DB_URL` поддерживается командой `make run`; `make dev` всегда использует локальный
Compose-контур. Подробности, ограничения формата и все переменные — в
[infra/README.md](infra/README.md).

API URL в браузере — относительный `/api/v1/osm`, поэтому смена порта приложения
не требует изменения JavaScript. По умолчанию CORS-разрешения отсутствуют.
Для отдельного клиента список точных origins задаётся `CORS_ALLOWED_ORIGINS`;
формат и ограничения — в [инструкции CORS](infra/README.md#cors).

## Структура

```text
frontend/    Статический интерфейс Leaflet и frontend-регрессии
backend/     Spring Boot API, Flyway-миграции и интеграционные тесты
infra/       Compose, локальные команды, документация геоданных и CLI-тесты
docs/        Происхождение исходников и модель пользовательских точек
AGENTS.md    Правила работы с репозиторием и Git workflow
Makefile     Общие команды
```

## Команды

```sh
make run           # API/frontend без demo seeding, конфигурация из .env/окружения
make init-editor   # Включить локального редактора, пароль в приватном .env
make db-up         # Только локальная БД, ожидание healthcheck
make db-down       # Остановить БД, не удаляя volume
make db-reset-demo # Восстановить только demo reference-данные, сохраняя user points
make db-backup     # Приватная копия локальной БД в backups/
make check         # Синтаксис JS/Python и пробельные ошибки Git diff
make test-frontend # Node-регрессии без БД
make test-infra    # CLI unit-тесты без Docker
make install-browser # Установить зависимости браузерных тестов и Chromium
make test-browser  # Popup/XSS и пакетное сохранение, отдельные PostGIS и приложение
make test          # Все тесты, отдельный временный PostGIS создаётся автоматически
make verify        # Проверки, тесты и сборка JAR
make lint          # ESLint frontend и браузерных тестов
make format-check  # Prettier frontend и Spotless Java
make format        # Применить форматирование
make ci            # Lint, формат, verify и браузерные тесты
make build         # JAR без запуска тестов
```

Backend-тесты не используют dev-volume: каждый запуск получает отдельный Compose
project, tmpfs и случайный порт; после выполнения он удаляется. Никакого ручного
SQL перед `make verify` не требуется. Для внешнего тестового контура есть явный
`TEST_DB_URL`; не направляй его на личные/demo-данные.

Для изменений popup и UI сохранения точек дополнительно запускай `make test-browser`.
[Подготовка и границы браузерной проверки](docs/browser-tests.md).

[Контракт ошибок и валидации](docs/api-errors.md), [OpenAPI изменённых маршрутов](docs/openapi.json).

Собранный JAR — `backend/target/gis-0.0.1-SNAPSHOT.jar`. При прямом `java -jar`
параметры `DB_*` нужно предоставить окружением процесса: JVM сама `.env` не читает.
Без Make команды реализованы в `python3 infra/manage.py`; Java Wrapper находится
в `backend/`. Дополнительные флаги: `make verify MAVEN_ARGS='-B -ntp'`.

## Данные и сохранность

Пользовательские точки хранятся в `app.user_points` с UUID от БД, в EPSG:4326.
OSM-reference таблицы находятся в `public`, в EPSG:3857 и доступны API только
для чтения. Обновление/сброс demo не удаляет пользовательские записи.

Demo включает 3 точки, 2 дороги и 2 полигона из реального OSM-снимка Железногорска.
Данные © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright),
[ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/). Источник, версии объектов,
лицензия и правила необязательного полного импорта описаны в
[infra/README.md](infra/README.md). Лицензия программного кода пока не выбрана.

Для переноса старых пользовательских записей используй
[инструкцию явного legacy-переноса](docs/user-points.md).
Восстановление резервной копии заменяет состояние БД и требует остановки backend,
пути к доверенному файлу и `CONFIRM_RESTORE=YES`; процедура описана в infra.
Не удаляй volume для обычного сброса demo-данных.

## Если запуск не работает

- Проверь `JAVA_HOME`: нужен полный JDK 25, одного runtime недостаточно.
- Проверь Docker daemon и доступ пользователя к нему; порт БД публикуется только
  на localhost. Конфликт портов устраняется настройками `.env`.
- Для скачивания Maven нужен доступ к Maven Central и записываемый cache.
  Можно задать `MAVEN_USER_HOME` и `MAVEN_ARGS=-Dmaven.repo.local=/path/to/cache`.
- Ошибка demo seed с существующими reference-данными — защитный отказ.
  Для реального импорта используй `make run`, отдельную БД и соответствующий профиль
  из infra; чужие таблицы автоматически не очищаются.
- Восстановление/импорт должны выполняться с остановленным backend; пароль уже
  инициализированного PostgreSQL не меняется простым редактированием `.env`.

Полное состояние и оставшиеся задачи —
[roadmap #20](https://github.com/Kenedy228/leaflet-geojson/issues/20).
Доработка UI и Svelte ещё не завершены; это не заявление
о готовом публичном production-сервисе.
[Происхождение кода](docs/migration.md), [контракт точек](docs/user-points.md).

Проверки CI и обновление зависимостей: [docs/ci.md](docs/ci.md).
