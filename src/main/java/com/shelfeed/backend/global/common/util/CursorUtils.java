package com.shelfeed.backend.global.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shelfeed.backend.global.common.exception.BusinessException;
import com.shelfeed.backend.global.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
@RequiredArgsConstructor
public class CursorUtils {

    private final ObjectMapper objectMapper;

    private record CursorPayload(Long id) {}

    public Long decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            byte[] decoded = Base64.getDecoder().decode(cursor);
            String json = new String(decoded, StandardCharsets.UTF_8);
            return objectMapper.readValue(json, CursorPayload.class).id();
        } catch (IllegalArgumentException | JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    public String encode(Long id) {
        try {
            String json = objectMapper.writeValueAsString(new CursorPayload(id));
            return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }
}
