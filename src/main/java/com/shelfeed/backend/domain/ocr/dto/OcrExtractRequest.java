package com.shelfeed.backend.domain.ocr.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * OCR 텍스트 추출 요청 DTO.
 * Base64 인코딩된 이미지 데이터와 형식(jpg, png 등)을 전달받는다.
 */
@Getter
@NoArgsConstructor
public class OcrExtractRequest {

    @NotBlank(message = "이미지 데이터는 필수입니다.")
    @Size(max = 7_000_000, message = "이미지 데이터는 약 5MB를 초과할 수 없습니다.")
    private String imageData;

    @NotBlank(message = "이미지 형식은 필수입니다.")
    private String imageFormat;
}
