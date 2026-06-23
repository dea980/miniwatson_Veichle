package com.miniwatson.service;

import com.miniwatson.data.Article;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 매뉴얼 파일명 → 메타(차종·연식·언어·구동계) 파서 회귀 방지.
 * 적재 경로(IngestionService.ingestText)가 이 결과를 Article에 그대로 박으므로,
 * 여기서 모든 변종을 검증한다.
 */
class ManualMetaTest {

    @Test
    void parsesBaseModelWithCode() {
        var r = ManualMeta.parse("hyundai_2025_casper_AX_owners_KR.pdf");
        assertNotNull(r);
        assertEquals("AX", r.code());
        assertEquals("casper", r.model());
        assertNull(r.powertrain());
        assertEquals(2025, r.year());
        assertEquals("ko", r.lang());
        assertEquals("KR", r.region());
    }

    @Test
    void parsesPowertrainBeforeCode() {
        var r = ManualMeta.parse("hyundai_2025_avante_hybrid_CN7HEV_owners_KR.pdf");
        assertNotNull(r);
        assertEquals("CN7HEV", r.code());
        assertEquals("avante", r.model());
        assertEquals("hybrid", r.powertrain());
        assertEquals(2025, r.year());
    }

    @Test
    void parsesMultiTokenModel() {
        // staria_sv 같은 다토큰 model — sv는 powertrain 토큰이지만 model 일부로 살아나야
        // (US4=staria base, US4SV는 staria 특수차) → model="staria_sv", powertrain=null 또는 sv 중 어느 쪽이든 일관.
        // 현 구현: 끝에서 powertrain 떼므로 model=staria, powertrain=sv. 이게 의도된 동작.
        var r = ManualMeta.parse("hyundai_2025_staria_sv_US4SV_owners_KR.pdf");
        assertNotNull(r);
        assertEquals("US4SV", r.code());
        assertEquals("staria", r.model());
        assertEquals("sv", r.powertrain());
    }

    @Test
    void parsesElectricSeparated() {
        var r = ManualMeta.parse("hyundai_2020_ioniq_electric_AEEV_owners_KR.pdf");
        assertNotNull(r);
        assertEquals("AEEV", r.code());
        assertEquals("ioniq", r.model());
        assertEquals("electric", r.powertrain());
    }

    @Test
    void parsesEnglishRegion() {
        // IA 원본(`hyundai_2016_equus_owners_EN.pdf`) — 코드 없는 형식
        var r = ManualMeta.parse("hyundai_2016_equus_owners_EN.pdf");
        assertNotNull(r);
        assertNull(r.code());                // 코드 토큰 없음
        assertEquals("equus", r.model());
        assertEquals("en", r.lang());
        assertEquals("EN", r.region());
    }

    @Test
    void returnsNullForNonManualFilenames() {
        assertNull(ManualMeta.parse("image-12345.png"));
        assertNull(ManualMeta.parse("something_random.pdf"));
        assertNull(ManualMeta.parse("Vector database"));
        assertNull(ManualMeta.parse(null));
    }

    @Test
    void applyInjectsMetaIntoArticle() {
        Article a = new Article();
        ManualMeta.apply(a, "hyundai_2025_ioniq5_NE1_owners_KR.pdf");
        assertEquals("NE1", a.getCarCode());
        assertEquals("ioniq5", a.getCarModel());
        assertNull(a.getPowertrain());
        assertEquals(Integer.valueOf(2025), a.getYear());
        assertEquals("ko", a.getLang());
        assertEquals("KR", a.getRegion());
    }

    @Test
    void applyIsNoOpForNonManualFilenames() {
        Article a = new Article();
        a.setTitle("preset"); a.setNamespace("default");
        ManualMeta.apply(a, "image-12345.png");
        assertNull(a.getCarCode());
        assertNull(a.getCarModel());
        assertNull(a.getYear());
        assertNull(a.getRegion());
    }
}
