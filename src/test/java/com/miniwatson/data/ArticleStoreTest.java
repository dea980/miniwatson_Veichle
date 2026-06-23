package com.miniwatson.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hot 저장소(JSONL 파티션) 회귀 테스트.
 * Phase 1 변경: 단일 articles.json → data/articles_hot/<safe>__<hash>.jsonl 디렉터리.
 *   - save = 한 줄 append, 같은 문서는 같은 파일에 누적
 *   - 다른 baseTitle = 다른 파일(원자 분할)
 *   - 레거시 articles.json은 1회 자동 분할 후 삭제
 *   - saveAll(빈 리스트) = 디렉터리 비우기(compact drain 계약)
 *   - deleteByID = 해당 파일에서 라인 단위 제거
 *   - size() = 라인 카운트만 (전체 파싱 회피)
 */
class ArticleStoreTest {

    private static Article mk(long id, String ns, String title, String summary) {
        Article a = new Article();
        a.setId(id);
        a.setNamespace(ns);
        a.setTitle(title);
        a.setSummary(summary);
        a.setUrl("file://" + title);
        a.setIngestedAt(LocalDateTime.now());
        a.setEmbedding(List.of(0.1f, 0.2f, 0.3f));
        return a;
    }

    private ArticleStore newStore(Path tmp) {
        return new ArticleStore(tmp.resolve("hot").toString(), tmp.resolve("legacy.json").toString());
    }

    @Test
    void saveAppendsAndLoadRoundtrips(@TempDir Path tmp) throws IOException {
        ArticleStore s = newStore(tmp);
        s.save(mk(0, "vehicle", "manual.pdf #1", "first"));
        s.save(mk(0, "vehicle", "manual.pdf #2", "second"));
        List<Article> all = s.loadAll();
        assertEquals(2, all.size());
        assertEquals("first", all.get(0).getSummary());
        assertEquals("second", all.get(1).getSummary());
    }

    @Test
    void differentBaseTitlesGoToDifferentFiles(@TempDir Path tmp) throws IOException {
        ArticleStore s = newStore(tmp);
        s.save(mk(0, "vehicle", "manualA.pdf #1", "A"));
        s.save(mk(0, "vehicle", "manualB.pdf #1", "B"));
        List<java.io.File> files = List.of(tmp.resolve("hot").toFile().listFiles());
        assertEquals(2, files.size(), "문서별 파티션 → 2개 파일");
    }

    @Test
    void sameBaseTitleSharesOneFile(@TempDir Path tmp) throws IOException {
        ArticleStore s = newStore(tmp);
        for (int i = 1; i <= 5; i++) s.save(mk(0, "vehicle", "manual.pdf #" + i, "c" + i));
        java.io.File[] files = tmp.resolve("hot").toFile().listFiles();
        assertNotNull(files);
        assertEquals(1, files.length, "동일 베이스 → 단일 파일");
        long lines = Files.lines(files[0].toPath()).filter(s2 -> !s2.isBlank()).count();
        assertEquals(5, lines);
    }

    @Test
    void sizeUsesLineCountNotFullParse(@TempDir Path tmp) throws IOException {
        ArticleStore s = newStore(tmp);
        for (int i = 1; i <= 7; i++) s.save(mk(0, "vehicle", "x.pdf #" + i, "y"));
        assertEquals(7, s.size());
    }

    @Test
    void saveAllEmptyDrainsDirectory(@TempDir Path tmp) throws IOException {
        ArticleStore s = newStore(tmp);
        s.save(mk(0, "vehicle", "doc.pdf #1", "x"));
        assertEquals(1, s.size());
        s.saveAll(new ArrayList<>());   // compact 후 drain
        assertEquals(0, s.size());
        assertEquals(0, s.loadAll().size());
    }

    @Test
    void deleteByIdRemovesLineAndKeepsFile(@TempDir Path tmp) throws IOException {
        ArticleStore s = newStore(tmp);
        Article a1 = s.save(mk(0, "vehicle", "doc.pdf #1", "alpha"));
        Article a2 = s.save(mk(0, "vehicle", "doc.pdf #2", "beta"));
        assertTrue(s.deleteByID(a1.getId()));
        List<Article> rest = s.loadAll();
        assertEquals(1, rest.size());
        assertEquals(a2.getId(), rest.get(0).getId());
    }

    @Test
    void legacyArticlesJsonAutoMigratesOnce(@TempDir Path tmp) throws IOException {
        Path legacy = tmp.resolve("legacy.json");
        String json = "[{\"id\":1,\"namespace\":\"vehicle\",\"title\":\"old.pdf #1\",\"summary\":\"s1\",\"url\":\"u\",\"ingestedAt\":\"2026-01-01T00:00:00\",\"embedding\":[0.1]}," +
                       "{\"id\":2,\"namespace\":\"vehicle\",\"title\":\"old.pdf #2\",\"summary\":\"s2\",\"url\":\"u\",\"ingestedAt\":\"2026-01-01T00:00:00\",\"embedding\":[0.2]}]";
        Files.writeString(legacy, json);
        ArticleStore s = newStore(tmp);
        List<Article> all = s.loadAll();
        assertEquals(2, all.size(), "레거시가 분할 적재되어 보임");
        assertFalse(legacy.toFile().exists(), "마이그레이션 후 레거시 파일 삭제");
        java.io.File[] files = tmp.resolve("hot").toFile().listFiles();
        assertNotNull(files);
        assertEquals(1, files.length, "동일 베이스(old.pdf) → 단일 파티션 파일");
    }
}
