package com.shelfeed.backend.domain.book.client;

import com.shelfeed.backend.domain.book.client.dto.CatalogBookItem;
import com.shelfeed.backend.domain.book.client.dto.Yes24Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CatalogBookItem — YES24 항목 변환")
class CatalogBookItemTest {

    private static Yes24Item yes24(String publishDate, String goodsSortNm, Yes24Item.ContentDetail detail) {
        Yes24Item item = new Yes24Item();
        item.setItemId(176787L);
        item.setTitle("데미안");
        item.setAuthor("헤르만 헤세 저/전영애 역");
        item.setPublisher("민음사");
        item.setIsbn13("9788937460449");
        item.setCover("https://image.yes24.com/goods/176787/L");
        item.setPublishDate(publishDate);
        item.setPages(240);
        item.setGoodsSortNm(goodsSortNm);
        item.setContentDetail(detail);
        return item;
    }

    @Test
    @DisplayName("yyyyMMdd 날짜, '국내도서-분류' 장르, bookIntroduction 설명을 매핑한다")
    void 정상_매핑() {
        Yes24Item.ContentDetail detail = new Yes24Item.ContentDetail();
        detail.setBookIntroduction("소개");
        detail.setBookSummary("요약");

        CatalogBookItem item = CatalogBookItem.fromYes24(yes24("20001220", "국내도서-소설/시/희곡", detail));

        assertThat(item.getIsbn13()).isEqualTo("9788937460449");
        assertThat(item.getTitle()).isEqualTo("데미안");
        assertThat(item.getAuthor()).isEqualTo("헤르만 헤세 저/전영애 역");
        assertThat(item.getPublisher()).isEqualTo("민음사");
        assertThat(item.getCoverImageUrl()).isEqualTo("https://image.yes24.com/goods/176787/L");
        assertThat(item.getDescription()).isEqualTo("소개");
        assertThat(item.getTotalPages()).isEqualTo(240);
        assertThat(item.getPublishedDate()).isEqualTo(LocalDate.of(2000, 12, 20));
        assertThat(item.getExternalItemId()).isEqualTo("176787");
        assertThat(item.getCategory()).isEqualTo("국내도서-소설/시/희곡");
        assertThat(item.getGenre()).isEqualTo("소설/시/희곡");
    }

    @Test
    @DisplayName("깨진 날짜는 null, contentDetail이 없으면 설명도 null")
    void 깨진_날짜와_없는_소개() {
        CatalogBookItem item = CatalogBookItem.fromYes24(yes24("2000122", "국내도서-인문", null));

        assertThat(item.getPublishedDate()).isNull();
        assertThat(item.getDescription()).isNull();
        assertThat(item.getGenre()).isEqualTo("인문");
    }

    @Test
    @DisplayName("yyyy-MM-dd 형식도 받아준다 (mock·시드 데이터 호환)")
    void ISO_날짜도_허용() {
        CatalogBookItem item = CatalogBookItem.fromYes24(yes24("2024-01-01", "국내도서-에세이", null));
        assertThat(item.getPublishedDate()).isEqualTo(LocalDate.of(2024, 1, 1));
    }

    @Test
    @DisplayName("분류에 구분자가 없으면 단일 계층을 그대로 장르로 쓰고, 비어 있으면 null")
    void 단일_계층_및_빈_분류() {
        assertThat(CatalogBookItem.fromYes24(yes24(null, "국내도서", null)).getGenre()).isEqualTo("국내도서");
        assertThat(CatalogBookItem.fromYes24(yes24(null, "  ", null)).getGenre()).isNull();
        assertThat(CatalogBookItem.fromYes24(yes24(null, null, null)).getCategory()).isNull();
        // 분류명 자체에 '-'가 더 있어도 첫 구분자에서만 나눈다
        assertThat(CatalogBookItem.fromYes24(yes24(null, "국내도서-IT 모바일-프로그래밍", null)).getGenre())
                .isEqualTo("IT 모바일-프로그래밍");
    }
}
