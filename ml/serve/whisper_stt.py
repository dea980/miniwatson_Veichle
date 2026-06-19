#!/usr/bin/env python3
"""
온디바이스 Whisper STT (로컬·오프라인·프라이빗).

왜: 브라우저 Web Speech API는 사실상 클라우드 STT(음성이 외부로 나감) → 데이터 주권/오프라인 불가.
faster-whisper로 음성을 **로컬에서** 텍스트화한다(현대 Cerence/SDV 차량 온디바이스 음성 정합).
서빙(Java)·ML(Python) 분리 원칙대로 ML 사이드카에 둔다.

두 모드:
  CLI:   python whisper_stt.py audio.wav
  서버:  python whisper_stt.py --serve --port 8001
         POST /transcribe  (multipart: audio=<파일>)  → {"text": "...", "lang": "ko"}

설치: pip install faster-whisper        # M2: CPU int8로 동작(Metal은 whisper.cpp 경로)
모델: 첫 실행 시 자동 다운로드. 한국어는 small/medium이 base보다 정확(느림).
"""
import argparse, json, os, sys, tempfile, cgi
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODEL_SIZE = os.environ.get("WHISPER_MODEL", "base")   # tiny|base|small|medium|large-v3
LANG = os.environ.get("WHISPER_LANG", "ko")

_model = None


def get_model():
    """모델 1회 로드(load-once). faster-whisper는 M2에서 CPU int8로 가볍게."""
    global _model
    if _model is None:
        from faster_whisper import WhisperModel
        print(f"[whisper] loading {MODEL_SIZE} (cpu/int8) …", file=sys.stderr)
        _model = WhisperModel(MODEL_SIZE, device="cpu", compute_type="int8")
    return _model


def transcribe(path: str) -> dict:
    segments, info = get_model().transcribe(path, language=LANG, vad_filter=True)
    text = "".join(s.text for s in segments).strip()
    return {"text": text, "lang": info.language}


class Handler(BaseHTTPRequestHandler):
    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")   # 데모용 — 운영은 프론트 오리진으로 제한
        self.send_header("Access-Control-Allow-Methods", "POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")

    def do_OPTIONS(self):
        self.send_response(204); self._cors(); self.end_headers()

    def do_GET(self):
        # 브라우저로 열면(GET) 헬스/사용법 안내 — POST /transcribe만 실제 동작
        if self.path in ("/", "/health"):
            self._json(200, {"status": "ok", "model": MODEL_SIZE, "lang": LANG,
                             "usage": "POST /transcribe (multipart: audio=<파일>)"})
        else:
            self._json(404, {"error": "POST /transcribe 만 지원"})

    def do_POST(self):
        if self.path != "/transcribe":
            self.send_response(404); self._cors(); self.end_headers(); return
        ctype, _ = cgi.parse_header(self.headers.get("Content-Type", ""))
        if ctype != "multipart/form-data":
            self._json(400, {"error": "multipart/form-data(audio) 필요"}); return
        form = cgi.FieldStorage(fp=self.rfile, headers=self.headers,
                                environ={"REQUEST_METHOD": "POST"})
        if "audio" not in form:
            self._json(400, {"error": "audio 파트 없음"}); return
        data = form["audio"].file.read()
        with tempfile.NamedTemporaryFile(suffix=".webm", delete=False) as tmp:
            tmp.write(data); tmp_path = tmp.name
        try:
            self._json(200, transcribe(tmp_path))
        except Exception as e:
            self._json(500, {"error": str(e)})
        finally:
            os.unlink(tmp_path)

    def _json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code); self._cors()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.end_headers(); self.wfile.write(body)

    def log_message(self, *a):  # 기본 액세스 로그 소음 제거
        pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("audio", nargs="?", help="CLI: 음성 파일 경로")
    ap.add_argument("--serve", action="store_true", help="HTTP 서버 모드")
    ap.add_argument("--port", type=int, default=8001)
    args = ap.parse_args()

    if args.serve:
        get_model()  # 시작 시 미리 로드(첫 요청 지연 방지)
        print(f"[whisper] STT 서버 → http://localhost:{args.port}/transcribe (model={MODEL_SIZE}, lang={LANG})")
        ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()
    elif args.audio:
        print(json.dumps(transcribe(args.audio), ensure_ascii=False))
    else:
        ap.print_help()


if __name__ == "__main__":
    main()
