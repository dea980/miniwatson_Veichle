ask("What is RAG?")
↓
[1] OllamaRequest 객체 만듦
model="gemma4", prompt="What is RAG?", stream=false
↓
[2] postForObject 호출
- request 객체 → JSON 변환 (Jackson)
- HTTP POST http://localhost:11434/api/generate
- Body: {"model":"gemma4","prompt":"What is RAG?","stream":false}
↓
[3] Ollama 답변
JSON: {"model":"gemma4","response":"RAG is...","done":true}
↓
[4] postForObject 반환
- JSON → OllamaResponse 객체 (Jackson)
- response.response = "RAG is..."
↓
[5] response.getResponse() 반환
→ "RAG is..."