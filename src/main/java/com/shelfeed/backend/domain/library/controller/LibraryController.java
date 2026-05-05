package com.shelfeed.backend.domain.library.controller;

import com.shelfeed.backend.domain.library.dto.request.LibraryBookAddRequest;
import com.shelfeed.backend.domain.library.dto.request.LibraryStatusUpdateRequest;
import com.shelfeed.backend.domain.library.dto.response.*;
import com.shelfeed.backend.domain.library.enums.ReadingStatus;
import com.shelfeed.backend.domain.library.service.LibraryService;
import com.shelfeed.backend.global.common.response.ApiResponse;
import com.shelfeed.backend.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class LibraryController {

    private final LibraryService libraryService;

    //1. 서재 도서 추가 POST /api/v1/library
    @PostMapping("/library")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LibraryBookAddResponse> addBook(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody LibraryBookAddRequest request){
        Long memberUserId = userDetails.getMember().getMemberUserId();
        return ApiResponse.success(201,  "도서가 서재에 추가되었습니다.", libraryService.addBook(memberUserId,request));
    }
    //2. 내 서제 목록 조회 GET /api/v1/library/me
    @GetMapping("/library/me")
    public ApiResponse<LibraryListResponse> getMyLibrary(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(required = false) ReadingStatus status,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int limit) {
        Long memberUserId = userDetails.getMember().getMemberUserId();
        return ApiResponse.success(200,libraryService.getMyLibrary(memberUserId,status,cursor,limit));
    }

    //3. 서제 도서 상세조회 GET /api/v1/library/{libraryBookId}
    @GetMapping("/library/{libraryBookId}")
    public ApiResponse<LibraryBookDetailResponse> getLibraryBookDetail(
            @PathVariable Long libraryBookId,
            @AuthenticationPrincipal CustomUserDetails userDetails){
        Long memberUserId = userDetails.getMember().getMemberUserId();
        return ApiResponse.success(200, libraryService.getLibraryBookDetail(libraryBookId,memberUserId));
    }

    //4. 독서 상태 변경 PATCH /api/v1/library/{libraryBookId}/status
    @PatchMapping("/library/{libraryBookId}/status")
    public ApiResponse<LibraryStatusUpdateResponse> updateStatus(
            @PathVariable Long libraryBookId,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody LibraryStatusUpdateRequest request){
        Long memberUserId = userDetails.getMember().getMemberUserId();
        return ApiResponse.success(200,"독서 상태가 변경되었습니다.", libraryService.updateStatus(libraryBookId,memberUserId,request));
    }

    //5. 서제에서 도서 제거 DELETE /api/v1/library/{libraryBookId}
    @DeleteMapping("/library/{libraryBookId}")
    public ApiResponse<Void> removeBook(
            @PathVariable Long libraryBookId,
            @AuthenticationPrincipal CustomUserDetails userDetails){
        Long memberUserId = userDetails.getMember().getMemberUserId();
        libraryService.removeBook(libraryBookId, memberUserId);
        return ApiResponse.success(200,"서재에서 도서가 제거되었습니다.");
    }

    //6. 타 유저 서제 목록 조회 GET /api/v1/members/{userId}/library
    @GetMapping("/members/{userId}/library")
    public ApiResponse<UserLibraryResponse> getUserLibrary(
            @PathVariable Long userId,
            @RequestParam(required = false) ReadingStatus status,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal CustomUserDetails userDetails){
        Long requestingUserId = (userDetails != null) ? userDetails.getMember().getMemberUserId() : null;
        return ApiResponse.success(200, libraryService.getUserLibrary(userId,status,cursor,limit,requestingUserId));
    }
    //7. 지혜의 탑 GET /api/v1/library/me/wisdom-tower
    @GetMapping("/library/me/wisdom-tower")
    public ApiResponse<WisdomTowerResponse> getWisdomTower(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Long memberUserId = userDetails.getMember().getMemberUserId();
        return ApiResponse.success(200, libraryService.getWisdomTower(memberUserId));
    }
}
