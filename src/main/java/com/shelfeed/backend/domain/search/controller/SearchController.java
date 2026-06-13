package com.shelfeed.backend.domain.search.controller;

import com.shelfeed.backend.domain.search.dto.response.SearchResponse;
import com.shelfeed.backend.domain.search.service.SearchService;
import com.shelfeed.backend.global.common.response.ApiResponse;
import com.shelfeed.backend.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    // 10.1 통합 검색  GET /api/v1/search
    // cursor: 단일 타입(type=book/user) 검색용 하위호환 파라미터.
    // bookCursor/userCursor: type=all에서 books·users를 독립적으로 페이징하기 위한 분리 커서.
    // (books 커서=bookId, users 커서=memberUserId로 ID 공간이 달라 단일 cursor 공유 시 페이징이 어긋남)
    @GetMapping
    public ApiResponse<SearchResponse> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "all") String type,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Long bookCursor,
            @RequestParam(required = false) Long userCursor,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Long memberUserId = userDetails != null ? userDetails.getMember().getMemberUserId() : null;
        return ApiResponse.success(200,
                searchService.search(query, type, cursor, bookCursor, userCursor, limit, memberUserId));
    }
}