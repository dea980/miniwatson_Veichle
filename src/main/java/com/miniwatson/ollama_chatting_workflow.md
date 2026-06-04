[브라우저/curl]
↓ POST /api/ask
[AskController]
↓ Jackson JSON 변환
[OllamaService]  
↓ RestTemplate HTTP 호출  
[Ollama API]
↓ gemma4 모델 추론
[OllamaService]
↓ JSON 파싱
[AskController]
↓ 응답
[curl 응답에 표시]