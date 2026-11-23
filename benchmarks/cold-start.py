#!/usr/bin/env python3
"""Cold start: time to first diagnostics, one server process per file.

Starts the fat jar (`./gradlew :server:shadowJar`, build/libs/grails-language-server-all.jar) over stdio as an editor
starts it: `initialize` (trusted), `initialized`, then `didOpen` of one file at once. The time is the server's own
`startup: first diagnostics after N ms` mark (since `initialize`); the wall time from process start to the first
diagnostics of the file, which includes JVM start-up, is given too. An untimed first run fills the project-model
cache, so every timed run is a warm start.

Files: up to --files controllers (`*Controller.groovy`) and as many services (`*Service.groovy`) under `grails-app/`
of every module, spread evenly over the sorted list; each run --repeat times (for projects with few files, such as the
fixtures). Prints counts and timings only, never file names.

    python3 benchmarks/cold-start.py --project <built project> --java <project JDK>/bin/java [--jar ...] [--files 20]

The server runs Gradle with the JDK it runs on, so --java is the project's JDK. Run on mains power with sleep
prevented (`caffeinate -i` on macOS) and no other measurement running.
"""

import argparse
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import threading
import time

MARK = re.compile(r"startup: first diagnostics after (\d+) ms")
GROUPS = (("controllers", "Controller.groovy"), ("services", "Service.groovy"))
SKIP = {".git", ".gradle", "build", "node_modules", "out"}


def artefacts(project, suffix):
    found = []
    for root, dirs, files in os.walk(project):
        dirs[:] = sorted(d for d in dirs if d not in SKIP)
        path = pathlib.Path(root)
        if "grails-app" in path.parts:
            found += [path / f for f in files if f.endswith(suffix)]
    return sorted(found)


def sample(files, limit):
    if len(files) <= limit:
        return files
    return [files[i * len(files) // limit] for i in range(limit)]


class Server:
    """One server process and its JSON-RPC stream."""

    def __init__(self, java, jar, project, cache):
        self.started = time.monotonic()
        self.process = subprocess.Popen([java, "-jar", jar], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.DEVNULL, cwd=project)
        self.project = project
        self.cache = cache
        self.next_id = 0

    def send(self, message):
        body = json.dumps(dict(message, jsonrpc="2.0")).encode()
        self.process.stdin.write(b"Content-Length: %d\r\n\r\n" % len(body) + body)
        self.process.stdin.flush()

    def request(self, method, params):
        self.next_id += 1
        self.send({"id": self.next_id, "method": method, "params": params})
        return self.next_id

    def read(self):
        length = None
        while True:
            line = self.process.stdout.readline()
            if not line:
                return None
            line = line.strip()
            if not line:
                break
            name, _, value = line.decode().partition(":")
            if name.lower() == "content-length":
                length = int(value)
        return json.loads(self.process.stdout.read(length))

    def first_diagnostics(self, file, timeout):
        """Opens the file; returns (server mark ms, wall ms, errors), or None on timeout or exit."""
        timer = threading.Timer(timeout, self.process.kill)
        timer.start()
        try:
            self.request("initialize", {
                "processId": os.getpid(),
                "rootUri": self.project.as_uri(),
                "capabilities": {"workspace": {"didChangeWatchedFiles": {"dynamicRegistration": True}}},
                "initializationOptions": {"trusted": True, "cacheDir": str(self.cache)},
            })
            mark = wall = errors = None
            uri = file.as_uri()
            while True:
                message = self.read()
                if message is None:
                    return None
                method = message.get("method")
                if "id" in message and method is not None:
                    self.send({"id": message["id"], "result": None})  # a request to the client
                elif "id" in message and message["id"] == 1:
                    self.send({"method": "initialized", "params": {}})
                    self.send({"method": "textDocument/didOpen", "params": {"textDocument": {
                        "uri": uri, "languageId": "groovy", "version": 1, "text": file.read_text(encoding="utf-8")}}})
                elif method == "window/logMessage":
                    found = MARK.search(message["params"]["message"])
                    if found:
                        mark = int(found.group(1))
                elif method == "textDocument/publishDiagnostics" and message["params"]["uri"] == uri and wall is None:
                    wall = int((time.monotonic() - self.started) * 1000)
                    errors = sum(1 for d in message["params"]["diagnostics"] if d.get("severity") == 1)
                if mark is not None and wall is not None:
                    return mark, wall, errors
        finally:
            timer.cancel()

    def stop(self):
        try:
            self.request("shutdown", None)
            self.send({"method": "exit"})
            self.process.wait(30)
        except (OSError, subprocess.TimeoutExpired):
            self.process.kill()
            self.process.wait()


def run(args, cache, file):
    server = Server(args.java, args.jar, args.project, cache)
    try:
        return server.first_diagnostics(file, args.timeout)
    finally:
        server.stop()


def stats(values):
    values = sorted(values)
    if not values:
        return "-"
    p90 = values[min(len(values) - 1, (len(values) * 9 + 9) // 10 - 1)]
    return "%d / %d / %d" % (values[(len(values) - 1) // 2], p90, values[-1])


def main():
    here = pathlib.Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--project", type=pathlib.Path, required=True, help="a built Grails project")
    parser.add_argument("--java", default="java", help="java of the project's JDK (the server runs Gradle with it)")
    parser.add_argument("--jar", default=str(here / "build/libs/grails-language-server-all.jar"))
    parser.add_argument("--files", type=int, default=20, help="files per group (default 20)")
    parser.add_argument("--repeat", type=int, default=1, help="timed runs per file (default 1)")
    parser.add_argument("--timeout", type=int, default=600, help="seconds per run (default 600)")
    parser.add_argument("--budget", type=int, default=3000, help="ms for the 'within' count (default 3000)")
    args = parser.parse_args()
    args.project = args.project.resolve()

    groups = [(name, sample(artefacts(args.project, suffix), args.files)) for name, suffix in GROUPS]
    files = [f for _, group in groups for f in group]
    if not files:
        sys.exit("no controllers or services under grails-app/")
    with tempfile.TemporaryDirectory(prefix="gls-cold-start-") as cache:
        cache = pathlib.Path(cache)
        if run(args, cache, files[0]) is None:  # untimed: fills the project-model cache
            sys.exit("the first, untimed run got no diagnostics within %d s" % args.timeout)
        print("group: runs, server mark median / p90 / max ms, wall median / p90 / max ms, within %d ms (server mark), "
              "files with errors, runs without diagnostics" % args.budget)
        for name, group in groups:
            marks, walls, with_errors, failed = [], [], 0, 0
            for file in [f for f in group for _ in range(args.repeat)]:
                result = run(args, cache, file)
                if result is None:
                    failed += 1
                    continue
                marks.append(result[0])
                walls.append(result[1])
                with_errors += 1 if result[2] else 0
            within = sum(1 for m in marks if m <= args.budget)
            print("%s: %d, %s, %s, %d, %d, %d" % (name, len(marks), stats(marks), stats(walls), within, with_errors, failed))
            sys.stdout.flush()


if __name__ == "__main__":
    main()
