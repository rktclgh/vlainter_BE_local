#!/usr/bin/env python3
"""Tiny stateless HTTP wrapper around Hermes CLI one-shot mode.

The Spring backend calls POST /generate with a prompt. This wrapper invokes
Hermes locally on the Ubuntu server and returns {"text": "..."}.
"""

from __future__ import annotations

import json
import os
import subprocess
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any


HOST = os.getenv("HERMES_ENDPOINT_BIND", "0.0.0.0")
PORT = int(os.getenv("HERMES_ENDPOINT_PORT", "8788"))
API_KEY = os.getenv("HERMES_API_KEY", "").strip()
CLI = os.getenv("HERMES_CLI", "/home/song/.hermes/hermes-agent/venv/bin/python")
MODULE = os.getenv("HERMES_CLI_MODULE", "hermes_cli.main")
PROVIDER = os.getenv("HERMES_CLI_PROVIDER", "openai-codex")
MODEL = os.getenv("HERMES_CLI_MODEL", "gpt-5.4-mini")
TIMEOUT_SECONDS = int(os.getenv("HERMES_CLI_TIMEOUT_SECONDS", "180"))
WORKDIR = os.getenv("HERMES_CLI_WORKDIR", "/home/song/Desktop/vlainter")


def _json_response(handler: BaseHTTPRequestHandler, status: int, payload: dict[str, Any]) -> None:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


def _read_body(handler: BaseHTTPRequestHandler) -> bytes:
    length_header = handler.headers.get("Content-Length")
    if length_header:
        return handler.rfile.read(int(length_header))

    transfer_encoding = handler.headers.get("Transfer-Encoding", "")
    if "chunked" not in transfer_encoding.lower():
        return b""

    chunks: list[bytes] = []
    while True:
        size_line = handler.rfile.readline().split(b";", 1)[0].strip()
        if not size_line:
            continue
        size = int(size_line, 16)
        if size == 0:
            handler.rfile.readline()
            break
        chunks.append(handler.rfile.read(size))
        handler.rfile.read(2)
    return b"".join(chunks)


class Handler(BaseHTTPRequestHandler):
    server_version = "vlainter-hermes-oneshot/1.0"

    def do_GET(self) -> None:
        if self.path.rstrip("/") == "/health":
            _json_response(self, 200, {"status": "UP", "provider": PROVIDER, "model": MODEL})
            return
        _json_response(self, 404, {"error": "not_found"})

    def do_POST(self) -> None:
        if self.path.rstrip("/") != "/generate":
            _json_response(self, 404, {"error": "not_found"})
            return

        if API_KEY:
            expected = f"Bearer {API_KEY}"
            if self.headers.get("Authorization", "") != expected:
                _json_response(self, 401, {"error": "unauthorized"})
                return

        try:
            payload = json.loads(_read_body(self).decode("utf-8") or "{}")
        except json.JSONDecodeError as exc:
            _json_response(self, 400, {"error": "invalid_json", "message": str(exc)})
            return

        prompt = str(payload.get("prompt", "")).strip()
        if not prompt:
            _json_response(self, 400, {"error": "invalid_request", "message": "prompt is required"})
            return

        system_prefix = (
            "You are a stateless inference endpoint for VlaInter. "
            "Do not use tools, do not persist memory, and return only the requested answer.\n\n"
        )
        full_prompt = system_prefix + prompt
        requested_model = str(payload.get("model") or MODEL).strip()

        command = [
            CLI,
            "-m",
            MODULE,
            "--oneshot",
            full_prompt,
            "--provider",
            PROVIDER,
            "--model",
            MODEL,
            "--ignore-rules",
        ]

        env = os.environ.copy()
        env.setdefault("HERMES_HOME", "/home/song/.hermes")
        env.setdefault("PYTHONUNBUFFERED", "1")

        try:
            completed = subprocess.run(
                command,
                cwd=WORKDIR,
                env=env,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=TIMEOUT_SECONDS,
                check=False,
            )
        except subprocess.TimeoutExpired:
            _json_response(self, 504, {"error": "timeout", "message": "Hermes one-shot timed out"})
            return

        if completed.returncode != 0:
            _json_response(
                self,
                502,
                {
                    "error": "hermes_failed",
                    "message": completed.stderr.strip()[-2000:],
                    "exitCode": completed.returncode,
                },
            )
            return

        text = completed.stdout.strip()
        _json_response(
            self,
            200,
            {
                "text": text,
                "model": requested_model,
                "runtimeProvider": PROVIDER,
                "runtimeModel": MODEL,
            },
        )

    def log_message(self, fmt: str, *args: Any) -> None:
        print(f"{self.address_string()} - {fmt % args}", flush=True)


def main() -> None:
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"vlainter Hermes one-shot endpoint listening on http://{HOST}:{PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
