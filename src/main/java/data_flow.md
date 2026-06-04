[사용자]
↓ POST /api/ask
↓ Body: { "question": "What is RAG?" }

[AskController]
↓ @RequestBody로 AskRequest 객체에 매핑됨
↓ ollamaService.ask("What is RAG?") 호출

[OllamaService]
↓ OllamaRequest 만들고 RestTemplate.postForObject(...)
↓ Ollama 서버에 HTTP POST

[Ollama:11434]
↓ AI 답변 생성
↓ JSON 반환

[OllamaService]
↓ OllamaResponse로 자동 파싱
↓ response.getResponse() 추출
↓ String 반환

[AskController]
↓ String 그대로 반환

[사용자]
↓ "RAG is..."