#!/usr/bin/env python3
"""Loopback-only embedding API for SQLTeacher Cloud."""

from __future__ import annotations

import json
import math
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from fastembed import TextEmbedding


MODEL_NAME = os.environ.get("FASTEMBED_MODEL", "BAAI/bge-small-zh-v1.5")
MODEL_CACHE = os.environ.get("FASTEMBED_CACHE_PATH", "/var/lib/fastembed/models")
EXPECTED_DIMENSION = int(os.environ.get("FASTEMBED_DIMENSION", "512"))
PORT = int(os.environ.get("FASTEMBED_PORT", "11434"))
MODEL = TextEmbedding(model_name=MODEL_NAME, cache_dir=MODEL_CACHE, threads=2)
if TextEmbedding.get_embedding_size(MODEL_NAME) != EXPECTED_DIMENSION:
    raise RuntimeError("Configured FastEmbed dimension does not match the selected model")
INFERENCE_LOCK = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    server_version = "SQLTeacherFastEmbed/1"

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/readyz":
            self._json(200, {"status": "ok", "model": MODEL_NAME, "dimension": EXPECTED_DIMENSION})
            return
        if self.path == "/api/tags":
            self._json(200, {"models": [{"name": MODEL_NAME}]})
            return
        self._json(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/api/embed":
            self._json(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 2 or length > 2_500_000:
                raise ValueError("request size is invalid")
            request = json.loads(self.rfile.read(length))
            if request.get("model") != MODEL_NAME:
                raise ValueError("model is not available")
            values = request.get("input")
            texts = [values] if isinstance(values, str) else values
            if not isinstance(texts, list) or not 1 <= len(texts) <= 64:
                raise ValueError("input must contain 1 to 64 texts")
            if any(not isinstance(value, str) or not value.strip() or len(value) > 20_000 for value in texts):
                raise ValueError("input contains an invalid text")
            purpose = request.get("task", "passage")
            if purpose not in {"query", "passage"}:
                raise ValueError("task must be query or passage")
            with INFERENCE_LOCK:
                generator = MODEL.query_embed(texts) if purpose == "query" else MODEL.passage_embed(texts)
                embeddings = [self._normalized(vector.tolist()) for vector in generator]
            if len(embeddings) != len(texts) or any(len(vector) != EXPECTED_DIMENSION for vector in embeddings):
                raise RuntimeError("embedding result is invalid")
            self._json(200, {"model": MODEL_NAME, "embeddings": embeddings})
        except (ValueError, json.JSONDecodeError) as error:
            self._json(400, {"error": str(error)})
        except Exception:
            self._json(503, {"error": "embedding inference failed"})

    @staticmethod
    def _normalized(vector: list[float]) -> list[float]:
        if not vector or any(not math.isfinite(value) for value in vector):
            raise RuntimeError("embedding contains a non-finite value")
        norm = math.sqrt(sum(value * value for value in vector))
        if norm <= 0:
            raise RuntimeError("embedding norm is zero")
        return [value / norm for value in vector]

    def _json(self, status: int, value: object) -> None:
        body = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, pattern: str, *args: object) -> None:
        # The standard format logs only method/path/status; request bodies are never logged.
        super().log_message(pattern, *args)


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
