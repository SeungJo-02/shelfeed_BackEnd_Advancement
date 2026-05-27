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

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class OcrController {

    private final OcrService ocrService;

    @PostMapping("/ocr/extract-text")
    public ApiResponse<OcrExtractResponse> extractText(
            @Valid @RequestBody OcrExtractRequest request) {
        return ApiResponse.success(200, ocrService.extractText(request));
    }
}
