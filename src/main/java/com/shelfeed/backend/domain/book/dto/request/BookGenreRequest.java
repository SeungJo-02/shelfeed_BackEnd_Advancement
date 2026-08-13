package com.shelfeed.backend.domain.book.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BookGenreRequest { // 장르별 도서 조회 쿼리 파라미터

    @Schema(description = "조회할 장르 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long genreId;

    @Schema(defaultValue = "20", minimum = "1", maximum = "50")
    private int limit = 20;

    @Schema(defaultValue = "1", minimum = "1", description = "1부터 시작하는 페이지 번호")
    private int page = 1;
}
