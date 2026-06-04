"Wikipedia ingestion → 768차원 embedding 생성 → Parquet 저장 → cosine similarity 검색 → Ollama grounded answer + governance 로그 — 이게 본인 mini watsonx의 full RAG pipeline입니다.
JSON에서 Parquet으로 마이그레이션하니 파일 크기 7배 감소. columnar 포맷 + snappy 압축 효과. watsonx.data가 왜 Parquet을 표준으로 쓰는지 직접 검증했습니다.
그 과정에서 Hadoop 의존성과 Java 21의 SecurityManager 호환성 문제도 디버그해서 해결했습니다. 모던 enterprise stack의 실전 issue를 직접 만져본 셈입니다."

articles.json.backup       54K    ← 옛 JSON (embedding 포함)
articles.parquet           7.8K   ← 새 Parquet (embedding 포함)

                           7배 작음! 🔥