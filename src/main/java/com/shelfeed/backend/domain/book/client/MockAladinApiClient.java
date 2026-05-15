package com.shelfeed.backend.domain.book.client;

import com.shelfeed.backend.domain.book.client.dto.AladinItem;
import com.shelfeed.backend.domain.book.client.dto.AladinSearchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Profile("mock-aladin & !prod")
@RequiredArgsConstructor
public class MockAladinApiClient implements AladinClient {

    // 실제 Aladin API P50 기준 지연 시뮬레이션 (부하테스트 현실성 확보)
    private static final int SIMULATED_LATENCY_MS = 30;

    @Override
    public AladinSearchResponse search(String query, int start, int maxResults) {
        simulateLatency();
        List<AladinItem> items = new ArrayList<>();
        for (int i = 0; i < maxResults; i++) {
            int idx = (start - 1) * maxResults + i;
            AladinItem item = new AladinItem();
            item.setIsbn13(String.format("8%012d", Math.abs((query + idx).hashCode()) % 1_000_000_000_000L));
            item.setTitle(query + " 테스트도서 " + idx);
            item.setAuthor("테스트저자");
            item.setPublisher("테스트출판사");
            item.setCover("https://mock.cover/image.jpg");
            item.setDescription("Mock description for " + query);
            item.setCategoryName("국내도서>소설/시/희곡>한국소설");
            item.setPubDate("2024-01-01");
            items.add(item);
        }
        AladinSearchResponse response = new AladinSearchResponse();
        response.setItems(items);
        return response;
    }

    @Override
    public AladinSearchResponse lookupByIsbn(String isbn13) {
        simulateLatency();
        AladinItem item = new AladinItem();
        item.setIsbn13(isbn13);
        item.setTitle("Mock Book " + isbn13);
        item.setAuthor("테스트저자");
        item.setPublisher("테스트출판사");
        item.setCover("https://mock.cover/image.jpg");
        item.setDescription("Mock description");
        item.setCategoryName("국내도서>소설/시/희곡>한국소설");
        item.setPubDate("2024-01-01");
        AladinSearchResponse response = new AladinSearchResponse();
        response.setItems(List.of(item));
        return response;
    }

    private void simulateLatency() {
        try {
            Thread.sleep(SIMULATED_LATENCY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
