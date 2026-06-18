import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "MiniWatson Vehicle — Automotive LLM",
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
      </head>
      <body>{children}</body>
    </html>
  );
}
