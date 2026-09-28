package com.shelfeed.backend.global.init;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * seed/kdc_category.csv 가 만드는 카테고리 문자열("국내도서-소설/시/희곡")이
 * R__genres_seed.sql 의 장르 REGEXP 패턴에 실제로 걸리는지 검증한다.
 * KDC 로는 도출할 수 없는 장르(장르소설, 만화/라이트노벨은 657 만화만 매핑)는 제외한다.
 */
class KdcCategoryGenreCoverageTest {

    /** KDC 에서 도출 불가능해 커버리지 검증에서 제외하는 장르 */
    private static final Set<String> NOT_DERIVABLE = Set.of("장르소설");

    @Test
    @DisplayName("KDC 매핑 카테고리가 장르 패턴 14개에 최소 1개씩 걸린다")
    void everyDerivableGenreMatchesAtLeastOneMappedCategory() throws Exception {
        List<String> categories = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("seed/kdc_category.csv"), StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("prefix,")) continue;
            String[] parts = line.split(",", 2);
            assertThat(parts).hasSize(2);
            assertThat(parts[0].trim()).matches("\\d+");
            categories.add(parts[1].trim());
        }
        assertThat(categories).isNotEmpty();

        Map<String, String> patterns = readGenrePatterns();
        assertThat(patterns).hasSize(15);

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> e : patterns.entrySet()) {
            if (NOT_DERIVABLE.contains(e.getKey())) continue;
            Pattern p = Pattern.compile(e.getValue());
            boolean hit = categories.stream().anyMatch(c -> p.matcher(c).find());
            if (!hit) missing.add(e.getKey() + " /" + e.getValue() + "/");
        }
        assertThat(missing).as("KDC 매핑으로 채워지지 않는 장르").isEmpty();
    }

    /** 어떤 장르 패턴에도 걸리지 않는 KDC 매핑 카테고리. 실제 KDC 분류라 유지하되, 새로 생기면 실패시켜 의도적임을 강제한다. */
    private static final Set<String> INTENTIONAL_ORPHANS = Set.of("국내도서-총류", "국내도서-IT 모바일", "국내도서-국어 외국어 사전");

    @Test
    @DisplayName("장르 패턴에 걸리지 않는 매핑 카테고리는 문서화된 3개뿐이다")
    void onlyDocumentedCategoriesAreOrphans() throws Exception {
        List<String> categories = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("seed/kdc_category.csv"), StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("prefix,")) continue;
            categories.add(line.split(",", 2)[1].trim());
        }
        List<Pattern> patterns = readGenrePatterns().values().stream().map(Pattern::compile).toList();
        List<String> orphans = categories.stream().distinct()
                .filter(c -> patterns.stream().noneMatch(p -> p.matcher(c).find())).toList();
        assertThat(orphans).containsExactlyInAnyOrderElementsOf(INTENTIONAL_ORPHANS);
    }

    @Test
    @DisplayName("과학 패턴은 자연과학에만 걸리고 사회과학에는 걸리지 않는다")
    void sciencePatternDoesNotMatchSocialScience() throws Exception {
        String science = readGenrePatterns().get("과학");
        assertThat(Pattern.compile(science).matcher("국내도서-자연과학").find()).isTrue();
        assertThat(Pattern.compile(science).matcher("국내도서>과학>물리").find()).isTrue();
        assertThat(Pattern.compile(science).matcher("국내도서-사회과학").find()).isFalse();
    }

    /** R__genres_seed.sql 의 (id, '이름', '패턴') 행을 읽는다. */
    private static Map<String, String> readGenrePatterns() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/R__genres_seed.sql"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\\(\\s*\\d+\\s*,\\s*'([^']+)'\\s*,\\s*'([^']+)'\\s*\\)").matcher(sql);
        Map<String, String> out = new LinkedHashMap<>();
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }
}
