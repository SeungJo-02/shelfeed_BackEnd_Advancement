package com.shelfeed.backend.domain.book.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * YES24 Open API 상품 항목 ({@code GoodsInfo}).
 * 검색({@code /v1/goods/itemList})과 상세({@code /v1/goods/itemDetail}) 모두 같은 구조로 {@code data.items[]}에 담겨 온다.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class Yes24Item {
    private Long itemId;
    private String title;
    private String author;        // 예: "헤르만 헤세 저/전영애 역"
    private String publisher;
    private String isbn13;
    private String cover;         // 표지 이미지 URL
    private String publishDate;   // yyyyMMdd
    private Integer pages;        // detail=Y 전용
    private String goodsSortNm;   // 예: "국내도서-소설/시/희곡"
    private ContentDetail contentDetail;

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContentDetail {
        private String bookIntroduction;
        private String bookSummary;
        private String tableOfContents;
    }
}
