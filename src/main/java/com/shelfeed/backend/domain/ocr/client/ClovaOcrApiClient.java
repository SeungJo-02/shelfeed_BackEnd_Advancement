package com.shelfeed.backend.domain.ocr.client;

import com.shelfeed.backend.domain.ocr.dto.ClovaOcrApiResponse;
import com.shelfeed.backend.domain.ocr.dto.OcrTextField;
import com.shelfeed.backend.global.common.exception.BusinessException;
import com.shelfeed.backend.global.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ClovaOcrApiClient implements ClovaOcrClient {

    private final RestTemplate ocrRestTemplate;
    private final String secretKey;
    private final String apiUrl;

    public ClovaOcrApiClient(
            @Value("${clova.ocr.secret-key}") String secretKey,
            @Value("${clova.ocr.api-url}") String apiUrl) {
        this.secretKey = secretKey;
        this.apiUrl = apiUrl;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.ocrRestTemplate = new RestTemplate(factory);
    }

    @Override
    public List<OcrTextField> extractTextFields(String base64Image, String imageFormat) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-OCR-SECRET", secretKey);

        Map<String, Object> image = new HashMap<>();
        image.put("format", imageFormat);
        image.put("name", "quote");
        image.put("data", base64Image);

        Map<String, Object> body = new HashMap<>();
        body.put("version", "V2");
        body.put("requestId", UUID.randomUUID().toString());
        body.put("timestamp", System.currentTimeMillis());
        body.put("images", List.of(image));

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ClovaOcrApiResponse response = ocrRestTemplate.postForObject(apiUrl, request, ClovaOcrApiResponse.class);

            if (response == null || response.getImages() == null || response.getImages().isEmpty()) {
                throw new BusinessException(ErrorCode.OCR_PROCESSING_FAILED);
            }

            ClovaOcrApiResponse.ImageResult result = response.getImages().get(0);
            if (!"SUCCESS".equals(result.getInferResult()) || result.getFields() == null) {
                throw new BusinessException(ErrorCode.OCR_PROCESSING_FAILED);
            }

            return result.getFields().stream()
                    .map(field -> OcrTextField.builder()
                            .text(field.getInferText())
                            .lineBreak(field.isLineBreak())
                            .vertices(field.getBoundingPoly() != null && field.getBoundingPoly().getVertices() != null
                                    ? field.getBoundingPoly().getVertices().stream()
                                        .map(v -> OcrTextField.Vertex.builder()
                                                .x(v.getX() != null ? v.getX() : 0)
                                                .y(v.getY() != null ? v.getY() : 0)
                                                .build())
                                        .collect(Collectors.toList())
                                    : List.of())
                            .build())
                    .collect(Collectors.toList());

        } catch (RestClientException e) {
            log.error("CLOVA OCR API 호출 실패", e);
            throw new BusinessException(ErrorCode.OCR_PROCESSING_FAILED, e);
        }
    }
}
