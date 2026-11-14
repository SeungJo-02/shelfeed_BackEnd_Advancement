package com.shelfeed.backend.domain.book.client.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 공급자 중립 도서 항목. 서비스·시더·테스트는 이 타입만 다룬다.
 *
 * <p>{@code category}에는 공급자가 준 분류 문자열이 그대로 들어간다
 * (YES24: {@code 국내도서-소설/시/희곡}, 과거 알라딘: {@code 국내도서>소설/시/희곡>한국소설}).
 * 장르 탐색의 {@code genres.category_pattern}은 두 형식을 모두 잡도록 REGEXP로 맞춰져 있다.
 */
@Getter
@Builder
public class CatalogBookItem {
    private final String isbn13;
    private final String title;
    private final String author;
    private final String publisher;
    private final String coverImageUrl;
    private final String description;
    private final Integer totalPages;
    private final LocalDate publishedDate;
    /** 공급자 상품 번호 (YES24 itemId). */
    private final String externalItemId;
    private final String category;
    /** 분류의 두 번째 계층 — 예: {@code 국내도서-소설/시/희곡} → {@code 소설/시/희곡}. */
    private final String genre;

    /**
     * YES24 상품 항목 → 중립 항목.
     * 검색(detail=N) 응답에도 {@code contentDetail.bookIntroduction}은 들어오지만 {@code pages}는 detail=Y 전용이라 null일 수 있다.
     */
    public static CatalogBookItem fromYes24(Yes24Item item) {
        Yes24Item.ContentDetail detail = item.getContentDetail();
        return CatalogBookItem.builder()
                .isbn13(item.getIsbn13())
                .title(item.getTitle())
                .author(item.getAuthor())
                .publisher(item.getPublisher())
                .coverImageUrl(item.getCover())
                .description(detail != null ? detail.getBookIntroduction() : null)
                .totalPages(item.getPages())
                .publishedDate(parseDate(item.getPublishDate()))
                .externalItemId(item.getItemId() != null ? String.valueOf(item.getItemId()) : null)
                .category(item.getGoodsSortNm())
                .genre(extractGenre(item.getGoodsSortNm()))
                .build();
    }

    /** YES24는 {@code yyyyMMdd}. 혹시 {@code yyyy-MM-dd}로 와도 받아주고, 그 외는 null. */
    static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        try {
            return LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE);
        } catch (DateTimeParseException ignored) { }
        try {
            return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ignored) { }
        return null;
    }

    /** {@code "국내도서-소설/시/희곡"} → {@code "소설/시/희곡"}, 구분자가 없으면 그대로 (단일 계층 보존). */
    static String extractGenre(String goodsSortNm) {
        if (goodsSortNm == null || goodsSortNm.isBlank()) return null;
        String[] parts = goodsSortNm.split("-", 2);
        return (parts.length == 2 ? parts[1] : parts[0]).trim();
    }
}
