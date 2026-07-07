#!/usr/bin/env python3
"""
크로스인코더 리랭커 사이드카 (BAAI/bge-reranker-base).

왜: 자바 DJL PyTorch 크로스인코더가 M2 맥에서 네이티브 폴백(재정렬 없이 top-K 반환)이라 실측 불가.
    sentence-transformers CrossEncoder는 맥 CPU에서 확실히 동작 → 실측 가능해진다.
    서빙(Java)·ML(Python) 분리 원칙대로 ML 사이드카에 둔다(whisper_stt.py와 동일 패턴).

모델: BAAI/bge-reranker-base — XLM-RoBERTa 기반 다국어 크로스인코더(한국어 매뉴얼에 적합).
    질의-문단 쌍을 함께 인코딩해 관련도 점수를 낸다(bi-encoder 벡터검색보다 정밀).

서버:  python reranker_sidecar.py --serve --port 8002
       POST /rerank  {"query": "...", "passages": ["...", "..."]}  → {"scores": [0.9, 0.1, ...]}
CLI:   python reranker_sidecar.py "질의" "문단1" "문단2"

설치:  pip install sentence-transformers    # 첫 실행 시 모델 자동 다운로드(~1.1GB)
성능:  M2 CPU에서 문단 20개 ~1초 내외. 점수는 순서 무관, passages 입력 순서대로 반환.
"""
import argparse, json, os, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODEL = os.environ.get("RERANKER_MODEL", "BAAI/bge-reranker-base")
MAX_LEN = int(os.environ.get("RERANKER_MAX_LEN", "512"))

_model = None


def get_model():
    """모델 1회 로드(load-once)."""
    global _model
    if _model is None:
        from sentence_transformers import CrossEncoder
        _model = CrossEncoder(MODEL, max_length=MAX_LEN)
    return _model


def rerank_scores(query, passages):
    """질의-문단 쌍마다 관련도 점수(높을수록 관련). 입력 순서 그대로 반환."""
    if not passages:
        return []
    pairs = [(query, p or "") for p in passages]
    scores = get_model().predict(pairs)   # numpy array
    return [float(s) for s in scores]


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self._send(200, {"status": "ok", "model": MODEL})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/rerank":
            self._send(404, {"error": "not found"})
            return
        try:
            n = int(self.headers.get("Content-Length", 0))
            req = json.loads(self.rfile.read(n) or b"{}")
            scores = rerank_scores(req.get("query", ""), req.get("passages", []))
            self._send(200, {"scores": scores})
        except Exception as e:
            self._send(500, {"error": str(e)})

    def log_message(self, *a):
        pass   # 조용히


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serve", action="store_true")
    ap.add_argument("--port", type=int, default=8002)
    ap.add_argument("rest", nargs="*")
    args = ap.parse_args()

    if args.serve:
        print(f"[reranker] loading {MODEL} …", file=sys.stderr)
        get_model()   # 시작 시 로드(첫 요청 지연 회피)
        print(f"[reranker] serving on :{args.port}", file=sys.stderr)
        ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
    elif len(args.rest) >= 2:
        q, passages = args.rest[0], args.rest[1:]
        print(json.dumps(dict(zip(passages, rerank_scores(q, passages))), ensure_ascii=False, indent=2))
    else:
        ap.print_help()


if __name__ == "__main__":
    main()
