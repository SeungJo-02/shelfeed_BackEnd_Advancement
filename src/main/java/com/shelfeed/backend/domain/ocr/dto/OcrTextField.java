package com.shelfeed.backend.domain.ocr.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

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
