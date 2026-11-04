"""Read the application's public startup event and wait for usable dependencies."""

import json
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request


def events(log: Path) -> list[dict]:
    result = []
    for line in log.read_text(encoding="utf-8").splitlines():
        try:
            value = json.loads(line)
        except ValueError:
            continue  # JVM warnings are not application events.
        if isinstance(value, dict):
            result.append(value)
    return result


def wait_ready(base: str, timeout: float = 30) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(base + "/actuator/health/readiness", timeout=3) as response:
                if response.status == 200 and json.load(response) == {"status": "UP"}:
                    return
        except (OSError, urllib.error.URLError, ValueError):
            pass
        time.sleep(0.1)
    raise RuntimeError("Application readiness timed out")


def wait_application(process: subprocess.Popen, log: Path, timeout: float = 90) -> str:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError("Test application exited during startup; inspect startup_failed")
        for event in events(log):
            port = event.get("http_port")
            if event.get("event") == "application_ready" and type(port) is int and 0 < port < 65536:
                base = f"http://127.0.0.1:{port}"
                wait_ready(base, max(0, deadline - time.monotonic()))
                return base
        time.sleep(0.1)
    raise RuntimeError("Test application startup timed out")
