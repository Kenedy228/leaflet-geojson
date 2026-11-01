# Проверки монорепозитория

Workflow `CI` выполняет обязательное задание `Monorepo checks` для PR и push в
`stage`/`main`. Нет deployment, личных secrets или подключения к рабочей БД.
Локальный эквивалент после установки инструментов:

```sh
npm --prefix frontend ci --ignore-scripts
npm --prefix frontend exec -- playwright install --with-deps chromium
make ci MAVEN_ARGS='-B -ntp'
```

Нужны JDK 25 (в CI Temurin 25.0.5+8), Node 22.23.4
(`frontend/.node-version`), Python 3.10+, Make и Docker Compose 2.20+.
Runner — Ubuntu 24.04. Maven Wrapper закрепляет Maven 3.9.11 и проверяет SHA-256.
Версии npm-инструментов и транзитивные зависимости закреплены lockfile.

`make ci` последовательно выполняет:

1. ESLint Svelte/TypeScript и browser tests; Prettier Svelte/TS, HTML, CSS,
   JSON и тестов; Spotless/google-java-format 1.34.0 для Java.
2. `make verify`: Svelte/TypeScript strict, синтаксис Python, Git whitespace, Vitest/CLI tests,
   backend-тесты на настоящем PostGIS с production Flyway и сборка JAR.
3. `make test-browser`: отдельные временные PostGIS и приложение, Chromium,
   проверки API/UI, XSS и доступности.

Frontend собирается Vite в Maven generate-resources, только dist попадает в JAR.
`make format` применяет формат, `make lint` и `make format-check` только проверяют.
Подробности команд, dev proxy и модели состояния — [frontend.md](frontend.md).

PostGIS использует закреплённый digest из `infra/compose.yaml`, уникальные проекты,
tmpfs и случайные порты. Каждый контур удаляет ресурсы в `finally`; dev-volume не
используется. Браузерные тесты используют тот же локальный Vite bundle и
отключают загрузку тайлов. Пакетные репозитории нужны при установке инструментов,
внешние сервисы не нужны для выполнения функциональных тестов.

Ошибка любого обязательного шага делает job неуспешным; `continue-on-error` нет.
XML backend-отчётов сохраняются даже после ошибки тестов, JAR — только после
успешного job. Срок хранения — 7 дней. Отчёты Vitest/Chromium находятся в логе job;
приватные `.env`, дампы и логи приложения в artifacts не отправляются.
Actions закреплены полными SHA, token имеет только `contents: read`, checkout не
сохраняет credentials. Workflow не использует `pull_request_target`.

## Обновление зависимостей

Dependabot еженедельно проверяет Actions, `/frontend` npm и `/backend` Maven;
создаёт PR в `stage`, автоматического merge нет. Leaflet и Svelte входят в bundle; обновления проверяются тем же browser suite.
`minimatch` закреплён override 11.0.1 из-за GHSA-g3hv-75wg-m249 в зависимостях
ESLint; удалять override можно после перехода ESLint на исправленную версию.
Обновления JDK, Node, Maven Wrapper, formatter и PostGIS digest требуют отдельного
проверенного изменения конфигурации.

GitHub читает `.github/dependabot.yml` из **default branch**, сейчас это `main`.
После merge этого PR в `stage` CI работает, но плановые Dependabot PR начнут
создаваться только после переноса конфигурации в `main` обычным release PR.
`target-branch: stage` задаёт базу update PR, а не место чтения конфигурации.
Правила защиты веток не меняются этим workflow; для обязательного merge gate
в настройках GitHub нужно выбрать проверку `Monorepo checks`.

Dev-only advisory Svelte Vite plugin описан в [frontend.md](frontend.md#проверки-и-ограничения).
