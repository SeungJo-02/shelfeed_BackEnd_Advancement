package com.shelfeed.backend.domain.book.client;

/**
 * 외부 도서 카탈로그(YES24)가 응답하지 못한 상태 — 429 한도 초과, 5xx, 인증 키 오류, 네트워크/타임아웃.
 * "그런 책이 없다"(빈 결과)와 구분하기 위해 던진다. 호출자는 검색이면 DB·ES 결과로 폴백하고,
 * ISBN 단건 조회면 503으로 올린다.
 */
public class CatalogUnavailableException extends RuntimeException {
    public CatalogUnavailableException(String message) {
        super(message);
    }

    public CatalogUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
