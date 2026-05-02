package com.shelfeed.backend.domain.book.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BookSearchRequest { //API 명세서 속 쿼리 파라미터 용
    private String query;    // 검색어 (필수)

    @Schema(defaultValue = "20", minimum = "1", maximum = "50")
    private int limit = 20;

    @Schema(defaultValue = "1", minimum = "1", description = "알라딘 API 페이지 번호")
    private int page = 1;
}
