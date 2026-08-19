import os
from contextlib import asynccontextmanager

from fastapi import FastAPI
from langchain_mcp_adapters.client import MultiServerMCPClient

MCP_URL = os.getenv("VEHICLE_MCP_URL", "http://localhost:8080/sse")
EXCLUDE = {"diagnose"}        # AgentService를 감싸고 있어 순환이 생긴다

state: dict = {}


@asynccontextmanager
async def lifespan(app: FastAPI):
    client = MultiServerMCPClient(
        {"vehicle": {"transport": "sse", "url": MCP_URL}}
    )
    tools = await client.get_tools()          # 0.3.x는 컨텍스트 매니저가 아니다
    state["client"] = client
    state["tools"] = [t for t in tools if t.name not in EXCLUDE]
    yield
    state.clear()


app = FastAPI(lifespan=lifespan)


def _schema(t):
    s = getattr(t, "args_schema", None)
    if s is None:
        return None
    return s if isinstance(s, dict) else s.model_json_schema()


@app.get("/agent/health")
async def health():
    return {"mcp": MCP_URL, "tools": len(state.get("tools", []))}


@app.get("/agent/tools")
async def list_tools():
    return [
        {"name": t.name, "description": (t.description or "")[:120], "args": _schema(t)}
        for t in state.get("tools", [])
    ]