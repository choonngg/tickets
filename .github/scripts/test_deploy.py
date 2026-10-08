"""Local checks: python3 .github/scripts/test_deploy.py (no Docker/server required)."""
import http.server
import json
import os
import re
from pathlib import Path
import subprocess
import tempfile
import threading
import textwrap
import unittest

SCRIPTS = Path(__file__).resolve().parent


def workflow_step(name):
    # Read the literal step block without requiring a YAML dependency in CI.
    workflow = (SCRIPTS.parent / "workflows/deploy.yml").read_text()
    block = workflow.split(f"      - name: {name}\n", 1)[1].split("\n      - name:", 1)[0]
    return block, textwrap.dedent(block.split("        run: |\n", 1)[1])


class SmokeTest(unittest.TestCase):
    def setUp(self):
        self.responses = {
            "/actuator/health": (200, {"status": "UP"}),
            "/": (200, '<html><body><div id="root"></div></body></html>'),
            "/api/concerts": (200, []),
            "/.well-known/jwks.json": (200, {"keys": [{"kty": "RSA"}]}),
            "/api/queue/.well-known/jwks.json": (200, {"keys": [{"kty": "RSA"}]}),
            "/api/auth/refresh": (401, {"code": "REAUTH_REQUIRED", "message": "Sign in again."}),
        }

    def exercise(self, run):
        result = run()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(result.stdout.count("PASS"), 6)
        for path, broken in [
            ("/", (200, "<html>missing root</html>")),
            ("/api/concerts", (503, {"error": "unavailable"})),
            ("/api/queue/.well-known/jwks.json", (200, "<html>SPA fallback</html>")),
            ("/api/auth/refresh", (401, {"code": "wrong", "message": "secret-marker"})),
            ("/actuator/health", (200, {"status": "DOWN"})),
        ]:
            original = self.responses[path]
            self.responses[path] = broken
            with self.subTest(path=path):
                result = run()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("FAIL", result.stdout)
                self.assertIn(path, result.stdout)
                self.assertIn("attempt 2/2", result.stdout)
                self.assertNotIn("secret-marker", result.stdout + result.stderr)
            self.responses[path] = original

        # A rollback health probe must work even without the current API contracts.
        original = self.responses.copy()
        self.responses.clear()
        for status in ("UP", "DOWN"):
            self.responses["/actuator/health"] = (200, {"status": status})
            result = run(True)
            self.assertEqual(result.returncode == 0, status == "UP", result.stdout + result.stderr)
            self.assertEqual(result.stdout.count("PASS"), int(status == "UP"))
            self.assertNotIn("/api/", result.stdout)
        self.responses.update(original)

    def run_smoke(self, url, env=None, health_only=False):
        return subprocess.run(
            ["bash", str(SCRIPTS / "smoke.sh"), *(["--health-only"] if health_only else []), url],
            env={**os.environ, "SMOKE_ATTEMPTS": "2", "SMOKE_DELAY": "0", **(env or {})},
            capture_output=True, text=True, timeout=15,
        )

    def test_http_contracts(self):
        responses = self.responses

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                status, body = responses[self.path]
                self.send_response(status)
                self.send_header("Content-Type", "text/html" if isinstance(body, str) else "application/json")
                self.end_headers()
                self.wfile.write((body if isinstance(body, str) else json.dumps(body)).encode())

            def do_POST(self):
                assert self.path == "/api/auth/refresh"
                assert self.headers.get("Cookie") is None
                self.do_GET()

            def log_message(self, *args):
                pass

        try:
            server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        except PermissionError as error:
            self.skipTest(f"Local HTTP socket blocked: {error}")
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            self.exercise(lambda health_only=False: self.run_smoke(
                f"http://127.0.0.1:{server.server_port}", health_only=health_only))
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_curl_stub_contracts(self):
        # Exercises the same script/contracts even when sandbox sockets are blocked.
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            curl = work / "curl"
            curl.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
from urllib.parse import urlsplit
args = sys.argv[1:]
path = urlsplit(args[-1]).path
assert args[args.index("--request") + 1] == ("POST" if path == "/api/auth/refresh" else "GET")
assert not any("cookie" in arg.lower() for arg in args)
status, body = json.loads(pathlib.Path(os.environ["RESPONSES"]).read_text())[path]
pathlib.Path(args[args.index("--output") + 1]).write_text(body if isinstance(body, str) else json.dumps(body))
print(str(status) + (" text/html" if isinstance(body, str) else " application/json"), end="")
''')
            curl.chmod(0o755)
            def run(health_only=False):
                (work / "responses.json").write_text(json.dumps(self.responses))
                return self.run_smoke("http://mock", {
                    "PATH": f"{work}:{os.environ['PATH']}", "RESPONSES": str(work / "responses.json"),
                }, health_only=health_only)
            self.exercise(run)


class DeployTest(unittest.TestCase):
    def test_workflow_image_selection(self):
        _, select = workflow_step("Select deployment images")
        validate, _ = workflow_step("Validate deployment secrets")
        self.assertIn("TICKET_PURCHASE_STRATEGY: ${{ secrets.TICKET_PURCHASE_STRATEGY || 'pessimistic' }}", validate)
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "outputs"
            for image_sha, expected in [("", "a" * 40), ("b" * 40, "b" * 40),
                                        ("latest", None), ("abcd", None), ("$(false)", None)]:
                with self.subTest(image_sha=image_sha):
                    output.write_text("")
                    result = subprocess.run(["bash", "-e", "-c", select], capture_output=True,
                                            text=True, env={**os.environ, "IMAGE_SHA": image_sha,
                                            "GITHUB_SHA": "a" * 40, "GITHUB_OUTPUT": str(output),
                                            "GITHUB_REPOSITORY_OWNER": "Example", "REGISTRY": "ghcr.io",
                                            "BACKEND_IMAGE_NAME": "backend", "NGINX_IMAGE_NAME": "nginx"})
                    self.assertEqual(result.returncode == 0, expected is not None)
                    if expected:
                        self.assertEqual(output.read_text(),
                                         f"backend-image=ghcr.io/example/backend:{expected}\n"
                                         f"nginx-image=ghcr.io/example/nginx:{expected}\n")
                    else:
                        self.assertEqual(output.read_text(), "")

    def test_strategy_validation(self):
        block, script = workflow_step("Validate deployment secrets")
        names = re.findall(r"^          ([A-Z0-9_]+):", block, re.MULTILINE)
        for strategy in ("", "pessimistic", "optimistic", "distributed",
                         "PESSIMISTIC", "Optimistic", "DISTRIBUTED", "pesimistic", " pessimistic "):
            with self.subTest(strategy=strategy):
                result = subprocess.run(["bash", "-e", "-c", script], capture_output=True, text=True,
                                        env={**os.environ, **dict.fromkeys(names, "hidden"),
                                             "TICKET_PURCHASE_STRATEGY": strategy}, timeout=5)
                self.assertEqual(result.returncode == 0,
                                 strategy.lower() in ("", "pessimistic", "optimistic", "distributed"),
                                 result.stdout + result.stderr)
                self.assertNotIn("hidden", result.stdout + result.stderr)
                if strategy == "pesimistic":
                    self.assertNotIn(strategy, result.stdout + result.stderr)

    def test_ci_and_manual_mode_wiring(self):
        ci = (SCRIPTS.parent / "workflows/ci.yml").read_text()
        self.assertIn("run: python3 .github/scripts/test_deploy.py", ci)
        _, script = workflow_step("Deploy application")
        self.assertIn("${{ inputs.image_sha != '' && 'manual-rollback' || 'deploy' }}", script)
        self.assertIn("backend/loadtest/", (SCRIPTS.parents[1] / ".dockerignore").read_text())

    def test_deploy_and_rollback(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            docker = work / "docker"
            docker.write_text('''#!/usr/bin/env bash
echo "$*" >> "$DOCKER_CALLS"
case "$*" in
  *pull*) [[ ${FAIL_PULL:-0} == 0 ]] ;;
  *"up -d"*)
    if grep -q backend:new .env; then exit "${FAIL_UP:-0}"; fi ;;
  *"ps -q"*)
    [[ ${FAIL_PS:-0} == 0 ]] || exit 1
    [[ ${EMPTY_PS:-0} == 0 ]] && echo "${@: -1}"
    exit 0 ;;
  *"ps --all"*) echo "failed-container-state" ;;
  *"logs --tail=200"*)
    echo "failed-container-logs $(sed -n 's/^BACKEND_IMAGE=//p' .env)"
    exit "${FAIL_LOGS:-0}" ;;
  inspect*)
    [[ ${FAIL_INSPECT:-0} == 0 ]] || exit 1
    service="${@: -1}"
    image=$(sed -n "s/^${service^^}_IMAGE=//p" .env)
    [[ ${FAIL_SHA:-0} == 0 ]] || image=wrong
    echo "true $image" ;;
esac
''')
            docker.chmod(0o755)
            (work / "smoke.sh").write_text('''#!/usr/bin/env bash
echo "smoke $*" >> "$DOCKER_CALLS"
if [[ ${1:-} == --health-only ]]; then exit "${FAIL_HEALTH:-0}"; fi
exit "${FAIL_SMOKE:-0}"
''')
            env = {**os.environ, "PATH": f"{work}:{os.environ['PATH']}", "DOCKER_CALLS": str(work / "calls")}
            for failure, previous, mode, expected in [
                ({}, True, "deploy", "Deployment passed"),
                ({}, True, "manual-rollback", "Deployment passed"),
                ({"FAIL_SHA": "1"}, True, "deploy", "Rollback succeeded"),
                ({"FAIL_SMOKE": "1"}, True, "deploy", "Rollback succeeded"),
                ({"FAIL_UP": "1"}, True, "deploy", "Rollback succeeded"),
                ({"FAIL_PS": "1"}, True, "deploy", "Rollback succeeded"),
                ({"FAIL_INSPECT": "1"}, True, "deploy", "Rollback succeeded"),
                ({"EMPTY_PS": "1"}, True, "deploy", "Rollback succeeded"),
                ({"FAIL_SMOKE": "1", "FAIL_LOGS": "1"}, True, "deploy", "Rollback succeeded"),
                ({"FAIL_SMOKE": "1", "FAIL_HEALTH": "1"}, True, "deploy", "Rollback failed"),
                ({"FAIL_SMOKE": "1"}, False, "deploy", "Rollback unavailable"),
                ({"FAIL_PULL": "1"}, True, "deploy", ""),
                ({"FAIL_SMOKE": "1"}, True, "manual-rollback", "automatic rollback disabled"),
                ({"FAIL_SHA": "1"}, True, "manual-rollback", "automatic rollback disabled"),
                ({"FAIL_PS": "1"}, True, "manual-rollback", "automatic rollback disabled"),
            ]:
                with self.subTest(failure=failure, previous=previous, mode=mode):
                    (work / "calls").write_text("")
                    for name in (".env", ".env.previous"):
                        (work / name).unlink(missing_ok=True)
                    old = "BACKEND_IMAGE=backend:old\nNGINX_IMAGE=nginx:old\nSECRET=hidden\n"
                    if previous:
                        (work / ".env").write_text(old)
                    new = "BACKEND_IMAGE=backend:new\nNGINX_IMAGE=nginx:new\nSECRET=hidden\n"
                    (work / ".env.next").write_text(new)
                    result = subprocess.run(
                        ["bash", str(SCRIPTS / "deploy.sh"), "backend:new", "nginx:new",
                         *([mode] if mode == "manual-rollback" else [])],
                        cwd=work, env={**env, **failure}, capture_output=True, text=True, timeout=5,
                    )
                    self.assertEqual(result.returncode == 0, not failure, result.stdout + result.stderr)
                    self.assertIn(expected, result.stdout)
                    calls = (work / "calls").read_text()
                    self.assertNotIn("hidden", result.stdout + result.stderr)
                    if not failure:
                        self.assertLess(calls.index("pull"), calls.index("up -d"))
                        self.assertLess(calls.index("inspect"), calls.index("smoke"))
                        self.assertLess(calls.index("smoke"), calls.index("image prune"))
                    else:
                        self.assertNotIn("prune", calls)
                        if "FAIL_PULL" in failure:
                            self.assertNotIn("up -d", calls)
                            self.assertEqual((work / ".env").read_text(), old)
                        else:
                            self.assertIn("failed-container-state", result.stdout)
                            self.assertIn("failed-container-logs backend:new", result.stdout)
                            self.assertEqual(calls.count("ps --all backend nginx"), 1)
                            self.assertEqual(calls.count("logs --tail=200 backend nginx"), 1)
                            if mode == "manual-rollback":
                                self.assertEqual((work / ".env").read_text(), new)
                                self.assertEqual(calls.count("up -d"), 1)
                                self.assertNotIn("--health-only", calls)
                                self.assertNotIn("attempting rollback", result.stdout)
                            elif previous:
                                self.assertEqual((work / ".env").read_text(), old)
                                self.assertEqual(calls.count("up -d"), 2)
                                self.assertEqual(calls.count("smoke --health-only"), 1)
                                self.assertEqual(result.stdout.count("attempting rollback"), 1)
                                self.assertLess(calls.index("logs --tail=200"), calls.rindex("up -d"))
                                self.assertLess(result.stdout.index("failed-container-logs"),
                                                result.stdout.index("attempting rollback"))


if __name__ == "__main__":
    unittest.main()
