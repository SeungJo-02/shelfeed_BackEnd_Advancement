package com.shelfeed.backend.domain.book.client;

import com.shelfeed.backend.domain.book.client.dto.CatalogBookItem;

import java.util.List;
import java.util.Optional;

/**
 * 외부 도서 카탈로그(현재 YES24 Open API) 조회 포트.
 *
 * <p>공급자 응답 DTO는 이 인터페이스 밖으로 새지 않는다 — 서비스는 {@link CatalogBookItem}만 본다.
 * 알라딘 OpenAPI 종료(2026-10-30)로 YES24로 갈아탄 자리이며, 다음 교체 때는 구현체 하나만 추가하면 된다.
 *
 * <p>빈 결과는 "그런 책이 없다"이고, 제공처 장애(429·5xx·키 오류·타임아웃)는
 * {@link CatalogUnavailableException}이다. 검색은 이를 잡아 DB·ES 결과로 폴백하고, ISBN 조회는 503으로 올린다.
 */
public interface BookCatalogClient {

    /** 키워드 검색. page는 1부터. 무결과 시 빈 리스트. @throws CatalogUnavailableException 제공처 장애 */
    List<CatalogBookItem> search(String query, int page, int pageSize);

    /** ISBN13 단건 조회. 미매칭 시 empty. @throws CatalogUnavailableException 제공처 장애 */
    Optional<CatalogBookItem> lookupByIsbn(String isbn13);
}
