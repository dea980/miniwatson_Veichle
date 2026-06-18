# Vehicle 확장 — 실행 런북 (직접 따라하기)

> 목표: 1주일 안에 JD 5개 항목(RAG·LoRA·경량화·Agent·InferenceAPI)을 동작 데모로.
> 전제: GPU 없음(맥). Base = Qwen2.5-1.5B-Instruct. 추론 = Ollama.

---

## STEP 0. 준비 (10분)

```bash
# 1) Python 가상환경 + ML 의존성
cd <repo>
python3 -m venv ml/.venv && source ml/.venv/bin/activate
pip install -U -r ml/requirements.txt        # 맥은 mlx-lm 설치됨

# 2) Ollama 실행 + 모델 (이미 쓰던 것)
ollama serve &                                # 별도 터미널이면 생략
ollama pull ibm/granite4:latest              # 데이터 생성/judge용 (이미 있으면 skip)

# 3) 앱 실행
./mvnw spring-boot:run                        # http://localhost:8080
```

## STEP 1. 데이터 — vehicle 코퍼스 (Day 1)

```bash
# 1) 매뉴얼 PDF 직접 다운로드 → data/vehicle/manuals/ 에 저장
#    소스: ml/data/sources.md  (현대 오너스매뉴얼 포털 등)
#    예: 아반떼/쏘나타/아이오닉 사용설명서 PDF 2~3개

# 2) 인제스트 (vehicle 네임스페이스)
bash ml/data/ingest_vehicle.sh

# 다시 깨끗이 하고 싶으면 (vehicle만 리셋):
bash ml/data/reset_vehicle.sh

# 확인: vehicle 문서 수
curl -s "http://localhost:8080/api/data/count?namespace=vehicle"
```

## STEP 2. RAG 도메인 점검 (Day 2)

```bash
# 1) 베이스 RAG 질의 테스트
curl -s -X POST http://localhost:8080/api/rag/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"P0420 코드가 뭐야?","namespace":"vehicle"}' | python3 -m json.tool

# 2) 도메인 평가셋 점수 (golden_vehicle.json)
#    ※ 인제스트 후 eval/golden_vehicle.json 의 expectTitleContains를 실제 매뉴얼 제목으로 교체
GOLDEN=eval/golden_vehicle.json python3 eval/run_eval.py            # recall
GOLDEN=eval/golden_vehicle.json python3 eval/run_eval.py --judge   # 답변 품질
```
> 약어/DTC 사전(`automotive-glossary.json`) wiring은 Java 측 1줄 작업 — 다음 커밋에서 같이.

## STEP 3. 데이터셋 생성 (Day 3)

```bash
python3 ml/data/build_dataset.py --n-per-chunk 2 --max-chunks 200
# → ml/data/out/train.jsonl, val.jsonl  ({"instruction","input","output"})
head -2 ml/data/out/train.jsonl
```

## STEP 4. LoRA 파인튜닝 (Day 4)

```bash
# 맥(Apple Silicon) — 로컬 학습
python3 ml/finetune/train_lora.py --backend mlx --iters 600
# → ml/finetune/adapters/

# 빠른 확인
python3 -m mlx_lm.generate --model Qwen/Qwen2.5-1.5B-Instruct \
  --adapter-path ml/finetune/adapters --prompt "P0420 코드가 뭐야?"

# base vs 파인튜닝 비교
python3 ml/finetune/eval_adapter.py --n 20 --judge

# 머지(양자화 준비)
python3 -m mlx_lm.fuse --model Qwen/Qwen2.5-1.5B-Instruct \
  --adapter-path ml/finetune/adapters --save-path ml/finetune/merged
```
> Intel 맥이면: `--backend hf` 로 무료 Colab(T4)에서 학습 후 adapters/merged만 내려받기.

## STEP 5. 경량화 + 벤치마크 (Day 5)

```bash
# llama.cpp 준비 (최초 1회)
git clone https://github.com/ggerganov/llama.cpp ~/llama.cpp && (cd ~/llama.cpp && make)

# 머지 모델 → GGUF Q4 → Ollama 등록
bash ml/optimize/quantize_gguf.sh            # → ollama 모델 'vehicle-qwen2.5-1.5b'

# 양자화 전후 벤치마크 (TTFT/tok·s/지연 → CSV)
python3 ml/optimize/benchmark.py \
  --models "ibm/granite4:latest,vehicle-qwen2.5-1.5b" --runs 5
```
> 기존 Grafana(docker-compose.monitoring.yml)에서 `miniwatson.llm.latency` p50/p95 before/after 패널.

## STEP 6. Agent + 배포 (Day 6) — 코드 작업

- 파인튜닝 모델을 앱에 연결: `.env` 의 `OLLAMA_CHAT_MODEL=vehicle-qwen2.5-1.5b`
- (옵션) `VllmLlmClient` provider 추가 → `llm.provider=vllm` (OpenAI 호환)
- `AgentController` 추가 → RAG검색 + text-to-SQL(리콜) 툴콜 = Agentic Search
> 이 단계 Java 코드는 내가 같이 작성.

## STEP 7. 마무리 (Day 7)

- README 로드맵 30–33 체크 + 데모 스크린샷/영상
- before/after 평가표(RAG recall, base vs FT, 양자화 지연) 정리 → 면접 자료

---

### 막히면
각 STEP 결과(출력/에러)를 그대로 붙여주면 다음 단계로 같이 진행.
