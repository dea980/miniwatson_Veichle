[curl POST]
↓
[AskController] → @PostMapping("/ask")
↓
[OllamaService]
├── startTime 기록
├── Ollama API 호출 (5-30초)
├── latency 계산
└── 🆕 QueryLog DB 저장 ⭐
↓
[답변 반환]