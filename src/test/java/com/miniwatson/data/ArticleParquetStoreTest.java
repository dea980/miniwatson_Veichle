package com.miniwatson.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cold 저장소(Parquet 파티션) 회귀 테스트.
 * Phase 1 변경:
 *   - 단일 articles.parquet → data/articles/<safe>__<hash>.parquet 디렉터리
 *   - saveAll: 시그니처(정렬 id) 비교로 변경된 파티션만 재작성(dirty-only)
 *   - 입력에 없는 파티션 파일은 삭제(전체 상태 동기화)
 */
class ArticleParquetStoreTest {

    private static Article mk(long id, String ns, String title, String summary) {
        Article a = new Article();
        a.setId(id);
        a.setNamespace(ns);
        a.setTitle(title);
        a.setSummary(summary);
        a.setUrl("u");
        a.setIngestedAt(LocalDateTime.now());
        a.setEmbedding(List.of(0.1f));
        return a;
    }

    private ArticleParquetStore newStore(Path tmp) throws IOException {
        return new ArticleParquetStore(
                tmp.resolve("cold").toString(),
                tmp.resolve("legacy.parquet").toString());
    }

    @Test
    void saveAllSplitsByPartition(@TempDir Path tmp) throws IOException {
        ArticleParquetStore s = newStore(tmp);
        List<Article> all = List.of(
                mk(1, "vehicle", "A.pdf #1", "a1"),
                mk(2, "vehicle", "A.pdf #2", "a2"),
                mk(3, "vehicle", "B.pdf #1", "b1"));
        s.saveAll(all);
        File[] parts = tmp.resolve("cold").toFile().listFiles((d, n) -> n.endsWith(".parquet"));
        assertNotNull(parts);
        assertEquals(2, parts.length, "문서별 파티션 → A·B 두 파일");
    }

    @Test
    void unchangedPartitionIsNotRewritten(@TempDir Path tmp) throws IOException, InterruptedException {
        ArticleParquetStore s = newStore(tmp);
        List<Article> all = List.of(
                mk(1, "vehicle", "A.pdf #1", "a1"),
                mk(2, "vehicle", "B.pdf #1", "b1"));
        s.saveAll(all);
        File[] before = tmp.resolve("cold").toFile().listFiles((d, n) -> n.endsWith(".parquet"));
        assertNotNull(before);
        long mtimeA0 = before[0].lastModified();
        long mtimeB0 = before[1].lastModified();

        Thread.sleep(20);   // 파일시스템 mtime 해상도 보장

        // 동일 입력 다시 — 시그니처 일치라 재작성 안 돼야 함
        s.saveAll(all);
        File[] after = tmp.resolve("cold").toFile().listFiles((d, n) -> n.endsWith(".parquet"));
        assertNotNull(after);
        long mtimeA1 = after[0].lastModified();
        long mtimeB1 = after[1].lastModified();
        assertEquals(mtimeA0, mtimeA1, "동일 시그니처 → 재작성 없음 (A)");
        assertEquals(mtimeB0, mtimeB1, "동일 시그니처 → 재작성 없음 (B)");
    }

    @Test
    void changedPartitionIsRewrittenOthersUntouched(@TempDir Path tmp) throws IOException, InterruptedException {
        ArticleParquetStore s = newStore(tmp);
        List<Article> all = new ArrayList<>(List.of(
                mk(1, "vehicle", "A.pdf #1", "a1"),
                mk(2, "vehicle", "B.pdf #1", "b1")));
        s.saveAll(all);
        File aFile = findFor(tmp, "A.pdf");
        File bFile = findFor(tmp, "B.pdf");
        long aMtime0 = aFile.lastModified();
        long bMtime0 = bFile.lastModified();

        Thread.sleep(20);

        // A에 새 청크 추가 — A만 dirty
        all.add(mk(3, "vehicle", "A.pdf #2", "a2"));
        s.saveAll(all);
        assertNotEquals(aMtime0, findFor(tmp, "A.pdf").lastModified(), "A는 시그니처 변경 → 재작성");
        assertEquals(bMtime0, findFor(tmp, "B.pdf").lastModified(), "B는 그대로 → 미변경");
    }

    @Test
    void partitionsAbsentFromInputAreDeleted(@TempDir Path tmp) throws IOException {
        ArticleParquetStore s = newStore(tmp);
        s.saveAll(List.of(mk(1, "vehicle", "A.pdf #1", "a1"), mk(2, "vehicle", "B.pdf #1", "b1")));
        assertEquals(2, tmp.resolve("cold").toFile().listFiles((d, n) -> n.endsWith(".parquet")).length);
        // B를 빼고 다시 저장 → B 파티션 파일 사라져야 함(전체 상태 동기화)
        s.saveAll(List.of(mk(1, "vehicle", "A.pdf #1", "a1")));
        File[] after = tmp.resolve("cold").toFile().listFiles((d, n) -> n.endsWith(".parquet"));
        assertEquals(1, after.length);
        assertTrue(after[0].getName().contains("A.pdf"), "남은 건 A 파티션");
    }

    @Test
    void loadAllRoundtripsAcrossPartitions(@TempDir Path tmp) throws IOException {
        ArticleParquetStore s = newStore(tmp);
        s.saveAll(List.of(
                mk(1, "vehicle", "A.pdf #1", "a1"),
                mk(2, "vehicle", "B.pdf #1", "b1")));
        // 새 인스턴스로 디스크에서만 로드(캐시 우회)
        ArticleParquetStore s2 = newStore(tmp);
        List<Article> loaded = s2.loadAll();
        assertEquals(2, loaded.size());
    }

    private static File findFor(Path tmp, String baseTitle) {
        File[] all = tmp.resolve("cold").toFile().listFiles((d, n) -> n.endsWith(".parquet"));
        for (File f : all) if (f.getName().contains(baseTitle.replaceAll("[^a-zA-Z0-9._-]", "_"))) return f;
        throw new AssertionError("partition file not found for " + baseTitle);
    }
}
