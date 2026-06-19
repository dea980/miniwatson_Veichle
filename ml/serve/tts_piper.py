#!/usr/bin/env python3
"""
온디바이스 TTS (로컬·오프라인) — Piper.

왜: 완전 오프라인 음성 응답. 브라우저 SpeechSynthesis는 macOS에선 로컬이지만 OS/브라우저 의존 →
임베디드/차량(SDV)처럼 브라우저가 없는 환경엔 자체 TTS가 필요. Whisper(STT)와 짝.

두 모드:
  CLI:   python tts_piper.py "안녕하세요" --out out.wav
  서버:  python tts_piper.py --serve --port 8002
         POST /speak  {"text":"..."}  → audio/wav (바이너리)

설치: pip install piper-tts
모델: Piper .onnx 음성 모델 필요(별도 다운로드). 경로를 PIPER_MODEL로 지정.
  예) 한국어/영어 음성: https://huggingface.co/rhasspy/piper-voices
      PIPER_MODEL=/path/ko_KR-xxx.onnx
정직한 한계: Piper의 한국어 음성은 영어 대비 선택지가 적다. macOS 데모는 브라우저 TTS(로컬)도 충분하며,
  본 서비스는 "브라우저 없는 완전 오프라인" 경로용이다.
"""
import argparse, io, json, os, sys, wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODEL = os.environ.get("PIPER_MODEL", "")   # Piper .onnx 경로(필수)
_voice = None


def get_voice():
    """모델 1회 로드(load-once)."""
    global _voice
    if _voice is None:
        if not MODEL or not os.path.exists(MODEL):
            raise RuntimeError("PIPER_MODEL 환경변수로 Piper .onnx 경로를 지정하세요 "
                               "(예: PIPER_MODEL=/path/ko_KR-xxx.onnx).")
        from piper import PiperVoice
        print(f"[piper] loading {MODEL} …", file=sys.stderr)
        _voice = PiperVoice.load(MODEL)
    return _voice


def synth(text: str) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        get_voice().synthesize(text, wf)
    return buf.getvalue()


class Handler(BaseHTTPRequestHandler):
    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")

    def do_OPTIONS(self):
        self.send_response(204); self._cors(); self.end_headers()

    def do_GET(self):
        body = json.dumps({"status": "ok", "model": os.path.basename(MODEL) or "(미설정)",
                           "usage": 'POST /speak {"text":"..."} → wav'}, ensure_ascii=False).encode()
        self.send_response(200); self._cors()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.end_headers(); self.wfile.write(body)

    def do_POST(self):
        if self.path != "/speak":
            self.send_response(404); self._cors(); self.end_headers(); return
        n = int(self.headers.get("Content-Length", 0))
        try:
            text = json.loads(self.rfile.read(n) or b"{}").get("text", "").strip()
            if not text:
                raise ValueError("text 필요")
            wav = synth(text)
            self.send_response(200); self._cors()
            self.send_header("Content-Type", "audio/wav")
            self.end_headers(); self.wfile.write(wav)
        except Exception as e:
            msg = json.dumps({"error": str(e)}, ensure_ascii=False).encode()
            self.send_response(500); self._cors()
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.end_headers(); self.wfile.write(msg)

    def log_message(self, *a):
        pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("text", nargs="?")
    ap.add_argument("--out", default="out.wav")
    ap.add_argument("--serve", action="store_true")
    ap.add_argument("--port", type=int, default=8002)
    args = ap.parse_args()

    if args.serve:
        get_voice()
        print(f"[piper] TTS 서버 → http://localhost:{args.port}/speak")
        ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()
    elif args.text:
        with open(args.out, "wb") as f:
            f.write(synth(args.text))
        print(f"[piper] wrote {args.out}")
    else:
        ap.print_help()


if __name__ == "__main__":
    main()
