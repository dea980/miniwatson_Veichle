package com.miniwatson.service;

import com.miniwatson.data.Article;
import com.miniwatson.data.ArticleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 매뉴얼 메타(차종·연식·언어·구동계) 백필 — 기동 시 1회.
 *
 * 왜:
 *   - {@link ManualMeta} 도입 이전에 적재된 청크는 메타 필드가 비어 있다.
 *     채워지지 않으면 RAG 1차 필터(car/year/lang/powertrain)에서 매뉴얼이 누락된다.
 *   - 청크 title이 표준 파일명("hyundai_<year>_<model>[_pt]_<CODE>_owners_<REGION>.pdf #N")
 *     이라 파일 재인제스트 없이 title만 보고 채울 수 있다.
 *
 * 방식:
 *   - 청크의 title 접미사(" #N")를 떼고 {@link ManualMeta#parse(String)} 으로 메타 추출.
 *   - 이미 채워진 청크는 건너뜀(idempotent — 재시동 여러 번 무해).
 *   - 변경분이 있을 때만 1회 saveAll → ArticleParquetStore 파티션 시그니처가 같으면 디스크 건드리지 않음.
 *   - 토글: backfill.manual-meta.enabled=false 면 비활성(평가 환경 등에서 끄기).
 */
@Component
public class BackfillManualMetaRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BackfillManualMetaRunner.class);

    private final ArticleRepository articleStore;
    private final boolean enabled;

    public BackfillManualMetaRunner(ArticleRepository articleStore,
                                    @Value("${backfill.manual-meta.enabled:true}") boolean enabled) {
        this.articleStore = articleStore;
        this.enabled = enabled;
    }

    @Override
    public void run(String... args) throws Exception {
        if (!enabled) {
            log.info("[backfill] manual-meta 비활성 (backfill.manual-meta.enabled=false)");
            return;
        }
        long t0 = System.nanoTime();
        List<Article> all = articleStore.loadAll();
        int scanned = 0, updated = 0, skipped = 0, missMatch = 0;
        for (Article a : all) {
            scanned++;
            // 이미 메타가 있으면 스킵 — title이 같아도 1회만 채우면 충분(idempotent).
            if (a.getCarCode() != null || a.getCarModel() != null) { skipped++; continue; }
            String title = a.getTitle();
            if (title == null) { missMatch++; continue; }
            String base = title.replaceAll(" #\\d+$", "");
            ManualMeta.ManualMetaResult r = ManualMeta.parse(base);
            if (r == null) { missMatch++; continue; }
            a.setCarCode(r.code());
            a.setCarModel(r.model());
            a.setPowertrain(r.powertrain());
            a.setYear(r.year());
            a.setLang(r.lang());
            a.setRegion(r.region());
            updated++;
        }
        if (updated > 0) {
            articleStore.saveAll(all);   // ArticleParquetStore: 변경된 파티션만 재작성
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        log.info("[backfill] manual-meta done — scanned={} updated={} skipped(already)={} non-manual={} in {}ms",
                scanned, updated, skipped, missMatch, ms);
    }
}
