# 결과 & 데모 (Results)

> 자동차 도메인 LLM 플랫폼 — end-to-end 동작 결과와 정량 지표. 면접/포트폴리오 캡스톤.
> 관련: [VEHICLE_ARCHITECTURE.md](VEHICLE_ARCHITECTURE.md) · [HYUNDAI_NEEDS_ROADMAP.md](HYUNDAI_NEEDS_ROADMAP.md)

---

## 1. 동작하는 풀 파이프라인 (완성)

```
질문(한국어/음성) → RAG 검색(매뉴얼 근거 grounding)
   → 파인튜닝 vehicle 모델(Qwen2.5-1.5B LoRA, Q4 온디바이스)이 한국어로 생성
   → 출처(sources) + 거버넌스(감사·PII) 로그 → (음성 TTS)
```

- ✅ 데이터 수집: NHTSA 리콜 119건(원본 JSON) + Internet Archive 정비 매뉴얼 PDF
- ✅ 자동차 RAG: `vehicle` 네임스페이스, 매뉴얼 근거 + **한국어 답변** (영어 코퍼스 → 한국어 강제)
- ✅ 리콜 text-to-SQL: 차종별 집계 (PALISADE 26 · SANTA FE 24 · ELANTRA 18 …)
- ✅ LoRA 파인튜닝: MLX, M2 로컬, GPU 없이
- ✅ 경량화: GGUF Q4 양자화 → Ollama 서빙 (온디바이스)
- ✅ 거버넌스: 멀티프로바이더 + 감사·PII (H-Chat 정합)
- ✅ 음성: 브라우저 STT/TTS (🎤 질문 / 🔊 듣기)
- ✅ UI: Next.js (7탭) + 모델 교체로 base↔FT 비교, 정적 UI 패리티
- ✅ **캡스톤 멀티툴**: 차종 종합 진단서(리콜+불만+매뉴얼) · 이미지진단(Vision+OCR+RAG)→필요부품(샘플견적)
- ✅ 멀티테이블 레지스트리(`recalls`·`complaints`·`parts`) + Agent 동적 테이블 선택 + SQL 자기수정

## 2. 정량 지표

**LoRA 파인튜닝 (Qwen2.5-1.5B, 122 train / 12 val, MLX iters 800)**
- Train loss 3.7 → 0.12, **Val loss 최저 1.54 @ iter 200** → 이후 상승(과적합) → **iter-200 체크포인트 채택**(early stopping).
- LLM-judge (n=12): base 10/12 vs **FT 11/12** (소량·약판정이라 노이즈 범위; 정성적으론 도메인 말투·퇴화 없음).

**추론 최적화 (양자화 트레이드오프, M2)**
| 정밀도 | 메모리 | tok/s | TTFT |
|---|---|---|---|
| Q8_0 | 1.8 GB | 4.7 | 0.88s |
| **Q4_K_M** | ~1.0 GB | **9.5** | **0.62s** |
→ Q4가 메모리 1/2·속도 2배 → 온디바이스 최적.

**RAG 도메인 동작 (예시)**
- 질문: "프리텐셔너 안전띠는 어떤 상황에서 작동하나요?"
- 근거: hyundai_2016_tucson_service.pdf #97 (Pre-tensioner Seat Belts / EFD 설명)
- 답변: 한국어, 매뉴얼 근거 기반 (grounding 확인).

## 3. 발견·판단 (엔지니어링 의사결정)

1. **데이터 품질이 9할** — 약한 생성모델(granite4)은 음역오류·환각("하다모"); qwen2.5:7b distillation + 한자 필터로 해결.
2. **Early stopping** — val loss 모니터링해 과적합 전(iter 200) 체크포인트 채택.
3. **양자화 폭주 제어** — Q4 단독은 반복/환각 → ChatML stop 토큰 + repeat_penalty로 제어.
4. **언어 강제** — 영어 코퍼스 + 한국어 사용자 → 프롬프트로 한국어 출력 강제.
5. **RAG = grounding** — 단독 FT는 환각("자석 안전띠"); RAG로 매뉴얼 근거 주입해 사실 교정.
6. **자원 직렬화** — 단일 M2에서 학습·추론 동시 = 메모리 초과 → 직렬화(운영은 노드 분리 근거).

## 4. 한계 & 개선 레버 (정직)

- 1.5B Q4 + 122 샘플 → 답변 문장이 거칠고 일부 영어/마커 잔재.
- **개선 순서**: ① 데이터 확대 ② 임베딩 파인튜닝(검색 정확도) ③ 더 큰 base/QLoRA ④ 리랭커 튜닝.
  (→ [RAG_COMPONENT_FINETUNING.md](RAG_COMPONENT_FINETUNING.md))

## 5. 현대차 정합 (왜 의미 있나)

- **H-Chat**(보안 LLM 게이트웨이) = 거버넌스(PII·감사·멀티프로바이더)
- **Nemotron**(자체 도메인 LLM) = LoRA 파인튜닝
- **Cerence/SDV**(차량 온디바이스 음성) = Q4 양자화 + 음성
- **A·S/품질** = 매뉴얼 RAG + 리콜 SQL
→ JD 키워드가 아니라 **현대차 실제 AI 스택을 작게 재현**.
