# Проверки поведения GIS

## Воспроизводимый запуск

Нужны JDK 25, Node 22.23.4, Python 3.10+, Make и Docker Compose 2.20+.
Из корня чистого checkout:

```sh
make install-browser
# На Linux при отсутствии системных библиотек Chromium:
# npm --prefix frontend exec -- playwright install --with-deps chromium
make ci
```

`make ci` — полный набор: lint/формат, strict Svelte/TypeScript, Vitest, CLI,
backend с настоящим PostGIS и Flyway, Vite/JAR, Chromium против этого JAR.
`make verify` не включает Chromium; `make test-frontend` не требует Docker.
Для focused backend-проверки: `make test MAVEN_ARGS='-B -ntp -Dtest=SpatialApiTests'`.
Дополнительный `-Dmaven.repo.local=...` задаёт только cache Maven.

Backend и browser runner создают отдельные Compose projects с tmpfs и случайными
localhost-портами, запускают production-миграции и удаляют контур в `finally`.
Браузерный runner использует demo-данные и временного редактора; перехватывает
только тайлы и явно моделируемые HTTP-сбои. Схема БД не подменяется test DDL.
Тестовый CHECK для воспроизведения batch SQL-ошибки описан в
[browser-tests.md](browser-tests.md). Личные secrets/БД не нужны.
`TEST_DB_URL` — отдельный явный opt-in; не направляйте его на dev или личную БД.

## Карта критериев #16

| Критерий | Проверки и наблюдаемый результат |
| --- | --- |
| Реальные PostGIS/Flyway, повторный seed | [DemoDatasetTests](../backend/src/test/java/com/github/gis/DemoDatasetTests.java): миграции, повторное заполнение, защита чужого импорта и пользовательских записей; [infra/manage.py](../infra/manage.py) и [browser runner](../infra/browser_test.py) владеют временными контурами |
| Все поддерживаемые геометрии и WGS84 | [SpatialApiTests](../backend/src/test/java/com/github/gis/SpatialApiTests.java): Point, LineString, MultiLineString, Polygon с отверстием, MultiPolygon; сравниваются все координаты, структура колец/частей и свойства после чтения из EPSG:3857 |
| Геодезические метры, порядок и сумма сегментов | SpatialApiTests: независимые эталоны ниже, неизменённые `[lng,lat]` в `from/to`, все сегменты в порядке выбора и total; границы/невалидный ввод — [ApiValidationTests](../backend/src/test/java/com/github/gis/ApiValidationTests.java) |
| CRUD, отсутствующий ID, атомарность, конкурентные ID | [PointIdentityTests](../backend/src/test/java/com/github/gis/PointIdentityTests.java): создание/удаление, непрецизионные OSM-ID как строки, 24 конкурентных запроса, rollback при невалидной второй точке и SQL-ошибке; статусы — ApiValidationTests |
| Серверные права и HTTP-границы | AccessControlTests, ReadonlyAccessTests, EditingConfigurationTests, DefaultCorsTests, ConfiguredCorsTests, CorsConfigurationValidationTests и ApiErrorTranslationTests в [backend tests](../backend/src/test/java/com/github/gis): readonly, сессии/CSRF, запрет записи OSM, CORS и безопасные ошибки |
| Регрессии frontend | [model tests](../frontend/src/lib/__tests__/model.test.ts), [transport tests](../frontend/src/lib/__tests__/api.test.ts), [Leaflet lifecycle](../frontend/src/lib/__tests__/LeafletMap.test.ts): snapshots, поздние ответы, права/выход, подтверждение записи, преобразования и cleanup; [browser state regressions](../frontend/tests/browser/state-regressions.test.cjs) исполняют реальные UI-действия |
| Полный браузерный путь и XSS | [popup-xss.test.cjs](../frontend/tests/browser/popup-xss.test.cjs): создание → сохранение → reload → выбор → дистанция → успешное удаление → reload/проверка отсутствия в API. Название со злонамеренной разметкой остаётся текстом; [batch](../frontend/tests/browser/batch-save.test.cjs) и [errors](../frontend/tests/browser/api-errors.test.cjs) покрывают отказы |
| Единая команда/CI | `make ci`, [workflow](../.github/workflows/ci.yml); [FrontendResourcesTests](../backend/src/test/java/com/github/gis/FrontendResourcesTests.java) загружает реальные hashed assets и запрещает выдачу исходников/старых скриптов |

Пустой `contextLoads()` удалён: остальные интеграционные проверки уже запускают
Spring, а пустой тест не проверял отдельного пользовательского контракта.
Новые тесты не копируют уже покрытые проверки прав, rollback и генерации ID.

## Независимые эталоны расстояний

Контракт — кратчайшая геодезическая на **сфероиде WGS84**, метры. Total — сумма
последовательных сегментов, а не расстояние между первым и последним пунктом
и не маршрут по дорогам. Порядок API `[lng,lat]` отличается от аргументов
`GeographicLib.Inverse(lat1, lon1, lat2, lon2)`.
[PostGIS ST_Distance](https://www.postgis.net/docs/ST_Distance.html) различает
плоские единицы geometry, сферический расчёт и spheroid geography.

| Сценарий / координаты API | Эталонные длины сегментов, м | Зачем нужен |
| --- | --- | --- |
| `[0,0] → [1,0] → [1.25,0] → [0,0]` | 111319.49079327357; 27829.87269831839; 139149.36349159197 | Неравные участки замкнутого пути различают порядок, пропуск/повтор сегмента и подмену total расстоянием по концам; перестановка осей меняет результат |
| `[174.81,-41.32] → [-5.50,40.96]` | 19959679.267 | Опубликованный пример Wellington–Salamanca из [GeographicLib](https://geographiclib.sourceforge.io/html/python/examples.html); почти противоположные точки различают сфероид, сферу и неверные планарные расчёты |
| `[179.9,0] → [-179.9,0] → [-179.9,0]` | 22263.898158654716; 0 | Короткое пересечение антимеридиана и допустимый нулевой участок, без обхода Земли по 359.8° |

Экваториальные значения вычислены независимо аналитически: `a × π/180 × Δlng`,
где полуось WGS84 `a=6378137 м`, а Δlng выбирает кратчайшую дугу.
Параметры WGS84 также приведены в примере GeographicLib. Во время теста не
запускается второй `ST_Distance` для получения ожидаемого значения и не нужна
дополнительная геодезическая библиотека или внешний сетевой oracle.

Допуск — **0.02 м на сегмент и total**: он покрывает округление опубликованного
эталона до миллиметров и вычислительную погрешность, но различает типичные
ошибки проекции/осей/модели Земли. Это допуск проверки алгоритма на заданных
координатах, не обещание двухсантиметровой точности данных OSM или клика мыши.

Для геометрий входные WGS84 WKT и ожидаемые координаты заданы независимо; fixture
хранится в EPSG:3857 через `ST_Transform`, API должен вернуть EPSG:4326.
Сравнение всех компонент имеет допуск **1e-8 градуса** при стандартных 9 десятичных знаках GeoJSON;
не проверяется только первая вершина или наличие строки `coordinates`.
Fixture INSERT выполняется в транзакции с rollback, без удаления чужих строк.

## Границы результата

Mutation-проверки выполняются временной заменой production-вычисления/преобразования
и восстановлением исходника перед финальным CI. Они подтверждают чувствительность
assertions, а не заменяют обычный зелёный прогон. Это не обязательная мутация
каждой строки в CI и не цель 100% coverage.

Для #16 подтверждены отказы при удалении `::geography`, выборе сферы,
перестановке осей, пропуске `ST_Transform` у всех трёх слоёв, подмене total
последним сегментом и ответе DELETE204 без фактического удаления. Последний
случай обнаружен именно проверкой отсутствия точки в API, а не только
исчезновением маркера в оптимистичном UI. После восстановления исходников
запускается полный `make ci`.

Chromium и axe проверяют конкретные пользовательские сценарии. Полный аудит WCAG,
кроссбраузерная матрица, нагрузочная проверка, внешний tile-сервис и импорт
произвольного OSM-дампа в эти проверки не входят.
