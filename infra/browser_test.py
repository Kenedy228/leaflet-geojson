"""Run real-browser regressions against an application and PostGIS owned by this run."""

import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile

import manage
from application_probe import wait_application


def main() -> None:
    env = dict(os.environ)
    # Never inherit a user's database, editor, profile or listener configuration.
    for key in list(env):
        if key.startswith(("DB_", "TEST_DB_", "SPRING_", "FLYWAY_", "EDITOR_", "EDITING_", "SERVER_", "CORS_", "MANAGEMENT_", "LOGGING_")):
            env.pop(key)
    for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        env.pop(key, None)
    env.update(COMPOSE_PROJECT_NAME="leaflet-geojson-browser-" + secrets.token_hex(4),
               DB_USER="gis", DB_NAME="gis_test", DB_PASSWORD=secrets.token_urlsafe(32),
               EDITING_ENABLED="true", EDITOR_USERNAME="browser-editor",
               EDITOR_PASSWORD=secrets.token_urlsafe(32), SPRING_PROFILES_ACTIVE="demo")
    process = None
    try:
        manage.compose(env, "--profile", "test", "up", "--detach", "--wait", "--wait-timeout", "90", "test-db")
        endpoint = manage.compose(env, "port", "test-db", "5432", capture_output=True, text=True).stdout.strip()
        env["DB_URL"] = f"jdbc:postgresql://127.0.0.1:{int(endpoint.rsplit(':', 1)[1])}/gis_test"
        manage.maven(env, "-DskipTests", "package")
        java = str(Path(env["JAVA_HOME"]) / "bin/java") if env.get("JAVA_HOME") else "java"
        with tempfile.TemporaryDirectory(prefix="leaflet-browser-") as directory:
            log = Path(directory) / "application.log"
            with log.open("w") as output:
                process = subprocess.Popen([java, "-jar", str(manage.ROOT / "backend/target/gis.jar"),
                                            "--server.port=0", "--server.address=127.0.0.1"],
                                           cwd=manage.ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
                base = wait_application(process, log)
                # Test-only SQL fault: never install this constraint in production migrations.
                manage.compose(env, "exec", "-T", "test-db", "psql", "-U", "gis", "-d", "gis_test",
                               "-v", "ON_ERROR_STOP=1", input="""
                    ALTER TABLE app.user_points ADD CONSTRAINT browser_test_batch_failure
                    CHECK (name <> 'browser-test-db-failure');
                    """, text=True)
                env.update(POPUP_TEST_ISOLATED="1", POPUP_TEST_BASE_URL=base,
                           POPUP_TEST_EDITOR_USERNAME=env["EDITOR_USERNAME"],
                           POPUP_TEST_EDITOR_PASSWORD=env["EDITOR_PASSWORD"])
                subprocess.run(["npm", "run", "test:browser"], cwd=manage.ROOT / "frontend", env=env, check=True)
    finally:
        failing = sys.exc_info()[0] is not None
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        try:
            manage.compose(env, "--profile", "test", "down", "--volumes")
        except subprocess.CalledProcessError:
            if not failing:
                raise
            print("Browser test project cleanup failed: " + env["COMPOSE_PROJECT_NAME"], file=sys.stderr)


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
