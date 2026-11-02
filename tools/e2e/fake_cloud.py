#!/usr/bin/env python3
"""Provedor falso compatível com OpenAI, para testar o modo Nuvem sem chave e sem custo.

Responde com a primeira intent que reconhece no pedido e grava cada corpo recebido em --log, para
conferir o que realmente sairia do aparelho. Na mesma máquina do emulador, o app o alcança em
http://10.0.2.2:<porta>/v1 (só no build de debug).

uso: python3 tools/e2e/fake_cloud.py [--port 8123] [--log corpos.jsonl]
"""
import argparse
import json
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def decide(user):
    task = re.search(r"TASK: (.*)", user).group(1).lower()
    history = "HISTORY: none" not in user
    if history:
        return {"thought": "feito", "tool": "done", "args": {"ok": True, "msg": "Concluído (nuvem falsa)."}}
    if "alarme" in task:
        return {"thought": "alarme", "tool": "intent", "args": {"kind": "alarm", "value": "07:00"}}
    if "timer" in task:
        return {"thought": "timer", "tool": "intent", "args": {"kind": "timer", "value": "5 min"}}
    return {"thought": "sem regra", "tool": "done", "args": {"ok": False, "msg": "Pedido sem regra na nuvem falsa."}}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        ok = self.headers.get("Authorization") == f"Bearer {self.server.key}"
        self.send_response(200 if ok else 401)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_POST(self):
        raw = self.rfile.read(int(self.headers["Content-Length"]))
        body = json.loads(raw)
        with open(self.server.log, "a") as f:
            f.write(json.dumps({"auth_ok": self.headers.get("Authorization") == f"Bearer {self.server.key}", "body": body}, ensure_ascii=False) + "\n")
        if self.headers.get("Authorization") != f"Bearer {self.server.key}":
            self.send_response(401)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        text = json.dumps(decide(body["messages"][-1]["content"]), ensure_ascii=False)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        for i in range(0, len(text), 12):
            chunk = {"choices": [{"delta": {"content": text[i:i + 12]}}]}
            self.wfile.write(f"data: {json.dumps(chunk)}\n\n".encode())
            self.wfile.flush()
        self.wfile.write(b"data: [DONE]\n\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8123)
    parser.add_argument("--key", default="chave-de-teste")
    parser.add_argument("--log", default="fake_cloud.jsonl")
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.key, server.log = args.key, args.log
    server.serve_forever()


if __name__ == "__main__":
    main()
