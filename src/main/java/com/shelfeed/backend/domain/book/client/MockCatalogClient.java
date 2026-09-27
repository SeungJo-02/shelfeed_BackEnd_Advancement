package com.shelfeed.backend.domain.book.client;

import com.shelfeed.backend.domain.book.client.dto.CatalogBookItem;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 부하 테스트용 가짜 카탈로그. 외부 API 없이 결정적인 도서를 만들어 준다.
 * 프로파일 {@code mock-catalog}에서만 뜨고, 운영({@code prod})에서는 절대 뜨지 않는다.
 */
@Component
@Profile("mock-catalog & !prod")
public class MockCatalogClient implements BookCatalogClient {

    // 실제 카탈로그 API P50 기준 지연 시뮬레이션 (부하테스트 현실성 확보)
    private static final int SIMULATED_LATENCY_MS = 30;

    @Override
    public List<CatalogBookItem> search(String query, int page, int pageSize) {
        simulateLatency();
        List<CatalogBookItem> items = new ArrayList<>();
        for (int i = 0; i < pageSize; i++) {
            int idx = (page - 1) * pageSize + i;
            String isbn = String.format("8%012d", Math.abs((query + idx).hashCode()) % 1_000_000_000_000L);
            items.add(mock(isbn, query + " 테스트도서 " + idx, "Mock description for " + query));
        }
        return items;
    }

    @Override
    public Optional<CatalogBookItem> lookupByIsbn(String isbn13) {
        simulateLatency();
        return Optional.of(mock(isbn13, "Mock Book " + isbn13, "Mock description"));
    }

    private static CatalogBookItem mock(String isbn, String title, String description) {
        return CatalogBookItem.builder()
                .isbn13(isbn)
                .title(title)
                .author("테스트저자")
                .publisher("테스트출판사")
                .coverImageUrl("https://mock.cover/image.jpg")
                .description(description)
                .publishedDate(LocalDate.of(2024, 1, 1))
                .category("국내도서-소설/시/희곡")
                .genre("소설/시/희곡")
                .build();
    }

    private void simulateLatency() {
        try {
            Thread.sleep(SIMULATED_LATENCY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
