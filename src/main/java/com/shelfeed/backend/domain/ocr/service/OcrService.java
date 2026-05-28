package com.shelfeed.backend.domain.ocr.service;

import com.shelfeed.backend.domain.ocr.client.ClovaOcrClient;
import com.shelfeed.backend.domain.ocr.dto.OcrExtractRequest;
import com.shelfeed.backend.domain.ocr.dto.OcrExtractResponse;
import com.shelfeed.backend.domain.ocr.dto.OcrTextField;
import com.shelfeed.backend.global.common.exception.BusinessException;
import com.shelfeed.backend.global.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * OCR 텍스트 추출 서비스.
 * 이미지 형식 검증, 외부 OCR API 호출, 응답의 lineBreak 기반 텍스트 조합을 담당한다.
 */
@Service
@RequiredArgsConstructor
public class OcrService {

    private static final Set<String> SUPPORTED_FORMATS = Set.of("jpg", "jpeg", "png", "pdf", "tiff");

    private final ClovaOcrClient clovaOcrClient;

    /**
     * Base64 이미지를 OCR 처리하여 텍스트와 좌표를 반환한다.
     *
     * @param request 이미지 데이터와 형식을 담은 요청
     * @return 추출된 전체 텍스트와 블록별 좌표 목록
     * @throws BusinessException 지원하지 않는 형식이거나 OCR 처리 실패 시
     */
    public OcrExtractResponse extractText(OcrExtractRequest request) {
        String format = request.getImageFormat().strip().toLowerCase();
        if (!SUPPORTED_FORMATS.contains(format)) {
            throw new BusinessException(ErrorCode.OCR_INVALID_IMAGE);
        }

        List<OcrTextField> fields = clovaOcrClient.extractTextFields(request.getImageData(), format);

        StringBuilder sb = new StringBuilder();
        for (OcrTextField field : fields) {
            sb.append(field.getText());
            if (field.isLineBreak()) {
                sb.append("\n");
            } else {
                sb.append(" ");
            }
        }

        return OcrExtractResponse.builder()
                .extractedText(sb.toString().trim())
                .fields(fields)
                .build();
    }
}
