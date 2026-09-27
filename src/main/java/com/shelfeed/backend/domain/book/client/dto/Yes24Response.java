package com.shelfeed.backend.domain.book.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * YES24 Open API 공통 봉투 {@code ApiResponse<PagedResult<GoodsInfo>>}.
 * 오류 시 {@code success=false, data=null, errorCode="AUTH_002"} 같은 형태로 온다.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class Yes24Response {
    private Boolean success;
    private String message;
    private String errorCode;
    private Data data;

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Data {
        private List<Yes24Item> items;
        private Integer totalCount;
        private Integer currentPage;
        private Integer pageSize;
    }

    /** 봉투를 벗겨 항목 목록만 돌려준다. 실패 응답·빈 응답은 빈 리스트. */
    public List<Yes24Item> itemsOrEmpty() {
        if (!Boolean.TRUE.equals(success) || data == null || data.items == null) return List.of();
        return data.items;
    }
}
