import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Automotive IR System — Hyundai Intelligence · LLM",
  description: "자동차 도메인 특화 LLM 플랫폼 (RAG | text-to-SQL | governance)",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko">
      <head>
        <link
          rel="stylesheet"
          as="style"
          href="https://cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/dist/web/static/pretendard.min.css"
        />
        {/* 계기판 디스플레이 폰트 — KPI 숫자·라벨 전용 (본문은 Pretendard 유지) */}
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin="anonymous" />
        <link
          rel="stylesheet"
          href="https://fonts.googleapis.com/css2?family=Rajdhani:wght@500;600;700&display=swap"
        />
      </head>
      <body>{children}</body>
    </html>
  );
}
