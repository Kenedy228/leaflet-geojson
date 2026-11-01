"""Local Compose/Maven boundary; never sources .env as shell code."""

from collections.abc import Mapping
from datetime import datetime, timezone
import os
from pathlib import Path
import re
import secrets
import shlex
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SEED = ROOT / "backend/src/main/resources/db/demo/R__reference_dataset.sql"


def load_env(root: Path, inherited: Mapping[str, str] | None = None) -> dict[str, str]:
    env = dict(os.environ if inherited is None else inherited)
    path = root / ".env"
    if path.exists():
        for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if not raw.strip() or raw.lstrip().startswith("#"):
                continue
            key, separator, value = raw.partition("=")
            key = key.strip()
            if not separator or not re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*", key):
                raise ValueError(f"Invalid .env assignment on line {number}")
            tokens = shlex.split(value, comments=True)
            if len(tokens) > 1:
                raise ValueError(f"Quote the .env value on line {number}")
            env.setdefault(key, tokens[0] if tokens else "")
    for key, value in {"DB_HOST": "127.0.0.1", "DB_PORT": "5432", "DB_NAME": "gis",
                       "DB_USER": "gis", "SERVER_PORT": "8080"}.items():
        env.setdefault(key, value)
    return env


def init_env(root: Path) -> None:
    template = (root / ".env.example").read_text(encoding="utf-8")
    contents = template.replace("replace-with-your-local-password", secrets.token_urlsafe(32))
    try:
        fd = os.open(root / ".env", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        print("Existing .env preserved.")
        return
    with os.fdopen(fd, "w", encoding="utf-8") as output:
        output.write(contents)
    print("Created private .env with a generated database password.")


def compose(env: dict[str, str], *args: str, **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run(["docker", "compose", "--project-directory", str(ROOT),
                           "-f", str(ROOT / "infra/compose.yaml"), *args],
                          cwd=ROOT, env=env, check=True, **kwargs)


def require_password(env: dict[str, str]) -> None:
    if env.get("DB_PASSWORD", "") in ("", "replace-with-your-local-password"):
        raise ValueError("Set DB_PASSWORD or run make init-env first")


def maven(env: dict[str, str], *goals: str) -> None:
    wrapper = "mvnw.cmd" if os.name == "nt" else "./mvnw"
    subprocess.run([wrapper, *shlex.split(env.get("MAVEN_ARGS", "")), *goals],
                   cwd=ROOT / "backend", env=env, check=True)


def database_sql(env: dict[str, str], text: str) -> None:
    compose(env, "exec", "-T", "db", "psql", "-U", env["DB_USER"],
            "-d", env["DB_NAME"], "--single-transaction", "-v", "ON_ERROR_STOP=1",
            input=text, text=True)


def test_backend(env: dict[str, str], goal: str) -> None:
    env = env.copy()
    env.pop("SPRING_PROFILES_ACTIVE", None)
    if env.get("TEST_DB_URL"):
        env["DB_URL"] = env["TEST_DB_URL"]
        env["DB_USER"] = env.get("TEST_DB_USER", env["DB_USER"])
        env["DB_PASSWORD"] = env.get("TEST_DB_PASSWORD", env.get("DB_PASSWORD", ""))
        maven(env, goal)
        return
    # Unique project + random localhost port: no dev DB or concurrent test run is reused.
    env["COMPOSE_PROJECT_NAME"] = (env.get("COMPOSE_PROJECT_NAME", "leaflet-geojson")
                                   + "-test-" + secrets.token_hex(4))
    env["DB_PASSWORD"] = secrets.token_urlsafe(32)
    try:
        compose(env, "--profile", "test", "up", "--detach", "--wait",
                "--wait-timeout", "90", "test-db")
        endpoint = compose(env, "port", "test-db", "5432", capture_output=True,
                           text=True).stdout.strip()
        port = int(endpoint.rsplit(":", 1)[1])
        env["DB_URL"] = f"jdbc:postgresql://127.0.0.1:{port}/gis_test"
        maven(env, goal)
    finally:
        already_failing = sys.exc_info()[0] is not None
        try:
            compose(env, "--profile", "test", "down", "--volumes")
        except subprocess.CalledProcessError:
            if not already_failing:
                raise
            print("Temporary test project cleanup failed: " + env["COMPOSE_PROJECT_NAME"],
                  file=sys.stderr)


def backup(env: dict[str, str]) -> None:
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    path = Path(env.get("BACKUP_FILE") or f"backups/gis-{timestamp}.dump")
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "wb") as output:
            compose(env, "exec", "-T", "db", "pg_dump", "-U", env["DB_USER"],
                    "-d", env["DB_NAME"], "--format=custom", "--no-owner", "--no-acl",
                    stdout=output)
    except BaseException:
        path.unlink(missing_ok=True)
        raise
    print(f"Database backup saved to {path}")


def main() -> None:
    command = sys.argv[1] if len(sys.argv) == 2 else ""
    if command == "init-env":
        init_env(ROOT)
        return
    env = load_env(ROOT)
    if command in ("test", "verify"):
        test_backend(env, command)
        return
    require_password(env)
    if command == "up":
        compose(env, "up", "--detach", "--wait", "--wait-timeout", "90", "db")
    elif command == "down":
        compose(env, "stop", "db")
    elif command in ("run", "dev"):
        if command == "dev":
            compose(env, "up", "--detach", "--wait", "--wait-timeout", "90", "db")
            env["DB_URL"] = f"jdbc:postgresql://127.0.0.1:{env['DB_PORT']}/{env['DB_NAME']}"
            env["SPRING_PROFILES_ACTIVE"] = "demo"
        maven(env, "spring-boot:run")
    elif command in ("seed", "reset-demo"):
        database_sql(env, SEED.read_text(encoding="utf-8")
                     .replace("${demo_data_enabled}", "true"))
    elif command == "backup":
        backup(env)
    elif command == "restore":
        if env.get("CONFIRM_RESTORE") != "YES" or not env.get("BACKUP_FILE"):
            raise ValueError("Restore replaces the local DB: set BACKUP_FILE and CONFIRM_RESTORE=YES")
        with Path(env["BACKUP_FILE"]).open("rb") as source:
            compose(env, "exec", "-T", "db", "pg_restore", "-U", env["DB_USER"],
                    "-d", env["DB_NAME"], "--clean", "--if-exists", "--single-transaction",
                    "--no-owner", "--no-acl", stdin=source)
    else:
        raise ValueError("Unknown local infrastructure command")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError) as error:
        print(f"Local environment: {error}", file=sys.stderr)
        sys.exit(1)
    except subprocess.CalledProcessError as error:
        sys.exit(error.returncode)
    except KeyboardInterrupt:
        sys.exit(130)
