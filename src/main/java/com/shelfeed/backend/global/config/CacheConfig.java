package com.shelfeed.backend.global.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("genres");
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(100)// 넘어가면 FIFO
                .expireAfterWrite(1, TimeUnit.HOURS));// 만료시간 1시간
        return manager;
    }
}
