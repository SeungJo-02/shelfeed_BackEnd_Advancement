package com.shelfeed.backend.domain.ocr.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * OCR 인식 텍스트 블록.
 * 단일 텍스트 조각과 이미지 내 위치 좌표(vertices)를 담는다.
 * lineBreak가 true이면 해당 블록 뒤에서 줄바꿈이 발생한다.
 */
@Getter
@Builder
public class OcrTextField {
    private final String text;
    private final boolean lineBreak;
    private final List<Vertex> vertices;

    @Getter
    @Builder
    public static class Vertex {
        private final double x;
        private final double y;
    }
}
