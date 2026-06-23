package com.miniwatson.service;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ToXMLContentHandler;

import java.io.InputStream;

/**
 * 표-인식 추출 (2세대 — INGESTION-FORMATS.md §5.1~5.3 결정).
 *
 * 왜: 현재 인제스트는 tika.parseToString = 평문이라 표가 평탄화(행·열 뭉갬)된다.
 * Tika를 XHTML로 받으면 <table><tr><td> 구조가 보존되므로, 그걸 마크다운 표로 바꾸면
 * 토크값·점검주기 같은 표가 청크에 구조 그대로 남아 RAG에서 검색된다.
 * 인프로세스(새 의존성 0). 더 정확한 pdfplumber/Docling은 Python 사이드카가 필요(스케일 칸).
 *
 * 그래프/다이어그램은 여기 대상 아님 — 그건 "추출"이 아니라 "비전 해석" 문제(§5.3).
 */
public final class PdfTableExtractor {

    /** [제공] PDF/문서 → XHTML 문자열 (<table> 포함). */
    public static String toXhtml(InputStream in) throws Exception {
        ToXMLContentHandler handler = new ToXMLContentHandler();
        new AutoDetectParser().parse(in, handler, new Metadata(), new ParseContext());
        return handler.toString();
    }

    /**
     * TODO-1 (네가 구현): XHTML → "본문 텍스트 + 마크다운 표".
     *
     * 목표: 본문은 평문으로, <table>...</table> 블록은 마크다운 표로 변환해 그 자리에 끼워 넣는다.
     *
     * 힌트:
     *  - <table> 안에 <tr>(행), 그 안에 <th>/<td>(셀).
     *  - 마크다운 한 행 = "| a | b | c |". 첫(헤더) 행 다음 줄에 구분자 "| --- | --- | --- |".
     *  - 표 외 태그(<p>,<h1>,<div>...)는 제거하고 텍스트만 남긴다.
     *  - 구현 방법 두 가지:
     *      (a) Jsoup이 클래스패스에 있으면: Jsoup.parse(xhtml) 후 doc.select("table") 로 표를 잡고,
     *          각 table.select("tr") → row.select("th,td") 로 셀을 읽어 마크다운 조립. 나머지는 doc.text().
     *      (b) 의존성 추가가 싫으면: 정규식/SAX로 <table>..</table>만 떼어 직접 변환, 나머지는 태그 제거.
     *  - 표가 없으면 평문만 반환되면 된다(기존과 동일 동작 보장 — 회귀 없음).
     *
     * 검증: 표 많은 매뉴얼 페이지 인제스트 후, 문서 전용 채팅에서 "토크값 표" 질문 →
     *       답에 셀 값(숫자·단위)이 보이면 성공. (전/후 같은 질문으로 비교)
     */
    public static String toTextWithTables(String xhtml) {
        // 임시 안전 폴백: 태그만 제거해 평문 반환(기존 parseToString 수준). 위 TODO로 교체하라.
        if (xhtml == null) return "";
        return xhtml.replaceAll("(?s)<[^>]+>", " ").replaceAll("[ \\t]+", " ").trim();
    }

    private PdfTableExtractor() {}
}
