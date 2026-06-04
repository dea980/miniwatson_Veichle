[ingest("RAG")]
↓
[Wikipedia URL 만들기]
url = "https://en.wikipedia.org/api/rest_v1/page/summary/RAG"
↓
[restTemplate.getForObject(...)]
HTTP GET → JSON → WikipediaResponse 객체
↓
[Article 객체로 변환]
title, summary, url, ingestedAt
↓
[articleStore.save(article)]
ID 자동 할당 + JSON 파일 append
↓
[Article 반환]