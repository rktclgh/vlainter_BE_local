#!/usr/bin/env python3
"""Stateless VlaInter -> Hermes generation proxy.

The Spring Boot app calls POST /generate with a compact provider payload.
This proxy translates that payload into Hermes' OpenAI-compatible
/v1/chat/completions endpoint and returns {"text": "..."}.
"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any


HOST = os.getenv("HERMES_PROXY_HOST", "0.0.0.0")
PORT = int(os.getenv("HERMES_PROXY_PORT", "8788"))
UPSTREAM_URL = os.getenv(
    "HERMES_UPSTREAM_URL",
    "http://127.0.0.1:8642/v1/chat/completions",
)
UPSTREAM_API_KEY = os.getenv("HERMES_UPSTREAM_API_KEY", "")
PROXY_API_KEY = os.getenv("HERMES_PROXY_API_KEY", "")
DEFAULT_MODEL = os.getenv("HERMES_MODEL", "vlainter-stateless-llm")
TIMEOUT_SECONDS = float(os.getenv("HERMES_PROXY_TIMEOUT_SECONDS", "120"))


def _json_response(handler: BaseHTTPRequestHandler, status: int, payload: dict[str, Any]) -> None:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


def _read_json(handler: BaseHTTPRequestHandler) -> dict[str, Any]:
    length = int(handler.headers.get("Content-Length", "0") or "0")
    raw = handler.rfile.read(length)
    if not raw:
        return {}
    return json.loads(raw.decode("utf-8"))


def _require_auth(handler: BaseHTTPRequestHandler) -> bool:
    if not PROXY_API_KEY:
        return True
    expected = f"Bearer {PROXY_API_KEY}"
    if handler.headers.get("Authorization", "") == expected:
        return True
    _json_response(handler, 401, {"error": "unauthorized"})
    return False


def _extract_text(payload: dict[str, Any]) -> str:
    choices = payload.get("choices")
    if isinstance(choices, list) and choices:
        first = choices[0]
        if isinstance(first, dict):
            message = first.get("message")
            if isinstance(message, dict) and isinstance(message.get("content"), str):
                return message["content"].strip()
            if isinstance(first.get("text"), str):
                return first["text"].strip()
    if isinstance(payload.get("text"), str):
        return payload["text"].strip()
    if isinstance(payload.get("output"), str):
        return payload["output"].strip()
    return ""


def _call_hermes(body: dict[str, Any]) -> dict[str, Any]:
    prompt = str(body.get("prompt") or "").strip()
    if not prompt:
        raise ValueError("prompt is required")

    upstream_payload = {
        "model": str(body.get("model") or DEFAULT_MODEL),
        "messages": [{"role": "user", "content": prompt}],
        "temperature": float(body.get("temperature", 0.2)),
        "max_tokens": int(body.get("maxTokens", 2048)),
        "stream": False,
    }
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json",
    }
    if UPSTREAM_API_KEY:
        headers["Authorization"] = f"Bearer {UPSTREAM_API_KEY}"

    request = urllib.request.Request(
        UPSTREAM_URL,
        data=json.dumps(upstream_payload, ensure_ascii=False).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    started = time.monotonic()
    with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
        response_body = response.read().decode("utf-8")
    elapsed_ms = int((time.monotonic() - started) * 1000)
    upstream_json = json.loads(response_body)
    text = _extract_text(upstream_json)
    if not text:
        raise RuntimeError("Hermes returned an empty text response")
    return {
        "text": text,
        "model": upstream_payload["model"],
        "profile": body.get("profile"),
        "elapsedMs": elapsed_ms,
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "vlainter-hermes-generate-proxy/1.0"

    def do_GET(self) -> None:
        if self.path == "/health":
            _json_response(self, 200, {"status": "UP", "upstream": UPSTREAM_URL})
            return
        _json_response(self, 404, {"error": "not_found"})

    def do_POST(self) -> None:
        if self.path != "/generate":
            _json_response(self, 404, {"error": "not_found"})
            return
        if not _require_auth(self):
            return
        try:
            result = _call_hermes(_read_json(self))
            _json_response(self, 200, result)
        except urllib.error.HTTPError as exc:
            details = exc.read().decode("utf-8", errors="replace")
            _json_response(self, exc.code, {"error": "upstream_http_error", "details": details})
        except Exception as exc:
            _json_response(self, 500, {"error": type(exc).__name__, "message": str(exc)})

    def log_message(self, fmt: str, *args: Any) -> None:
        sys.stderr.write("%s - %s\n" % (self.log_date_time_string(), fmt % args))


def main() -> None:
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"vlainter Hermes proxy listening on http://{HOST}:{PORT}/generate", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
