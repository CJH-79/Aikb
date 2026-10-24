#!/usr/bin/env python3
"""
本地 OpenAI 兼容服务，仅用于开发期验证链路，**不是可用的模型**。

提供两个端点：
  POST /v1/embeddings        返回基于字符 bigram 哈希的确定性向量（256 维）
  POST /v1/chat/completions  按 SSE 逐字返回一段由参考资料拼成的假答案

为什么需要它：
  1. 项目里的 embedding / chat 都走 OpenAI 兼容协议，没有 API Key 时
     整条链路（批量向量化、余弦检索、RRF 融合、SSE 流式、多轮记忆）无法验证。
     拿一个假服务把协议跑通，以后换成真模型只需要改 base-url。
  2. 向量是哈希出来的，**没有语义能力**，只能反映字面重合度。
     所以它能证明「链路通」，不能证明「语义召回有效」——
     后者必须配真的 embedding 模型（硅基流动 bge-m3 / 阿里 text-embedding-v3 / Ollama）。

用法：
  python tools/mock-openai-server.py --port 8090

配合后端启动：
  OPENAI_BASE_URL=http://127.0.0.1:8090 \
  OPENAI_API_KEY=mock \
  KB_AI_ENABLED=true \
  KB_EMBEDDING_ENABLED=true \
  mvn spring-boot:run

仅依赖 Python 标准库。
"""

import argparse
import hashlib
import json
import math
import re
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DIM = 256


def embed(text: str, dim: int = DIM):
    """把文本切成字符 bigram，带符号哈希到固定维度后做 L2 归一化。

    带符号哈希（signed hashing）是为了降低碰撞带来的系统性偏移：
    同一个 bigram 的贡献方向固定，不同 bigram 碰撞时正负相抵而不是叠加。
    """
    clean = re.sub(r"\s+", "", text or "")
    grams = [clean[i:i + 2] for i in range(len(clean) - 1)] or [clean or "空"]
    vec = [0.0] * dim
    for g in grams:
        h = int(hashlib.md5(g.encode("utf-8")).hexdigest(), 16)
        idx = h % dim
        sign = 1.0 if (h >> 17) & 1 else -1.0
        vec[idx] += sign
    norm = math.sqrt(sum(x * x for x in vec))
    if norm < 1e-9:
        return vec
    return [round(x / norm, 6) for x in vec]


def extract_refs(prompt: str):
    """从【参考资料】段落里抽出编号与正文，用来拼一个"看起来像在引用"的答案"""
    refs = []
    pattern = re.compile(r"\[(\d+)\]\s*来源：《(.+?)》第\s*(\d+)\s*页\n(.+?)(?=\n\n|\Z)", re.S)
    for m in pattern.finditer(prompt or ""):
        refs.append({
            "no": int(m.group(1)),
            "file": m.group(2),
            "page": int(m.group(3)),
            "content": m.group(4).strip(),
        })
    return refs


def make_answer(payload):
    messages = payload.get("messages", [])

    # 参考资料现在放在 system 消息里（见 AiAnswerService），
    # 所以要从全部消息里找，不能只看最后一条 user
    joined = "\n".join((m.get("content") or "") for m in messages)
    refs = extract_refs(joined)

    last_user = ""
    for m in reversed(messages):
        if m.get("role") == "user":
            last_user = m.get("content") or ""
            break

    # 除了最后这条 user，剩下的 user/assistant 都算历史
    history = sum(1 for m in messages if m.get("role") in ("user", "assistant")) - 1
    history = max(0, history)

    roles = ",".join(m.get("role", "?") for m in messages)
    print(f"  [mock] /chat 消息结构 = [{roles}]  历史轮次={history}  资料段={len(refs)}")
    print(f"  [mock] /chat 用户原话 = {last_user[:60]!r}")

    if not refs:
        return "根据现有资料无法回答该问题。（模拟模型：本轮没拿到参考资料）"

    first = refs[0]
    body = re.sub(r"\s+", "", first["content"])[:60]
    lines = [
        f"根据《{first['file']}》第 {first['page']} 页的内容，",
        f"{body}……[1]",
        "",
        f"（模拟模型：本轮参考资料 {len(refs)} 段，历史轮次 {history}）",
    ]
    return "\n".join(lines)


def sse_pack(obj):
    return ("data: " + json.dumps(obj, ensure_ascii=False) + "\n\n").encode("utf-8")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        print("  [mock] " + (fmt % args))

    def _read_body(self):
        """读请求体，必须同时支持 Content-Length 和 chunked。

        坑点：Spring AI 的 HTTP 客户端在批量 embedding 时用的是 chunked 传输
        （没有 Content-Length）。如果只按 Content-Length 读，会读到 0 字节，
        于是把一个 5 条文本的批量请求当成空请求处理——现象是
        「向量数量与切片数量不一致（1 vs 5）」，排查起来很费时间。
        """
        te = (self.headers.get("Transfer-Encoding") or "").lower()
        if "chunked" in te:
            data = b""
            while True:
                line = self.rfile.readline().strip()
                if not line:
                    continue
                size = int(line.split(b";")[0], 16)
                if size == 0:
                    self.rfile.readline()  # 末尾的 CRLF
                    break
                data += self.rfile.read(size)
                self.rfile.read(2)  # 每个 chunk 后面的 CRLF
            return data

        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length else b""

    def _read_json(self):
        raw = self._read_body()
        try:
            return json.loads(raw.decode("utf-8") or "{}")
        except Exception:
            return {}

    def _json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.rstrip("/") in ("/health", "/v1/models"):
            return self._json(200, {"object": "list", "data": [{"id": "mock-embed", "object": "model"}]})
        self._json(404, {"error": {"message": "not found: " + self.path}})

    def do_POST(self):
        path = self.path.split("?")[0].rstrip("/")
        payload = self._read_json()

        if path.endswith("/embeddings"):
            return self._embeddings(payload)
        if path.endswith("/chat/completions"):
            return self._chat(payload)
        return self._json(404, {"error": {"message": "not found: " + path}})

    def _embeddings(self, payload):
        raw = payload.get("input")
        texts = raw if isinstance(raw, list) else [raw or ""]
        data = [
            {"object": "embedding", "index": i, "embedding": embed(t)}
            for i, t in enumerate(texts)
        ]
        print(f"  [mock] embedding 批量条数 = {len(texts)}")
        return self._json(200, {
            "object": "list",
            "data": data,
            "model": payload.get("model") or "mock-embed",
            "usage": {"prompt_tokens": sum(len(t or "") for t in texts), "total_tokens": 0},
        })

    def _chat(self, payload):
        answer = make_answer(payload)
        model = payload.get("model") or "mock-chat"
        created = int(time.time())

        if not payload.get("stream"):
            return self._json(200, {
                "id": "chatcmpl-mock",
                "object": "chat.completion",
                "created": created,
                "model": model,
                "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": answer},
                    "finish_reason": "stop",
                }],
                "usage": {"prompt_tokens": 1, "completion_tokens": len(answer), "total_tokens": 0},
            })

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.end_headers()

        def chunk(delta, finish=None):
            return sse_pack({
                "id": "chatcmpl-mock",
                "object": "chat.completion.chunk",
                "created": created,
                "model": model,
                "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
            })

        try:
            self.wfile.write(chunk({"role": "assistant", "content": ""}))
            self.wfile.flush()
            # 按 3 个字符一节推送，制造真实的增量效果
            for i in range(0, len(answer), 3):
                self.wfile.write(chunk({"content": answer[i:i + 3]}))
                self.wfile.flush()
                time.sleep(0.018)
            self.wfile.write(chunk({}, "stop"))
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            print("  [mock] 客户端断开，停止推送")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8090)
    args = parser.parse_args()

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"mock OpenAI 服务已启动：http://127.0.0.1:{args.port}")
    print("  POST /v1/embeddings        （256 维，字符 bigram 哈希）")
    print("  POST /v1/chat/completions  （支持 stream=true 的 SSE）")
    print("提示：这是链路验证用的假服务，向量不具备语义能力。")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止")


if __name__ == "__main__":
    main()
