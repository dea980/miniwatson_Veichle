import os

def build_llm():
    provider = os.getenv("AGENT_LLM", "ollama").lower()

    if provider == "ollama":
        from langchain_ollama import ChatOllama
        return ChatOllama(
            model=os.getenv("OLLAMA_CHAT_MODEL", "ibm/granite4:latest"),
            base_url=os.getenv("OLLAMA_URL", "http://localhost:11434"),
            temperature=0,
        )

    if provider == "groq":
        from langchain_openai import ChatOpenAI
        return ChatOpenAI(
            model=os.getenv("GROQ_CHAT_MODEL", "llama-3.3-70b-versatile"),
            base_url="https://api.groq.com/openai/v1",
            api_key=os.getenv("GROQ_API_KEY"),
            temperature=0,
        )

    raise ValueError(f"unknown AGENT_LLM={provider}")