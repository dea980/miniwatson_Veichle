# 가이드 — 리랭커 실측 (mmr vs 크로스인코더 A/B)

리랭커가 검색 정확도를 올리는지 **직접 측정**하는 절차. 결론은 감이 아니라 RAGAS ctx-precision delta로 낸다.
배경·진단은 [`RAG-ACCURACY-ROADMAP.md`](RAG-ACCURACY-ROADMAP.md) §진단 참고(정밀도 문제로 확정됨).

## 0. 왜 사이드카인가
자바 `CrossEncoderReranker`(DJL PyTorch, `@Component("cross")`)는 **M2 맥에서 네이티브 폴백**(재정렬 없이 top-K 반환)이라 실측이 안 된다.
그래서 `bge-reranker-base`를 Python `sentence-transformers`로 띄우는 사이드카(`ml/serve/reranker_sidecar.py`)를 두고,
자바 `@Component("cross-sidecar")`(`SidecarReranker`)가 HTTP로 호출한다. whisper/tts 사이드카와 같은 패턴.

## 1. 준비 — 3개 프로세스

```bash
# (1) Ollama — judge 채점자
ollama serve

# (2) 백엔드 (arm64 JDK 필수)
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-23.jdk/Contents/Home ./mvnw spring-boot:run

# (3) 리랭커 사이드카 — 첫 실행 시 bge-reranker-base(~1.1GB) 자동 다운로드
pip install sentence-transformers        # 최초 1회
python3 ml/serve/reranker_sidecar.py --serve --port 8002
```

사이드카 확인:
```bash
curl http://127.0.0.1:8002/health
# {"status":"ok","model":"BAAI/bge-reranker-base"}
```

## 2. 골든셋 — KB에 답이 있는 문항으로

`eval/golden_vehicle.json` (10문항, KB 실재 매뉴얼 기준). 없는 차종을 물으면 recall이 억울하게 깎이니
KB에 실제 있는 매뉴얼(소나타·투싼·코나·아반떼·싼타페·그랜저 등)에서 답 나오는 질문만 넣는다.
문서 목록: `curl "http://localhost:8080/api/data/documents?namespace=vehicle"`.

## 3. A/B 측정 — RAGAS 두 번 (judge 고정!)

**judge를 반드시 고정**한다(채점자 다르면 delta 무의미). 아래는 qwen3:8b 고정 예.
`--rerank` 를 주면 답변 캐시를 우회하고 그 전략으로 실제 재검색한다.

```bash
cd /Users/daeyeop/Desktop/miniwatson_Veichle

# A) 기준선 — 현재 기본(mmr)
JUDGE_MODEL=qwen3:8b python3 eval/run_ragas.py --rerank mmr \
  | tee eval/ragas_qwen3_mmr.txt

# B) 크로스인코더 사이드카
JUDGE_MODEL=qwen3:8b python3 eval/run_ragas.py --rerank cross-sidecar \
  | tee eval/ragas_qwen3_cross.txt

# 비교
echo "=== mmr ==="   ; tail -3 eval/ragas_qwen3_mmr.txt
echo "=== cross ===" ; tail -3 eval/ragas_qwen3_cross.txt
```

## 4. 결과 읽는 법 — 무엇을 봐야 하나

리랭커는 **순서**를 고치는 도구다. 그러니 검색 지표를 본다:

| 지표 | 리랭커가 올려야 하는가 | 해석 |
|---|---|---|
| **ctx-precision** | ⭐ **핵심** | 최종 top-K가 실제로 관련 있는가. 리랭커의 직접 효과 |
| faithfulness | ○ 간접 | 정답 근거가 위로 오면 LLM이 잘 인용 → 환각↓ |
| answer_relevance | ○ 간접 | 위와 동반 상승 경향 |
| ctx-recall | △ 거의 무관 | 리랭커는 후보 풀을 못 넓힘. recall은 메타필터/적재의 몫 |

**판정 기준**: `cross`가 `mmr`보다 **ctx-precision avg가 유의미하게 높으면** 채택. 비슷하거나 낮으면
mmr 유지(리랭커 지연 비용 대비 이득 없음 = premature optimization 회피).

## 5. 단일 케이스 디버깅 (선택)

특정 문항이 왜 낮은지 볼 때 — 리랭크 전 후보 풀을 직접 확인:
```bash
curl "http://localhost:8080/api/rag/diag/candidates?question=<질의>&fetchN=20"
```
정답 문서가 풀에 **있는데 top-K 밖**이면 정밀도(리랭커가 고칠 문제), **아예 없으면** 회수(메타필터/청킹 문제).

사이드카 점수만 따로 확인:
```bash
python3 ml/serve/reranker_sidecar.py "질의" "문단1" "문단2"
```

## 6. 함께 볼 노브
- **TOP_K** (`RagService.TOP_K`, 현재 2): 리랭커가 있어도 슬롯이 2개뿐이라 타이트. 3~4로 넓혀 재측정 가치.
- **모델 교체**: `RERANKER_MODEL=BAAI/bge-reranker-v2-m3 python3 ml/serve/reranker_sidecar.py --serve`
  (더 강한 다국어, 대신 무겁고 느림). 사이드카 재기동만 하면 됨.
- **참고 관찰**(단일 케이스): "코나 일렉트릭 충전" 질의에서 cross-sidecar는 후보를 실제 재정렬하나
  (가솔린 kona→sonata_phev로 2위 교체), 그 KB의 kona_electric 청크가 충전 내용이 아니면 top-2에 못 든다.
  → 육안 한 건이 아니라 §3의 ctx-precision delta로 판단할 것.

## 7. 배포(참고)
클라우드(Linux/CUDA)에선 자바 DJL `@Component("cross")`가 정상 로드되므로 사이드카 없이 인프로세스로 돌 수 있다.
사이드카는 맥 로컬 실측용. `rerank.strategy`(application.yaml)로 무엇을 쓸지 고른다.
