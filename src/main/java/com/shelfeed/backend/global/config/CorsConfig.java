package com.shelfeed.backend.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
public class CorsConfig {

    // 콤마로 여러 오리진 허용 가능. 운영에서는 CORS_ALLOWED_ORIGIN 환경변수로 실제 프론트 도메인을 반드시 주입할 것
    // (미주입 시 localhost 기본값으로 동작하므로 운영 배포 시 누락 금지).
    @Value("${cors.allowed-origin:http://localhost:5173}")
    private String allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // 콤마 구분 다중 오리진 지원 (공백 trim)
        config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // "*" 대신 실제 사용하는 요청 헤더만 화이트리스트 (credentials 허용 시 와일드카드 지양)
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        // 리프레시 토큰이 httpOnly 쿠키로 교차 오리진 전송되므로 credentials 허용은 유지해야 한다.
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
