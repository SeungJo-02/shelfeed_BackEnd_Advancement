package com.shelfeed.backend.domain.ocr.client;

import com.shelfeed.backend.domain.ocr.dto.OcrTextField;

import java.util.List;

/**
 * OCR 외부 API 클라이언트 인터페이스.
 * 구현체를 교체하여 다른 OCR 서비스로 전환하거나 테스트 시 Mock을 주입할 수 있다.
 */
public interface ClovaOcrClient {

    /**
     * Base64 인코딩된 이미지에서 텍스트 블록을 추출한다.
     *
     * @param base64Image Base64 인코딩된 이미지 데이터
     * @param imageFormat 이미지 형식 (jpg, png, pdf, tiff)
     * @return 인식된 텍스트 블록 목록 (텍스트 + boundingPoly 좌표 포함)
     */
    List<OcrTextField> extractTextFields(String base64Image, String imageFormat);
}
