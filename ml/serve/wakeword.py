#!/usr/bin/env python3
"""
온디바이스 웨이크워드 — always-on 핸즈프리 트리거 (openWakeWord).

흐름: 마이크 상시 청취 → 웨이크워드("헤이 현대" 등) 감지 → STT(whisper_stt.py) 시작 → 질의.
오픈소스·온디바이스라 음성이 기기 밖으로 안 나감(데이터 주권, 차량 SDV 정합).

설치: pip install openwakeword sounddevice   # macOS: brew install portaudio (sounddevice 의존)
실행: python wakeword.py                       # 기본 프리빌트 모델로 데모
      WAKEWORD_MODEL=hey_jarvis python wakeword.py
      WAKE_WEBHOOK=http://localhost:8080/api/agent/ask python wakeword.py   # 감지 시 호출(선택)

⚠️ 커스텀 "헤이 현대"는 학습 필요(PoC 범위 밖):
   openWakeWord는 합성음성(TTS)으로 커스텀 단어를 학습한다(공식 Colab 학습 노트북).
   학습한 .onnx/.tflite를 WAKEWORD_MODEL 경로로 주면 그대로 동작한다.
   지금은 프리빌트 단어(hey_jarvis 등)로 파이프라인을 실증하고, 커스텀 학습은 로드맵.
"""
import os, sys, time

MODEL = os.environ.get("WAKEWORD_MODEL", "hey_jarvis")   # 프리빌트명 또는 커스텀 .onnx/.tflite 경로
THRESHOLD = float(os.environ.get("WAKEWORD_THRESHOLD", "0.5"))
WEBHOOK = os.environ.get("WAKE_WEBHOOK", "")             # 감지 시 호출할 URL(선택)
SR = 16000          # openWakeWord는 16kHz mono 요구
FRAME = 1280        # 80ms 프레임


def on_detected(name: str, score: float):
    print(f"\n🔔 웨이크워드 감지: {name} (score={score:.2f}) → 여기서 STT 시작/질의 트리거")
    if WEBHOOK:
        try:
            import json, urllib.request
            body = json.dumps({"question": "(음성 입력 대기)"}).encode()
            req = urllib.request.Request(WEBHOOK, data=body, method="POST",
                                         headers={"Content-Type": "application/json"})
            urllib.request.urlopen(req, timeout=5)
        except Exception as e:
            print(f"  webhook 실패: {e}", file=sys.stderr)


def main():
    try:
        import numpy as np
        import sounddevice as sd
        from openwakeword.model import Model
        from openwakeword.utils import download_models
    except ImportError as e:
        print(f"의존성 없음: {e}\n  pip install openwakeword sounddevice  (macOS: brew install portaudio)",
              file=sys.stderr)
        sys.exit(1)

    download_models()   # 프리빌트 모델 1회 다운로드(이미 있으면 skip)
    is_path = os.path.exists(MODEL)
    model = Model(wakeword_models=[MODEL]) if is_path else Model(wakeword_models=[MODEL])
    print(f"[wakeword] 청취 시작 (model={MODEL}, threshold={THRESHOLD}). Ctrl+C로 종료.")

    cooldown = 0
    with sd.InputStream(samplerate=SR, channels=1, dtype="int16", blocksize=FRAME) as stream:
        while True:
            audio, _ = stream.read(FRAME)
            scores = model.predict(audio.flatten())
            if cooldown > 0:
                cooldown -= 1
                continue
            for name, score in scores.items():
                if score >= THRESHOLD:
                    on_detected(name, score)
                    cooldown = int(SR / FRAME * 2)   # 2초 쿨다운(중복 트리거 방지)
                    break


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n[wakeword] 종료")
