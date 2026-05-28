package com.shelfeed.backend.domain.ocr.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * OCR 텍스트 추출 응답 DTO.
 * 전체 텍스트(extractedText)와 블록별 좌표 정보(fields)를 포함한다.
 * 프론트엔드에서 fields의 좌표를 이용해 이미지 위에 텍스트를 오버레이한다.
 */
@Getter
@Builder
public class OcrExtractResponse {
    private final String extractedText;
    private final List<OcrTextField> fields;
}
