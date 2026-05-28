package com.shelfeed.backend.domain.ocr.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * CLOVA OCR General V2 API 응답 매핑 DTO.
 * 외부 API 응답 구조를 그대로 매핑하며, 알 수 없는 필드는 무시한다.
 */
@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClovaOcrApiResponse {

    private List<ImageResult> images;

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ImageResult {
        private String inferResult;
        private List<Field> fields;
    }

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Field {
        private String inferText;
        private Float inferConfidence;
        private boolean lineBreak;
        private BoundingPoly boundingPoly;
    }

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BoundingPoly {
        private List<VertexPoint> vertices;
    }

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VertexPoint {
        private Double x;
        private Double y;
    }
}
