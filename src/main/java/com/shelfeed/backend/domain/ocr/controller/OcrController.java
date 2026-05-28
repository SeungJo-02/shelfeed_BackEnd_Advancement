package com.shelfeed.backend.domain.ocr.controller;

import com.shelfeed.backend.domain.ocr.dto.OcrExtractRequest;
import com.shelfeed.backend.domain.ocr.dto.OcrExtractResponse;
import com.shelfeed.backend.domain.ocr.service.OcrService;
import com.shelfeed.backend.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OCR 텍스트 추출 API 컨트롤러.
 * 인증된 사용자만 호출 가능하며, SecurityConfig 기본 정책으로 보호된다.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class OcrController {

    private final OcrService ocrService;

    /**
     * 이미지에서 텍스트를 추출한다.
     *
     * @param request Base64 이미지 데이터와 형식
     * @return 추출된 전체 텍스트와 블록별 좌표 정보
     */
    @PostMapping("/ocr/extract-text")
    public ApiResponse<OcrExtractResponse> extractText(
            @Valid @RequestBody OcrExtractRequest request) {
        return ApiResponse.success(200, ocrService.extractText(request));
    }
}
