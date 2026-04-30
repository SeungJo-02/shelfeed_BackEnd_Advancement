package com.shelfeed.backend.domain.book.controller;

import com.shelfeed.backend.domain.book.dto.request.BookReviewSearchRequest;
import com.shelfeed.backend.domain.book.dto.request.BookSearchRequest;
import com.shelfeed.backend.domain.book.dto.response.BookDetailResponse;
import com.shelfeed.backend.domain.book.dto.response.BookReviewListResponse;
import com.shelfeed.backend.domain.book.dto.response.BookSearchListResponse;
import com.shelfeed.backend.domain.book.service.BookService;
import com.shelfeed.backend.global.common.response.ApiResponse;
import com.shelfeed.backend.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/books")
@RequiredArgsConstructor
public class BookController {
    private final BookService bookService;

    // 1. 도서 검색  GET /api/v1/books/search
    @GetMapping("/search")
    public ApiResponse<BookSearchListResponse> searchBooks(
            @ModelAttribute BookSearchRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails){
        Long memberUserId = userDetails != null ? userDetails.getMember().getMemberUserId():null;
        return ApiResponse.success(200, bookService.searchBooks(request,memberUserId));
    }

    // 2. 도서 상세 조회  GET /api/v1/books/{bookId}
    @GetMapping("/{bookId}")
    public ApiResponse<BookDetailResponse> getBook(
            @PathVariable Long bookId,
            @AuthenticationPrincipal CustomUserDetails userDetails){
        Long memberUserId = userDetails != null ? userDetails.getMember().getMemberUserId() : null;
        return ApiResponse.success(200, bookService.getBook(bookId,memberUserId));
    }

    // 3. ISBN 조회  GET /api/v1/books/isbn/{isbn13}
    @GetMapping("/isbn/{isbn13}")
    public ApiResponse<BookDetailResponse> getBookByIsbn(
            @PathVariable String isbn13,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Long memberUserId = userDetails != null ? userDetails.getMember().getMemberUserId() : null;
        return ApiResponse.success(200, bookService.getBookByIsbn(isbn13, memberUserId));
    }

    // 4. 도서별 감상 목록  GET /api/v1/books/{bookId}/reviews
    @GetMapping("/{bookId}/reviews")
    public ApiResponse<BookReviewListResponse> getBookReviews(
            @PathVariable Long bookId,
            @ModelAttribute BookReviewSearchRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Long memberUserId = userDetails != null ? userDetails.getMember().getMemberUserId() : null;
        return ApiResponse.success(200, bookService.getBookReviews(bookId, request, memberUserId));
    }
}
