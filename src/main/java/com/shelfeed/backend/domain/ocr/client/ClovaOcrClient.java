package com.shelfeed.backend.domain.ocr.client;

import com.shelfeed.backend.domain.ocr.dto.OcrTextField;

import java.util.List;

public interface ClovaOcrClient {
    List<OcrTextField> extractTextFields(String base64Image, String imageFormat);
}
