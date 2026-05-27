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

@Service
@RequiredArgsConstructor
public class OcrService {

    private static final Set<String> SUPPORTED_FORMATS = Set.of("jpg", "jpeg", "png", "pdf", "tiff");

    private final ClovaOcrClient clovaOcrClient;

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
