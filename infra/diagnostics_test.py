"""Exercise packaged-process diagnostics using only this run's disposable PostGIS."""

from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from http.cookiejar import CookieJar
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

import manage
from application_probe import events, wait_application, wait_ready


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def request(base: str, path: str, *, method="GET", data=None, headers=None, opener=None):
    opener = opener or urllib.request.build_opener()
    req = urllib.request.Request(base + path, data=data, headers=headers or {}, method=method)
    try:
        response = opener.open(req, timeout=12)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        body = response.read()
        content_type = response.headers.get("Content-Type", "")
        return response.status, response.headers, json.loads(body) if content_type.partition(";")[0].endswith(("/json", "+json")) else body


@contextmanager
def application(env: dict[str, str], log: Path):
    java = str(Path(env["JAVA_HOME"]) / "bin/java") if env.get("JAVA_HOME") else "java"
    with log.open("w", encoding="utf-8") as output:
        process = subprocess.Popen([java, "-jar", str(manage.ROOT / "backend/target/gis-0.0.1-SNAPSHOT.jar"),
                                    "--server.port=0", "--server.address=127.0.0.1"],
                                   cwd=manage.ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
        try:
            yield process
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


def sql(env: dict[str, str], statement: str) -> str:
    return manage.compose(env, "exec", "-T", "test-db", "psql", "-U", "gis", "-d", "gis_test",
                          "-At", "-v", "ON_ERROR_STOP=1", input=statement, text=True,
                          capture_output=True).stdout.strip()


def wait_sql(env: dict[str, str], statement: str) -> None:
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        if sql(env, statement) == "t":
            return
        time.sleep(0.05)
    raise RuntimeError("Diagnostic test SQL synchronization timed out")


def correlated(log: Path, headers, status: int) -> str:
    request_id = headers.get("X-Request-ID", "")
    try:
        valid = str(uuid.UUID(request_id)) == request_id
    except ValueError:
        valid = False
    require(valid, "Response missing server UUID X-Request-ID")
    deadline = time.monotonic() + 3
    while time.monotonic() < deadline:
        matches = [e for e in events(log) if e.get("event") == "request_completed"
                   and e.get("request_id") == request_id]
        if matches:
            require(len(matches) == 1 and matches[0].get("status") == status,
                    "Request ID does not identify exactly one matching completion")
            return request_id
        time.sleep(0.05)
    raise RuntimeError("Response request ID absent from structured completion log")


def no_leaks(logs: list[Path], canaries: list[str]) -> None:
    for log in logs:
        content = log.read_text(encoding="utf-8")
        require(not any(value in content for value in canaries), "Private canary leaked into process output")
        require("\tat " not in content and '"error.stack_trace"' not in content,
                "Raw exception stack leaked into process output")


def failed_startup(env: dict[str, str], log: Path, reason: str) -> None:
    started = time.monotonic()
    with application(env, log) as process:
        try:
            code = process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            raise RuntimeError("Unavailable database startup exceeded 20 seconds") from None
        require(code == 1 and time.monotonic() - started < 20, "Startup failure was not bounded with exit 1")
    failures = [e for e in events(log) if e.get("event") == "startup_failed"]
    require(len(failures) == 1 and failures[0].get("reason") == reason,
            "Startup failed without exactly one safe categorical event")


def exercise(env: dict[str, str], directory: Path) -> None:
    log = directory / "application.log"
    logs = [log]
    private = "private-diagnostic-" + secrets.token_hex(8)
    incoming = str(uuid.uuid4())
    canaries = [env["DB_PASSWORD"], env["EDITOR_PASSWORD"], env["EDITOR_USERNAME"], env["DB_URL"], private, incoming]
    with application(env, log) as process:
        base = wait_application(process, log)
        for path in ("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"):
            status, headers, body = request(base, path + "?showDetails=always")
            require(status == 200 and body == {"status": "UP"}, "Healthy probe disclosed details or failed")
            correlated(log, headers, status)
        for path in ("", "/env", "/configprops", "/loggers", "/metrics", "/mappings", "/info", "/shutdown"):
            status, _, _ = request(base, "/actuator" + path)
            require(status == 404, "Non-health management endpoint exposed")
        status, headers, body = request(base, "/api/v1/osm/points?bbox=" + private,
                                        headers={"X-Request-ID": incoming})
        require(status == 400 and set(body) == {"code", "message"}, "Error envelope changed")
        require(correlated(log, headers, status) != incoming, "Untrusted incoming request ID accepted")
        payload = json.dumps([{"name": private, "lat": 52.3, "lng": 35.3}]).encode()
        status, headers, body = request(base, "/api/v1/osm/points/add", method="POST", data=payload,
                                        headers={"Content-Type": "application/json"})
        require(status == 403 and set(body) == {"code", "message"}, "Security error envelope changed")
        correlated(log, headers, status)
        for kind, path, extra in [("firewall", "/api/v1/osm/points;" + private, {}),
                            ("cors", "/api/v1/osm/points", {"Origin": "https://" + private + ".invalid", "Access-Control-Request-Method": "GET"}),
                            ("not-found", "/" + private, {})]:
            status, headers, body = request(base, path, headers=extra, method="OPTIONS" if kind == "cors" else "GET")
            require(status in (400, 403, 404) and set(body) == {"code", "message"},
                    f"{kind} error envelope changed (HTTP {status}, body {type(body).__name__})")
            correlated(log, headers, status)
        opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))
        _, _, session = request(base, "/api/v1/session", opener=opener)
        login = urllib.parse.urlencode({"username": env["EDITOR_USERNAME"], "password": env["EDITOR_PASSWORD"],
                                       "_csrf": session["csrf_token"]}).encode()
        request(base, "/login", method="POST", data=login, opener=opener)
        _, _, session = request(base, "/api/v1/session", opener=opener)
        require(session["can_edit"], "Diagnostic fixture login failed")
        sql(env, "ALTER TABLE app.user_points ADD CONSTRAINT diagnostic_failure CHECK (name <> '" + private + "');")
        status, headers, body = request(base, "/api/v1/osm/points/add", method="POST", data=payload, opener=opener,
                                        headers={"Content-Type": "application/json", session["csrf_header"]: session["csrf_token"]})
        require(status == 500 and set(body) == {"code", "message"}, "Real SQL failure did not return safe 500")
        fault_id = correlated(log, headers, status)
        require(any(e.get("event") == "request_failed" and e.get("request_id") == fault_id
                    for e in events(log)), "SQL failure lacks correlated safe error event")
        # The probe contract remains minimal for an authenticated editor as well.
        require(request(base, "/actuator/health", opener=opener)[2] == {"status": "UP"},
                "Authenticated health disclosed details")
        bad = directory / "bad-password.log"
        logs.append(bad)
        wrong_password = secrets.token_urlsafe(32)
        canaries.append(wrong_password)
        failed_startup(dict(env, DB_PASSWORD=wrong_password), bad, "database_authentication")
        manage.compose(env, "pause", "test-db")
        require(request(base, "/actuator/health/liveness")[2] == {"status": "UP"}, "DB outage broke liveness")
        started = time.monotonic()
        status, headers, body = request(base, "/actuator/health/readiness")
        require(status == 503 and body == {"status": "DOWN"} and time.monotonic() - started < 8,
                "DB outage readiness was not bounded status-only 503")
        correlated(log, headers, status)
        status, headers, body = request(base, "/api/v1/osm/points?bbox=35,52,36,53")
        require(status == 503 and set(body) == {"code", "message"}, "DB outage API was not safe 503")
        correlated(log, headers, status)
        for path, method, data in [("/api/v1/osm/points/user:00000000-0000-0000-0000-000000000001", "DELETE", None),
                                   ("/api/v1/osm/points/add", "POST", payload)]:
            status, headers, body = request(base, path, method=method, data=data, opener=opener,
                                            headers={"Content-Type": "application/json", session["csrf_header"]: session["csrf_token"]})
            require(status == 503 and set(body) == {"code", "message"},
                    "DB outage transaction was not safe 503")
            correlated(log, headers, status)
        unavailable = directory / "db-unavailable.log"
        logs.append(unavailable)
        failed_startup(env, unavailable, "database_unavailable")
        manage.compose(env, "unpause", "test-db")
        wait_ready(base)
        require(request(base, "/api/v1/osm/points?bbox=35,52,36,53")[0] == 200, "API failed to recover after DB recovery")
        # Hold the actual spatial table, then SIGTERM while the HTTP query waits on that lock.
        with ThreadPoolExecutor(max_workers=2) as pool:
            lock = pool.submit(sql, env, "BEGIN; LOCK TABLE public.planet_osm_point IN ACCESS EXCLUSIVE MODE; SELECT pg_sleep(8); COMMIT;")
            wait_sql(env, "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE relation='public.planet_osm_point'::regclass AND mode='AccessExclusiveLock' AND granted);")
            pending = pool.submit(request, base, "/api/v1/osm/points?bbox=35,52,36,53")
            wait_sql(env, "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%planet_osm_point%' AND pid<>pg_backend_pid());")
            started = time.monotonic()
            process.terminate()
            status, headers, body = pending.result(timeout=8)
            require(status == 503 and set(body) == {"code", "message"}, "Graceful shutdown abandoned in-flight SQL timeout response")
            correlated(log, headers, status)
            process.wait(timeout=15)
            require(time.monotonic() - started < 15, "Graceful shutdown exceeded bound")
            lock.result(timeout=10)
        require(any(e.get("event") == "application_stopping" for e in events(log)), "Shutdown event missing")
    no_leaks(logs, canaries)
    print("Process diagnostics passed: startup, correlation, SQL fault, probes, DB recovery, graceful shutdown; no private canaries")


def main() -> None:
    env = dict(os.environ)
    for key in list(env):
        if key.startswith(("DB_", "TEST_DB_", "SPRING_", "FLYWAY_", "EDITOR_", "EDITING_", "SERVER_", "CORS_", "MANAGEMENT_", "LOGGING_")):
            env.pop(key)
    for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        env.pop(key, None)
    env.update(COMPOSE_PROJECT_NAME="leaflet-geojson-diagnostics-" + secrets.token_hex(4),
               DB_USER="gis", DB_NAME="gis_test", DB_PASSWORD=secrets.token_urlsafe(32),
               EDITING_ENABLED="true", EDITOR_USERNAME="diagnostic-editor-" + secrets.token_hex(4),
               EDITOR_PASSWORD=secrets.token_urlsafe(32), SPRING_PROFILES_ACTIVE="demo")
    try:
        manage.compose(env, "--profile", "test", "up", "--detach", "--wait", "--wait-timeout", "90", "test-db")
        endpoint = manage.compose(env, "port", "test-db", "5432", capture_output=True, text=True).stdout.strip()
        env["DB_URL"] = f"jdbc:postgresql://127.0.0.1:{int(endpoint.rsplit(':', 1)[1])}/gis_test"
        manage.maven(env, "-DskipTests", "package")
        with tempfile.TemporaryDirectory(prefix="leaflet-diagnostics-") as directory:
            exercise(env, Path(directory))
    finally:
        failing = sys.exc_info()[0] is not None
        # A failed outage assertion must not leave the owned container paused.
        try:
            manage.compose(env, "unpause", "test-db", capture_output=True)
        except subprocess.CalledProcessError:
            pass
        try:
            manage.compose(env, "--profile", "test", "down", "--volumes")
        except subprocess.CalledProcessError:
            if not failing:
                raise
            print("Diagnostic test project cleanup failed: " + env["COMPOSE_PROJECT_NAME"], file=sys.stderr)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        sys.exit(error.returncode)
    except (RuntimeError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        sys.exit(130)
