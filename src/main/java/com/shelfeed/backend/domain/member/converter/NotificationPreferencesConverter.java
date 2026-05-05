package com.shelfeed.backend.domain.member.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shelfeed.backend.domain.member.vo.NotificationPreferences;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Converter
public class NotificationPreferencesConverter implements AttributeConverter<NotificationPreferences, String> {

    private static final Logger logger = LoggerFactory.getLogger(NotificationPreferencesConverter.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(NotificationPreferences attr) {
        if (attr == null) return "{}";
        try { return mapper.writeValueAsString(attr); }
        catch (Exception e) {
            logger.error("mapper 직렬화 실패: attr={}, error={}", attr, e.getMessage(), e);
            return "{}";
        }
    }

    @Override
    public NotificationPreferences convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return new NotificationPreferences();
        try { return mapper.readValue(dbData, NotificationPreferences.class); }
        catch (Exception e) {
            logger.error("mapper 역직렬화 실패: dbData={}, error={}", dbData, e.getMessage(), e);
            return new NotificationPreferences();
        }
    }
}
