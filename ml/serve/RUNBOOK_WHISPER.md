# 온디바이스 Whisper STT 런북

> 왜: 브라우저 Web Speech API는 사실상 클라우드 STT(음성이 외부로 나감). 로컬 Whisper로 **오프라인·프라이빗** 음성 인식 → 현대 Cerence/SDV 차량 온디바이스 음성 정합, 데이터 주권(H-Chat) 일관.
> 위치: ML 사이드카(`ml/serve/whisper_stt.py`) — 서빙(Java)과 분리 원칙대로 Python 쪽.

## 1. 설치 + 실행

```bash
pip install faster-whisper                 # M2: CPU int8로 동작
# (선택) 한국어 정확도↑: 더 큰 모델
WHISPER_MODEL=small python3 ml/serve/whisper_stt.py --serve --port 8001
# 기본(빠름): python3 ml/serve/whisper_stt.py --serve --port 8001
```
첫 실행 시 모델 자동 다운로드. 확인:
```bash
# 파일 한 개 빠른 테스트(CLI)
python3 ml/serve/whisper_stt.py sample.wav      # → {"text": "...", "lang": "ko"}
```

## 2. 프론트 연동 (브라우저 녹음 → 로컬 Whisper → 질문)

기존 Web Speech API(클라우드) 대신 **MediaRecorder로 녹음 → 로컬 Whisper 서버로 POST**. AskPanel에 드롭인할 스니펫:

```ts
async function recordAndTranscribe(): Promise<string> {
  const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
  const rec = new MediaRecorder(stream);
  const chunks: Blob[] = [];
  rec.ondataavailable = (e) => chunks.push(e.data);
  rec.start();
  await new Promise((r) => setTimeout(r, 4000));   // 4초 녹음(또는 버튼으로 stop)
  rec.stop();
  await new Promise((r) => (rec.onstop = r));
  stream.getTracks().forEach((t) => t.stop());

  const form = new FormData();
  form.append("audio", new Blob(chunks, { type: "audio/webm" }), "v.webm");
  const r = await fetch("http://localhost:8001/transcribe", { method: "POST", body: form });
  const { text } = await r.json();
  return text;   // → setQuestion(text); ask(text);
}
```

> 데모는 프론트가 `localhost:8001`을 직접 호출(서비스가 CORS 허용). 운영은 Java 게이트웨이가 프록시해 **거버넌스(감사·PII)를 통과**시키는 게 정석.

## 3. 설계 메모 (면접 포인트)

- **온디바이스 = 프라이버시·오프라인·무비용.** 음성이 기기를 떠나지 않음 → 차량/사내 환경(H-Chat) 적합.
- **load-once 모델 로드** — 서버 시작 시 1회 로드, 요청마다 재로드 안 함(추론 패턴 일관).
- **모델 크기 트레이드오프** — base(빠름) ↔ small/medium(한국어 정확도↑, 느림). 양자화 모델 선택 논리와 동일(INFERENCE_OPTIMIZATION.md).
- **VAD 필터**(`vad_filter=True`)로 무음 구간 제거 → 환각·지연↓.
- 한계: M2는 CPU int8 경로. 더 빠른 Metal 가속은 whisper.cpp(별도 바이너리) 경로 — 필요 시 교체 가능(서비스 인터페이스 동일 유지).

## 4. 로컬 TTS (Piper) — 완전 오프라인 음성 응답

브라우저 없는 오프라인 경로용(임베디드/차량/SDV). macOS 브라우저 데모는 브라우저 TTS(로컬, OS 음성)가 더 자연스러우니 그쪽 우선.

```bash
pip install piper-tts
# Piper 음성 모델(.onnx) 다운로드 후 경로 지정 (예: https://huggingface.co/rhasspy/piper-voices)
PIPER_MODEL=/path/voice.onnx python3 ml/serve/tts_piper.py --serve --port 8002
# CLI: PIPER_MODEL=/path/voice.onnx python3 ml/serve/tts_piper.py "안녕하세요" --out out.wav
# 호출: POST http://localhost:8002/speak  {"text":"..."} → audio/wav
```

> 정직한 한계: Piper의 한국어 음성은 영어 대비 선택지가 적다. 그래서 **맥 데모는 브라우저 TTS(로컬)** 를 쓰고, Piper는 "브라우저/OS 무의존 완전 오프라인"이 필요할 때의 경로다.

### 브라우저 TTS 메모 (글자 단위 낭독 버그)

브라우저 TTS가 글자/자모 단위로 읽히면: ① 한국어 voice 미지정(→ `getVoices()`에서 `ko` voice 명시), ② 마크다운 기호 낭독(→ 정리 후 낭독). 코드는 `frontend/components/AskPanel.tsx`, 배경은 [VOICE_ONDEVICE_VISION.md](../../docs/VOICE_ONDEVICE_VISION.md) 구현 메모.

## 5. 웨이크워드 (로드맵)

"헤이 현대" 핸즈프리는 openWakeWord/Porcupine 경로. 커스텀 단어("헤이 현대")는 학습이 필요해 PoC 범위 밖 — always-on 경량 감지 → 감지 시 STT 시작의 흐름만 설계로 남긴다([VOICE_ONDEVICE_VISION.md](../../docs/VOICE_ONDEVICE_VISION.md) 4절).

---

상세 배경: [../../docs/VOICE_ONDEVICE_VISION.md](../../docs/VOICE_ONDEVICE_VISION.md)
