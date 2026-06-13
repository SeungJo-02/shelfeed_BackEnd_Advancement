package com.shelfeed.backend.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

@Configuration
public class WebConfig {

    @Bean
    public RestTemplate restTemplate() {
        // 외부 API(알라딘 등) 호출이 무한정 요청 스레드를 점유하지 않도록 connect/read 타임아웃 설정.
        // (타임아웃 미설정 시 외부 의존성 지연이 Tomcat 스레드풀 고갈로 전파됨)
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        RestTemplate restTemplate = new RestTemplate(factory);

        // 알라딘 API는 Content-Type: text/javascript 로 JSON을 반환 → Jackson이 처리할 수 있도록 추가
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
}
