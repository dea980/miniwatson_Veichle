# LinkedIn 포스트 (MiniWatson)

## 먼저: 너가 물어본 것에 답

**1) "이 프로젝트의 이유"가 필요한가?** → 그렇다, 그게 제일 중요하다.
링크드인은 스크롤을 멈추게 하는 게 첫 줄(hook)이다. 기능 나열로 시작하면 안 읽힌다.
**왜 만들었나(insight)로 시작 → 무엇을 했나 → 무엇을 배웠나** 순서. 이 프로젝트의 "왜"는 이미 강하다:
*"watsonx를 읽는 것과 만드는 것은 다르다. 어려운 건 모델이 아니라 파이프라인·저장·감사였다."*

**2) 이미지 필요한가?** → 있으면 도달률이 확 오른다(텍스트만보다 보통 2배 이상).
이 프로젝트는 후보가 명확하다. 추천 순서:
- **1순위: 3계층 아키텍처 다이어그램** (README의 data/ai/governance 그림). 한 장으로 "뭘 만들었나"가 전달됨. 깔끔하게 다시 그리면 베스트.
- 2순위: 대시보드 + Audit Trail 탭 스크린샷 (거버넌스가 실제로 도는 증거).
- 3순위: 캐러셀 3~5장 (다이어그램 → 검색결과 → 감사로그 → 결과 숫자). 캐러셀은 체류시간이 길어 도달에 유리.

**이미지 만드는 법(빠른 순):**
- 아키텍처: [excalidraw.com](https://excalidraw.com) 또는 Mermaid로 5분. 손그림 느낌 excalidraw가 링크드인에서 잘 먹힌다.
- 스크린샷: 앱 로컬 기동 후 대시보드/감사탭 캡처.
- 원하면 내가 다이어그램 SVG/PNG로 만들어줄 수 있다.

---

## 드래프트 A — 한국어 (메인 추천)

> 첫 줄이 hook. 줄바꿈 많이(모바일 가독성). 해시태그는 끝에 3~5개.

---

IBM watsonx를 "읽기"만 하다가, 직접 만들어봤습니다.

엔터프라이즈 GenAI 플랫폼이 실제로 어디서 어려운지 알고 싶었습니다.
그래서 watsonx의 3계층(data, ai, governance)을 Spring Boot로 처음부터 다시 만들었습니다. MiniWatson.

만들고 나서 내린 결론:
**어려운 건 모델이 아니었습니다.** 파이프라인, 저장 포맷, 감사 추적이었습니다.

기억에 남는 순간들:
→ 같은 코퍼스인데 pgvector만 검색 정확도가 떨어졌습니다. ANN 오차도, 정밀도도 아니었고 — 객체를 재구성할 때 ID가 사라져 순위 융합이 한 키로 붕괴한 거였습니다. ID 보존으로 35/35 회복.
→ 로컬은 되는데 CI만 컴파일 실패. 원인은 .gitignore 한 줄이 소스 패키지까지 무시해 코드가 커밋된 적이 없던 것.
→ 비전 모델이 송장 숫자를 지어냈습니다. 답은 더 큰 모델이 아니라 역할 분리(OCR=정확한 숫자, 비전=맥락)와 "충돌 시 OCR을 신뢰하라"는 명시였습니다.

스택: Spring Boot 4, Java 21(IBM Semeru), Ollama, 768차원 임베딩, 하이브리드 검색(벡터+BM25, RRF), pgvector, 멀티테넌트 보안(API key/JWT), PII 마스킹, CI/CD.

가장 크게 배운 것: 거버넌스(감사·테넌트 격리)가 가용성을 죽이면 안 된다는 것. 감사 로그 저장이 실패해도 사용자 답변은 나가야 합니다(fail-open).

전체 코드와 문서는 GitHub에 공개했습니다 (댓글에 링크).

#GenAI #RAG #watsonx #SpringBoot #LLM #MLOps

---

## 드래프트 B — 영어 (글로벌/IBM 본사 타겟이면)

---

I kept reading about IBM watsonx — so I rebuilt it from scratch to find out where enterprise GenAI is actually hard.

MiniWatson: watsonx's three layers (data, ai, governance), reimplemented in Spring Boot.

The verdict after building it: **the hard part isn't the model.** It's the pipeline, the storage format, and the audit trail.

A few moments I won't forget:
→ Same corpus, but pgvector retrieval scored lower than in-memory. Not ANN error, not precision — object reconstruction dropped the ID, so rank fusion collapsed to one key. Preserving the ID restored 35/35.
→ Compiled locally, failed only in CI. One unanchored line in .gitignore was hiding an entire source package — the code had never been committed.
→ A vision model invented an invoice total. The fix wasn't a bigger model; it was splitting roles (OCR for exact numbers, vision for context) and telling the LLM to trust OCR on conflicts.

Stack: Spring Boot 4, Java 21 (IBM Semeru), Ollama, 768-dim embeddings, hybrid search (vector + BM25, RRF), pgvector, multi-tenant auth (API key / JWT), PII redaction, CI/CD.

Biggest lesson: governance must never kill availability. If the audit write fails, the user still gets their answer (fail-open).

Code and docs on GitHub (link in comments).

#GenAI #RAG #watsonx #SpringBoot #LLM #MLOps

---

## 링크드인 운영 팁 (도달 관련)

- **링크는 본문 말고 첫 댓글에.** 링크드인은 외부링크 있는 글을 덜 퍼뜨린다. 본문엔 "댓글에 링크", 댓글에 GitHub URL.
- **첫 1~2시간 댓글 응답**이 알고리즘에 제일 영향. 올리고 자리 지켜라.
- **첫 3줄이 전부.** "더 보기" 전에 hook이 끝나야 한다. 위 드래프트는 그 구조로 짰다.
- 길이는 이 정도(중간)가 적당. 너무 길면 "더 보기"에서 이탈.
- 이미지 alt 텍스트 채우면 접근성 + 약간의 도달 보너스.
