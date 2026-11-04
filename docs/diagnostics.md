# Запуск, ошибки и доступность

Приложение пишет JSON Lines в stdout. По умолчанию включены только безопасные
события приложения; SQL, JDBC URL, пароли, имена пользователей и точек, тела,
query string, cookie и CSRF не журналируются. Framework debug/trace отключены:
их включение может вывести SQL и конфигурацию. Не публикуй такие логи.

## Проверка запуска

После `make dev` ожидай событие `application_ready` с числовым `http_port`.
Оно означает завершение старта Spring; пригодность зависимостей проверяй отдельно:

```sh
curl -i http://localhost:8080/actuator/health/liveness
curl -i http://localhost:8080/actuator/health/readiness
curl -i http://localhost:8080/actuator/health
```

Используй фактический `SERVER_PORT`, если он изменён. У исправного приложения
все три ответа — HTTP 200 и `{"status":"UP"}`. Liveness проверяет процесс,
readiness и общий health — также получение подключения и `SELECT 1` в БД.
При потере БД liveness остаётся UP, readiness/health дают HTTP 503 и DOWN.
При отказе принимать трафик возможен OUT_OF_SERVICE (503); стандартный UNKNOWN
сохраняется как возможный статус Boot. Детали и компоненты скрыты всегда,
включая сессию редактора. Discovery, env, configprops, metrics, loggers, mappings
и shutdown не опубликованы; JMX выключен. Это минимальные probes без отдельной
системы мониторинга.

Если приложение завершилось до `application_ready`, найди единственный
`startup_failed`: процесс завершается с кодом 1. Поле `reason` содержит категорию:

| reason | Что проверить локально |
| --- | --- |
| `database_unavailable` | Запущен ли PostGIS, доступны ли хост/порт из `DB_URL` |
| `database_authentication` | Совпадают ли приватные DB_USER/DB_PASSWORD с созданной БД |
| `migration_failed` | Поддерживаемую схему/версию PostGIS и историю Flyway; не правь применённые миграции |
| `invalid_configuration` | Обязательные переменные и формат настроек, включая редактора |
| `server_bind_failed` | Свободен ли SERVER_PORT |
| `startup_failed` | Валидность конфигурации и версии инструментов; ошибка не относится к известным категориям |

После исправления параметров повтори `make dev` или `make run`. Смена пароля в
`.env` не меняет пароль уже созданного PostgreSQL volume. Не удаляй volume для
диагностики и не отправляй содержимое `.env` в issue. У работающего приложения
после восстановления доступности той же БД пул переподключается; проверяй
readiness и повтори запрос. `make dev` требует Compose, `make run` допускает
явную внешнюю `DB_URL`.

## Найти ошибку запроса

```sh
curl -i 'http://localhost:8080/api/v1/osm/points?bbox=invalid'
```

Ответ 400 содержит только публичные `code`/`message`. Заголовок `X-Request-ID`
содержит новый серверный UUID; найди этот UUID в JSON-журнале по `request_id`.
Входной заголовок с таким именем игнорируется. Корреляция охватывает также
отказы Security, CORS и firewall, которые дошли до servlet-фильтра приложения.
Ошибки HTTP-парсера до фильтров этого заголовка не гарантируют.
При разрешённом API CORS этот заголовок доступен браузеру; credentialed CORS
по-прежнему не включён. Контракт — [openapi.json](openapi.json).

| event | Поля и назначение |
| --- | --- |
| `application_ready` | `http_port`: сервер завершил запуск |
| `startup_failed` | `reason`: безопасная категория, ERROR |
| `application_stopping` | Началась остановка |
| `request_completed` | `request_id`, фиксированный `route`, `method`, `status`, `duration_ms` |
| `request_failed` | Тот же `request_id`, `reason=database_unavailable`, `database_timeout` или `internal_error`; WARN для БД, ERROR для внутренней ошибки |
| `database_unavailable` | Проверка БД завершилась неуспешно, WARN |

У `request_completed` уровень INFO для статусов до 400, WARN для 4xx, ERROR для
5xx. `route` — метка известного маршрута либо `other`; идентификаторы объектов
и неизвестные URL в журнал не попадают. Для сообщения об ошибке достаточно
request ID, HTTP-статуса и шагов воспроизведения с синтетическими данными.

## Таймауты и завершение

| Операция | Ограничение по умолчанию |
| --- | --- |
| Ожидание подключения Hikari | 2 с, validation — 1 с |
| PostgreSQL connect/socket/cancel signal | 3 / 5 / 1 с; TCP keepalive включён |
| Spatial SELECT, включая distance | query timeout 2 с |
| Транзакция создания/удаления точек | 5 с; отказ пакета откатывает все строки |
| Graceful shutdown | 10 с на фазу Spring lifecycle |
| Health SELECT 1 | query timeout 1 с; получение подключения отдельно ограничено пулом |

Это ограничения отдельных операций, а не единый deadline любого HTTP-запроса.
Отмена SQL и сеть добавляют время; таймаут транзакции не является принудительным
прерыванием произвольного Java-кода. Не задавай глобальный JdbcTemplate timeout:
он заменит специальный spatial timeout. Не увеличивай лимиты ради тяжёлой
выборки: уменьши bbox/детализацию согласно [spatial-queries.md](spatial-queries.md).

Для остановки JAR отправь SIGTERM его Java-процессу (при запуске в foreground —
Ctrl+C). Приложение пишет `application_stopping`, перестаёт принимать новые
запросы и даёт активным запросам завершиться в пределах graceful-фазы. Остановка
не удаляет данные. При проблеме БД запрос получает безопасную ошибку, а не SQL
или stack trace. `SIGKILL` обходит graceful shutdown.

## Автоматическая проверка

```sh
make test-diagnostics
```

Команда собирает JAR и создаёт собственный tmpfs PostGIS со случайными портом,
паролями и Compose project. Она игнорирует `.env`, внешние DB/TEST_DB,
редактора и профиль. Проверяет запуск, UUID и ошибки Security/валидации/SQL,
непубликацию management endpoints, health без деталей даже для редактора,
неверный пароль и недоступную БД при старте, outage/recovery, SIGTERM при
заблокированном spatial SELECT и отсутствие секретных canary-значений во всём
stdout/stderr. Для outage контейнер временно **приостанавливается**, затем
возобновляется: tmpfs-данные сохраняются. Финальная очистка удаляет только этот
тестовый проект. Логи приложения и пароли не отправляются в artifacts.

`make ci` включает эту проверку; `make verify` сам по себе её не запускает.
Не воспроизводи остановку БД или искусственные SQL-ошибки на личных данных.
