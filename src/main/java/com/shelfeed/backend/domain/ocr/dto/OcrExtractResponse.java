package com.shelfeed.backend.domain.ocr.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class OcrExtractResponse {
    private final String extractedText;
    private final List<OcrTextField> fields;
}
