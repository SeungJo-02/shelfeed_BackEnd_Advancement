package com.shelfeed.backend.domain.book.client;

import com.shelfeed.backend.domain.book.client.dto.CatalogBookItem;
import com.shelfeed.backend.domain.book.client.dto.Yes24Item;
import com.shelfeed.backend.domain.book.client.dto.Yes24Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * YES24 Open API 클라이언트.
 *
 * <ul>
 *   <li>인증: {@code X-Api-Key} 헤더 — {@code yes24RestTemplate}의 인터셉터가 붙인다.</li>
 *   <li>한도: 초당 5건 · 일 5,000건. 초과 시 429 + {@code Retry-After}.</li>
 *   <li>한글 검색어는 반드시 percent 인코딩해서 보내야 한다. 원문 그대로 보내면 서버가 깨진 문자열로 검색한다.
 *       {@link UriComponentsBuilder#encode()} 한 번으로 충분하다 (이중 인코딩 금지).</li>
 *   <li>429·5xx·401(키 오류)·타임아웃은 {@link CatalogUnavailableException}으로 던진다 — "제공처 장애"이지 "없는 책"이 아니다.
 *       404(GOODS_002 미매칭)·빈 items·{@code success=false} 봉투는 빈 결과로 돌려준다.</li>
 * </ul>
 */
@Slf4j
@Component
@Profile("!mock-catalog")
public class Yes24ApiClient implements BookCatalogClient {

    static final String BASE_URL = "https://apis.yes24.com";
    static final String SEARCH_PATH = "/v1/goods/itemList";
    static final String DETAIL_PATH = "/v1/goods/itemDetail";

    private final RestTemplate restTemplate;

    public Yes24ApiClient(@Qualifier("yes24RestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public List<CatalogBookItem> search(String query, int page, int pageSize) {
        URI uri = UriComponentsBuilder.fromUriString(BASE_URL + SEARCH_PATH)
                .queryParam("query", query)
                .queryParam("category", "BOOK")      // 국내도서 한정 (알라딘 SearchTarget=Book에 대응)
                .queryParam("sort", "RELATION")      // 정확도순
                .queryParam("page", page)
                .queryParam("pageSize", pageSize)
                .encode()
                .build()
                .toUri();
        return call(uri, "search").stream().map(CatalogBookItem::fromYes24).toList();
    }

    @Override
    public Optional<CatalogBookItem> lookupByIsbn(String isbn13) {
        URI uri = UriComponentsBuilder.fromUriString(BASE_URL + DETAIL_PATH)
                .queryParam("searchType", "ISBN13")
                .queryParam("query", isbn13)
                .queryParam("detail", "Y")           // pages 등 전체 정보
                .encode()
                .build()
                .toUri();
        return call(uri, "lookupByIsbn").stream().findFirst().map(CatalogBookItem::fromYes24);
    }

    private List<Yes24Item> call(URI uri, String op) {
        try {
            Yes24Response response = restTemplate.getForObject(uri, Yes24Response.class);
            if (response == null) return List.of();
            if (!Boolean.TRUE.equals(response.getSuccess())) {
                // 200 봉투 안의 실패(SEARCH_001 무결과 등)는 "없음"으로 본다.
                log.warn("YES24 {} 실패 응답: errorCode={}, message={}", op, response.getErrorCode(), response.getMessage());
                return List.of();
            }
            return response.itemsOrEmpty();
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            String body = abbreviate(e.getResponseBodyAsString());
            if (status == HttpStatus.NOT_FOUND.value()) {
                // GOODS_001/GOODS_002/SEARCH_001 — 매칭되는 상품이 없다.
                log.debug("YES24 {} 미매칭(404): body={}", op, body);
                return List.of();
            }
            if (status == HttpStatus.TOO_MANY_REQUESTS.value()
                    || status == HttpStatus.UNAUTHORIZED.value()
                    || e.getStatusCode().is5xxServerError()) {
                // 한도 초과·키 오류·서버 장애 — 제공처를 쓸 수 없는 상태. 호출자가 폴백/503을 결정한다.
                String retryAfter = e.getResponseHeaders() != null ? e.getResponseHeaders().getFirst("Retry-After") : null;
                log.warn("YES24 {} 제공처 불가: status={}, retryAfter={}, body={}", op, status, retryAfter, body);
                throw new CatalogUnavailableException("YES24 " + op + " status=" + status, e);
            }
            // 그 외 4xx(잘못된 파라미터 등)는 우리 쪽 요청 문제 — 결과 없음으로 처리하고 로그만 남긴다.
            log.warn("YES24 {} HTTP 오류: status={}, body={}", op, status, body);
            return List.of();
        } catch (RestClientException e) {
            // 연결/읽기 타임아웃, 역직렬화 실패 등
            log.warn("YES24 {} 호출 실패: {}", op, e.getMessage());
            throw new CatalogUnavailableException("YES24 " + op + " 호출 실패", e);
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return null;
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
