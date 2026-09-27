package com.shelfeed.backend.domain.book.client;

import com.shelfeed.backend.domain.book.client.dto.CatalogBookItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@DisplayName("Yes24ApiClient 단위 테스트 (MockRestServiceServer)")
class Yes24ApiClientTest {

    private MockRestServiceServer server;
    private Yes24ApiClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new Yes24ApiClient(restTemplate);
    }

    // 실제 YES24 응답(2026-09-28 확인)을 축약한 봉투
    private static String envelope(String items) {
        return """
                {"success":true,"message":"성공","data":{"meta":{"apiTitle":"상품 검색"},"items":[%s],
                 "currentPage":1,"pageSize":2,"totalCount":11}}
                """.formatted(items);
    }

    private static final String DEMIAN = """
            {"sortOrder":1,"itemId":176787,"title":"데미안","subTitle":"","author":"헤르만 헤세 저/전영애 역",
             "goodsType":"도서","goodsSortNm":"국내도서-소설/시/희곡","adultYn":"N","publisher":"민음사",
             "isbn10":"8937460440","isbn13":"9788937460449","shopPrice":8000.00,"salePrice":7200.00,
             "publishDate":"20001220","itemStatus":"판매중","cover":"https://image.yes24.com/goods/176787/L",
             "link":"https://www.yes24.com/product/goods/176787","pages":240,
             "contentDetail":{"bookIntroduction":"싱클레어의 성장 이야기","bookSummary":null,"tableOfContents":null}}
            """;

    @Test
    @DisplayName("검색: percent 인코딩된 한글 쿼리로 itemList를 호출하고 필드를 매핑한다")
    void 검색_성공_매핑() {
        server.expect(requestTo("https://apis.yes24.com/v1/goods/itemList?query=%EB%8D%B0%EB%AF%B8%EC%95%88&category=BOOK&sort=RELATION&page=1&pageSize=2"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(envelope(DEMIAN), MediaType.APPLICATION_JSON));

        List<CatalogBookItem> items = client.search("데미안", 1, 2);

        server.verify();
        assertThat(items).hasSize(1);
        CatalogBookItem item = items.get(0);
        assertThat(item.getIsbn13()).isEqualTo("9788937460449");
        assertThat(item.getTitle()).isEqualTo("데미안");
        assertThat(item.getAuthor()).isEqualTo("헤르만 헤세 저/전영애 역");
        assertThat(item.getPublisher()).isEqualTo("민음사");
        assertThat(item.getCoverImageUrl()).isEqualTo("https://image.yes24.com/goods/176787/L");
        assertThat(item.getDescription()).isEqualTo("싱클레어의 성장 이야기");
        assertThat(item.getTotalPages()).isEqualTo(240);
        assertThat(item.getPublishedDate()).isEqualTo(LocalDate.of(2000, 12, 20));
        assertThat(item.getExternalItemId()).isEqualTo("176787");
        assertThat(item.getCategory()).isEqualTo("국내도서-소설/시/희곡");
        assertThat(item.getGenre()).isEqualTo("소설/시/희곡");
    }

    @Test
    @DisplayName("검색: 429(한도 초과)는 '없는 책'이 아니라 제공처 불가 예외다")
    void 검색_429_제공처_불가() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", "1");
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://apis.yes24.com/v1/goods/itemList")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":false,\"message\":\"요청 한도 초과\",\"data\":null,\"errorCode\":\"RATE_001\"}"));

        assertThatThrownBy(() -> client.search("데미안", 1, 2))
                .isInstanceOf(CatalogUnavailableException.class)
                .hasMessageContaining("429");
        server.verify();
    }

    @Test
    @DisplayName("검색: 5xx·401(키 오류)·네트워크 오류도 제공처 불가 예외다")
    void 검색_5xx_401_네트워크_제공처_불가() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://apis.yes24.com/v1/goods/itemList")))
                .andRespond(withServerError());
        assertThatThrownBy(() -> client.search("x", 1, 1)).isInstanceOf(CatalogUnavailableException.class);

        server.reset();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://apis.yes24.com/v1/goods/itemList")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":false,\"message\":\"유효하지 않은 API Key입니다.\",\"data\":null,\"errorCode\":\"AUTH_002\"}"));
        assertThatThrownBy(() -> client.search("x", 1, 1)).isInstanceOf(CatalogUnavailableException.class);

        server.reset();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://apis.yes24.com/v1/goods/itemList")))
                .andRespond(request -> { throw new java.net.SocketTimeoutException("read timed out"); });
        assertThatThrownBy(() -> client.search("x", 1, 1)).isInstanceOf(CatalogUnavailableException.class);
    }

    @Test
    @DisplayName("검색: 400(잘못된 파라미터)은 우리 쪽 문제 — 예외 없이 빈 목록")
    void 검색_400_빈_목록() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://apis.yes24.com/v1/goods/itemList")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":false,\"message\":\"query 누락\",\"data\":null,\"errorCode\":\"PARAM_003\"}"));

        assertThat(client.search("x", 1, 1)).isEmpty();
    }

    @Test
    @DisplayName("검색: 200이지만 success=false(봉투 오류)면 빈 목록")
    void 검색_success_false_빈_목록() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://apis.yes24.com/v1/goods/itemList")))
                .andRespond(withSuccess("{\"success\":false,\"message\":\"err\",\"data\":null,\"errorCode\":\"SEARCH_001\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.search("x", 1, 1)).isEmpty();
    }

    @Test
    @DisplayName("ISBN 조회: itemDetail(searchType=ISBN13, detail=Y)을 호출하고 첫 항목을 돌려준다")
    void ISBN_조회_성공() {
        server.expect(requestTo("https://apis.yes24.com/v1/goods/itemDetail?searchType=ISBN13&query=9788937460449&detail=Y"))
                .andRespond(withSuccess(envelope(DEMIAN), MediaType.APPLICATION_JSON));

        Optional<CatalogBookItem> item = client.lookupByIsbn("9788937460449");

        server.verify();
        assertThat(item).isPresent();
        assertThat(item.get().getTotalPages()).isEqualTo(240);
        assertThat(item.get().getExternalItemId()).isEqualTo("176787");
    }

    @Test
    @DisplayName("ISBN 조회: items가 비어 있으면 Optional.empty")
    void ISBN_조회_빈_items() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://apis.yes24.com/v1/goods/itemDetail")))
                .andRespond(withSuccess(envelope(""), MediaType.APPLICATION_JSON));

        assertThat(client.lookupByIsbn("0000000000000")).isEmpty();
    }

    @Test
    @DisplayName("ISBN 조회: 404(GOODS_002 미매칭)도 예외 없이 Optional.empty")
    void ISBN_조회_404() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://apis.yes24.com/v1/goods/itemDetail")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":false,\"message\":\"ISBN13 미매칭\",\"data\":null,\"errorCode\":\"GOODS_002\"}"));

        assertThat(client.lookupByIsbn("0000000000000")).isEmpty();
    }
}
