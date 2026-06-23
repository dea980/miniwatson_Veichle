"use client";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import HomePanel from "@/components/HomePanel";
import AskPanel from "@/components/AskPanel";
import AgentPanel from "@/components/AgentPanel";
import ReportPanel from "@/components/ReportPanel";
import DiagnosePanel from "@/components/DiagnosePanel";
import KnowledgeBasePanel from "@/components/KnowledgeBasePanel";
import TabularPanel from "@/components/TabularPanel";
import AnalyticsPanel from "@/components/AnalyticsPanel";
import CaseTriagePanel from "@/components/CaseTriagePanel";
import SchedulePanel from "@/components/SchedulePanel";
import GovernancePanel from "@/components/GovernancePanel";

const ICONS: Record<string, React.ReactNode> = {
  home: <><path d="M3 11l9-7 9 7" /><path d="M5 10v10h14V10" /></>,
  ask: <><circle cx="11" cy="11" r="7" /><line x1="21" y1="21" x2="16.65" y2="16.65" /></>,
  agent: <polygon points="13 2 4 14 12 14 11 22 20 10 12 10 13 2" />,
  report: <><path d="M8 4h8a1 1 0 011 1v15a1 1 0 01-1 1H8a1 1 0 01-1-1V5a1 1 0 011-1z" /><line x1="10" y1="9" x2="14" y2="9" /><line x1="10" y1="13" x2="14" y2="13" /></>,
  diag: <><path d="M4 8h3l1.5-2h7L17 8h3v11H4z" /><circle cx="12" cy="13" r="3.2" /></>,
  kb: <><path d="M5 4h13v16H7a2 2 0 01-2-2z" /><line x1="9" y1="4" x2="9" y2="20" /></>,
  sql: <><ellipse cx="12" cy="5" rx="8" ry="3" /><path d="M4 5v6c0 1.7 3.6 3 8 3s8-1.3 8-3V5" /><path d="M4 11v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6" /></>,
  analytics: <><line x1="4" y1="20" x2="4" y2="11" /><line x1="10" y1="20" x2="10" y2="4" /><line x1="16" y1="20" x2="16" y2="14" /><line x1="20" y1="20" x2="20" y2="8" /></>,
  triage: <><path d="M3 5h18" /><path d="M6 12h12" /><path d="M10 19h4" /></>,
  schedule: <><rect x="3" y="4" width="18" height="17" rx="2" /><line x1="3" y1="9" x2="21" y2="9" /><line x1="8" y1="2" x2="8" y2="6" /><line x1="16" y1="2" x2="16" y2="6" /></>,
  gov: <path d="M12 3l8 3v6c0 5-3.4 7.7-8 9-4.6-1.3-8-4-8-9V6z" />,
};

const TABS = [
  { id: "home", label: "홈", desc: "차량 품질 현황, 최근 리콜·불만, AI 어시스턴트, 빠른 작업을 한 화면에." },
  { id: "ask", label: "매뉴얼 검색", desc: "현대차 오너스 매뉴얼(취급설명서)을 근거로 한국어 답변을 생성합니다. 모든 답변에 출처가 따라붙습니다." },
  { id: "agent", label: "통합 질의", desc: "질문을 분석해 매뉴얼 검색·리콜 데이터·복합을 자동으로 골라 답합니다." },
  { id: "report", label: "차종 진단 리포트", desc: "차종 하나(예: PALISADE)에 대해 리콜·불만·매뉴얼을 모은 종합 리포트. 개별 케이스 진단은 케이스 트리아지에서." },
  { id: "diag", label: "사진 진단", desc: "경고등·부품 사진을 인식해 진단하고, 필요한 부품을 산정합니다." },
  { id: "kb", label: "지식베이스", desc: "문서를 업로드해 지식베이스에 적재하고 관리합니다." },
  { id: "sql", label: "데이터 질의", desc: "CSV/엑셀을 올려 자연어로 질문하면 표를 분석해 답합니다." },
  { id: "analytics", label: "분석 대시보드", desc: "리콜·불만·부품 데이터를 집계해 부품 수요·안전 핫스팟·추세를 보여주고, AI가 운영 인사이트를 서술합니다." },
  { id: "triage", label: "케이스 트리아지", desc: "고객 불만(접수)을 심각도 우선순위로 정렬해 먼저 대응할 케이스를 위로 보여줍니다. 차종·부위 필터와 케이스별 차량 진단을 제공합니다." },
  { id: "schedule", label: "정비 스케줄", desc: "달력으로 정비 일정을 추가하고 상태(예정·진행·완료)를 관리합니다. 백엔드에 영속 저장됩니다." },
  { id: "gov", label: "거버넌스", desc: "모든 AI 호출의 기록·개인정보 마스킹·지표를 추적합니다." },
] as const;
type TabId = (typeof TABS)[number]["id"];

function Icon({ id }: { id: string }) {
  return <svg viewBox="0 0 24 24" strokeLinecap="round" strokeLinejoin="round">{ICONS[id]}</svg>;
}

export default function Home() {
  const [tab, setTab] = useState<TabId>("home");
  const [dark, setDark] = useState(false);
  const [online, setOnline] = useState<boolean | null>(null);
  const [reportCar, setReportCar] = useState("");   // → 진단 리포트 차종 전달
  const [triageModel, setTriageModel] = useState("");   // → 트리아지 차종 필터
  const [triageCase, setTriageCase] = useState("");     // → 트리아지 특정 케이스 자동 오픈

  // payload: report=차종 / triage="차종::접수번호"(케이스 상세 자동 오픈) 또는 빈값(큐)
  function navigate(id: string, payload?: string) {
    if (id === "report" && payload) setReportCar(payload);
    if (id === "triage") {
      const parts = (payload || "").split("::");
      setTriageModel(parts[0] || ""); setTriageCase(parts[1] || "");
    }
    setTab(id as TabId);
  }

  useEffect(() => {
    const saved = typeof window !== "undefined" ? localStorage.getItem("mw-theme") : null;
    if (saved === "dark") setDark(true);
    // 백엔드 연결 확인 (models 핑)
    api.models().then(() => setOnline(true)).catch(() => setOnline(false));
  }, []);
  useEffect(() => {
    if (typeof window !== "undefined") localStorage.setItem("mw-theme", dark ? "dark" : "light");
  }, [dark]);

  const current = TABS.find((t) => t.id === tab)!;

  return (
    <div className="shell" data-theme={dark ? "dark" : "light"}>
      <aside className="sidebar">
        <div className="brand">
          <span className="logo">
            <svg viewBox="0 0 40 40" aria-hidden="true">
              <defs>
                <linearGradient id="mw-logo" x1="0" y1="0" x2="1" y2="1">
                  <stop offset="0" stopColor="#2f86ff" />
                  <stop offset="1" stopColor="#002c5f" />
                </linearGradient>
              </defs>
              <rect width="40" height="40" rx="11" fill="url(#mw-logo)" />
              <circle cx="20" cy="20" r="9" fill="none" stroke="#fff" strokeWidth="2.2" />
              <circle cx="20" cy="20" r="2.6" fill="#fff" />
              <path d="M20 17.4V11.2 M17.7 21.3l-5.4 3.1 M22.3 21.3l5.4 3.1"
                stroke="#fff" strokeWidth="2.2" strokeLinecap="round" />
            </svg>
          </span>
          <div>
            <div className="name">MiniWatson Vehicle</div>
            <div className="sub">Automotive Domain LLM</div>
          </div>
        </div>
        <nav className="nav">
          {TABS.map((t) => (
            <button key={t.id} className={`navitem ${tab === t.id ? "active" : ""}`} onClick={() => navigate(t.id)}>
              <span className="ic"><Icon id={t.id} /></span>{t.label}
            </button>
          ))}
        </nav>
      </aside>

      <div className="content">
        <header className="topbar">
          <span className="crumb">MiniWatson Vehicle <span className="muted">/</span> <b>{current.label}</b></span>
          <div className="row" style={{ gap: 10 }}>
            {online !== null && (
              <span className="conn" data-on={online ? "1" : "0"}>
                <span className="dot" />{online ? "연결됨" : "백엔드 꺼짐"}
              </span>
            )}
            <button className="theme-btn" onClick={() => setDark((v) => !v)}>{dark ? "라이트" : "다크"}</button>
          </div>
        </header>

        <main className="main">
          <section className="hero">
            <div className="kicker">Automotive Domain LLM</div>
            <h2>{current.label}</h2>
            <p>{current.desc}</p>
          </section>

          <div className="panels">
            {tab === "home" && <HomePanel onNavigate={navigate} />}
            {tab === "ask" && <AskPanel />}
            {tab === "agent" && <AgentPanel />}
            {tab === "report" && <ReportPanel initialCar={reportCar} onNavigate={navigate} />}
            {tab === "diag" && <DiagnosePanel />}
            {tab === "kb" && <KnowledgeBasePanel />}
            {tab === "sql" && <TabularPanel />}
            {tab === "analytics" && <AnalyticsPanel />}
            {tab === "triage" && <CaseTriagePanel onNavigate={navigate} initialModel={triageModel} initialCaseId={triageCase} />}
            {tab === "schedule" && <SchedulePanel />}
            {tab === "gov" && <GovernancePanel />}
          </div>

          <footer className="footer">
            <span>MiniWatson Vehicle — 자동차 도메인 LLM 플랫폼</span>
            <span>RAG, Agent, text-to-SQL, LoRA, 거버넌스 — <b>Daeyeop Kim</b></span>
          </footer>
        </main>
      </div>
    </div>
  );
}
