package com.miniwatson.service;

import com.miniwatson.data.Article;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 매뉴얼 파일명에서 메타데이터 파싱.
 *
 * 규칙(rename_manuals.py 의 표준 출력 포맷과 동일):
 *   hyundai_&lt;year&gt;_&lt;model&gt;[_&lt;powertrain&gt;]_&lt;CODE&gt;_owners_&lt;REGION&gt;.pdf
 *   예: hyundai_2025_avante_hybrid_CN7HEV_owners_KR.pdf
 *       hyundai_2025_casper_AX_owners_KR.pdf
 *
 * RAG 1차 필터(차종/연식/언어/구동계)를 메타로 박아 검색 정확도를 올린다.
 * 비-매뉴얼(이미지·위키 등)은 적용 안 됨 — apply()는 안전하게 no-op.
 */
public final class ManualMeta {
    private ManualMeta() {}

    private static final Set<String> POWERTRAINS = Set.of("hybrid", "electric", "phev", "fcev", "sv");

    // hyundai_<year>_<...rest>_owners_<REGION>.pdf  (rest = model[_powertrain][_CODE])
    private static final Pattern PAT = Pattern.compile(
        "^hyundai_(\\d{4})_(.+?)_owners_([A-Z]{2})\\.pdf$",
        Pattern.CASE_INSENSITIVE);

    /** 파일명 → 메타(파싱 불가면 null). 호출자: IngestionService. */
    public static ManualMetaResult parse(String filename) {
        if (filename == null) return null;
        Matcher m = PAT.matcher(filename);
        if (!m.matches()) return null;
        int year;
        try { year = Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { return null; }
        String[] rest = m.group(2).split("_");
        String region = m.group(3).toUpperCase();
        if (rest.length == 0) return null;

        // 마지막 토큰이 대문자/숫자 위주면 프로젝트코드로 간주(CN7HEV, AX, NE1N…).
        // 단일 토큰만 있으면(예: code-fallback `vi`)도 code로 본다.
        String code = null;
        int restEnd = rest.length;
        if (rest.length >= 2 && isCodeToken(rest[rest.length - 1])) {
            code = rest[rest.length - 1].toUpperCase();
            restEnd--;
        }
        // restEnd 직전 토큰이 powertrain이면 분리.
        String powertrain = null;
        if (restEnd > 1 && POWERTRAINS.contains(rest[restEnd - 1].toLowerCase())) {
            powertrain = rest[restEnd - 1].toLowerCase();
            restEnd--;
        }
        // 남은 토큰들이 model
        if (restEnd <= 0) return null;
        StringBuilder model = new StringBuilder();
        for (int i = 0; i < restEnd; i++) {
            if (i > 0) model.append("_");
            model.append(rest[i].toLowerCase());
        }
        String lang = "KR".equals(region) ? "ko" : ("EN".equals(region) || "US".equals(region) ? "en" : null);
        return new ManualMetaResult(code, model.toString(), powertrain, year, lang, region);
    }

    /** 모든 글자가 대문자/숫자면 프로젝트코드로 인정. (model 토큰은 소문자만 사용) */
    private static boolean isCodeToken(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isUpperCase(c) || Character.isDigit(c))) return false;
        }
        return true;
    }

    /** Article에 메타를 주입(파일명이 매뉴얼 패턴이면). */
    public static void apply(Article a, String filename) {
        ManualMetaResult r = parse(filename);
        if (r == null) return;
        a.setCarCode(r.code);
        a.setCarModel(r.model);
        a.setPowertrain(r.powertrain);
        a.setYear(r.year);
        a.setLang(r.lang);
        a.setRegion(r.region);
    }

    public record ManualMetaResult(String code, String model, String powertrain,
                                   Integer year, String lang, String region) {}
}
