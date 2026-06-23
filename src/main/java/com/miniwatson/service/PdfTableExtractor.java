package com.miniwatson.service;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ToXMLContentHandler;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern TABLE = Pattern.compile("(?s)<table\\b.*?</table>");
    private static final Pattern ROW   = Pattern.compile("(?s)<tr\\b.*?</tr>");
    private static final Pattern CELL  = Pattern.compile("(?s)<t[hd]\\b[^>]*>(.*?)</t[hd]>");

    public static String toTextWithTables(String xhtml) {
        if (xhtml == null || xhtml.isBlank()) return "";
        // 1) <table> 블록을 마크다운 표로 치환 (quoteReplacement: 마크다운의 $/\ 보호)
        Matcher mt = TABLE.matcher(xhtml);
        StringBuffer sb = new StringBuffer();
        while (mt.find()) {
            String md = tableToMarkdown(mt.group());
            mt.appendReplacement(sb, Matcher.quoteReplacement("\n" + md + "\n"));
        }
        mt.appendTail(sb);
        // 2) 남은 태그 제거 → 평문(표는 이미 마크다운이라 영향 없음) + 엔티티 복원
        String text = unescape(sb.toString().replaceAll("(?s)<[^>]+>", " "));
        // 3) 줄별 공백 정리 (마크다운 표의 '|' 줄은 그대로 유지됨)
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n")) {
            String t = line.replaceAll("[ \\t]+", " ").trim();
            if (!t.isEmpty()) out.append(t).append("\n");
        }
        return out.toString().trim();
    }

    /** <table> 한 개 → 마크다운 표. 첫 행을 헤더로 보고 구분자(--- )를 넣는다. */
    private static String tableToMarkdown(String tableHtml) {
        List<List<String>> rows = new ArrayList<>();
        Matcher mr = ROW.matcher(tableHtml);
        while (mr.find()) {
            List<String> cells = new ArrayList<>();
            Matcher mc = CELL.matcher(mr.group());
            while (mc.find()) cells.add(cleanCell(mc.group(1)));
            if (!cells.isEmpty()) rows.add(cells);
        }
        if (rows.isEmpty()) return "";
        int cols = rows.stream().mapToInt(List::size).max().orElse(0);
        StringBuilder md = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            md.append("|");
            for (int c = 0; c < cols; c++) md.append(" ").append(c < row.size() ? row.get(c) : "").append(" |");
            md.append("\n");
            if (r == 0) {                       // 헤더 구분자
                md.append("|");
                for (int c = 0; c < cols; c++) md.append(" --- |");
                md.append("\n");
            }
        }
        return md.toString();
    }

    private static String cleanCell(String s) {
        return unescape(s.replaceAll("(?s)<[^>]+>", " "))
                .replace("|", "\\|")            // 셀 내부 파이프는 이스케이프(표 깨짐 방지)
                .replaceAll("\\s+", " ").trim();
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
    }

    private PdfTableExtractor() {}
}
