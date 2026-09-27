package com.shelfeed.backend.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

@Configuration
public class WebConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    // RestTemplate 빈이 둘이 됐다(yes24RestTemplate 추가). 한정자 없는 주입은 기존처럼 이 공용 빈을 받는다.
    @Bean
    @Primary
    public RestTemplate restTemplate() {
        // 외부 API 호출이 무한정 요청 스레드를 점유하지 않도록 connect/read 타임아웃 설정.
        // (타임아웃 미설정 시 외부 의존성 지연이 Tomcat 스레드풀 고갈로 전파됨)
        RestTemplate restTemplate = new RestTemplate(requestFactory());

        // 일부 외부 API는 JSON을 text/javascript, text/plain 등으로 내려준다 → Jackson이 처리할 수 있도록 추가
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setSupportedMediaTypes(List.of(
                MediaType.APPLICATION_JSON,
                MediaType.TEXT_PLAIN,
                new MediaType("text", "javascript"),
                new MediaType("application", "javascript"),
                MediaType.TEXT_HTML
        ));
        restTemplate.getMessageConverters().add(0, converter);

        return restTemplate;
    }

    /**
     * YES24 Open API 전용 RestTemplate.
     * 모든 요청에 {@code X-Api-Key} 헤더를 붙인다. 키를 URL 쿼리에 싣지 않으므로 접근 로그에 노출되지 않는다.
     * {@code mock-catalog} 프로파일에서는 MockCatalogClient만 뜨므로 이 빈도 만들지 않는다 — 키 없이 부팅 가능.
     */
    @Bean
    @Profile("!mock-catalog")
    public RestTemplate yes24RestTemplate(@Value("${yes24.api.key:}") String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            // 키가 비면 모든 호출이 401 → CatalogUnavailableException으로 떨어진다. 부팅은 막지 않되 원인을 남긴다.
            org.slf4j.LoggerFactory.getLogger(WebConfig.class)
                    .warn("YES24_API_KEY가 비어 있다 — 도서 검색·ISBN 조회가 제공처 불가(503/빈 결과)로 동작한다.");
        }
        RestTemplate restTemplate = new RestTemplate(requestFactory());
        ClientHttpRequestInterceptor apiKeyHeader = (request, body, execution) -> {
            request.getHeaders().set("X-Api-Key", apiKey);
            return execution.execute(request, body);
        };
        restTemplate.getInterceptors().add(apiKeyHeader);
        return restTemplate;
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }
}
