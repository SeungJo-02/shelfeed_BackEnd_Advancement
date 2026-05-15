package com.shelfeed.backend.domain.book.client;

import com.shelfeed.backend.domain.book.client.dto.AladinSearchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@Component
@Profile("!mock-aladin")
@RequiredArgsConstructor
public class AladinApiClient implements AladinClient {

    private final RestTemplate restTemplate;

    @Value("${aladin.api.ttbkey}")
    private String ttbKey;
    @Value("${aladin.api.version}")
    private String version;

    private static final String SEARCH_URL = "https://www.aladin.co.kr/ttb/api/ItemSearch.aspx";
    private static final String LOOKUP_URL  = "https://www.aladin.co.kr/ttb/api/ItemLookUp.aspx";

    //도서검색
    public AladinSearchResponse search(String query, int start, int maxResults){
        URI uri = UriComponentsBuilder.fromHttpUrl(SEARCH_URL) //uri 변경
                .queryParam("ttbkey", ttbKey) //인증키
                .queryParam("Query", query) //검색어
                .queryParam("QueryType", "Keyword") //키워드
                .queryParam("MaxResults", maxResults) // 최대 검색 결과 수
                .queryParam("start", start)// 시작페이지
                .queryParam("SearchTarget", "Book")// 단행본 한정
                .queryParam("output", "js")// JSON요청
                .queryParam("Version", version) //사용할 외부 API 버전
                .encode() // 한 번만 인코딩 (이중 인코딩 방지)
                .build()
                .toUri();
        return restTemplate.getForObject(uri, AladinSearchResponse.class);
    }
    // ISBN으로 도서 조회
    public AladinSearchResponse lookupByIsbn(String isbn13) {
        URI uri = UriComponentsBuilder.fromHttpUrl(LOOKUP_URL) //uri 변경
                .queryParam("ttbkey", ttbKey) //인증키
                .queryParam("itemIdType", "ISBN13")// 조회할 식별자 종류
                .queryParam("ItemId", isbn13)//실제 식별자 값
                .queryParam("output", "js")// JSON요청
                .queryParam("Version", version) //사용할 외부 API 버전
                .queryParam("OptResult", "packing")// 부가정보
                .encode() // 한 번만 인코딩 (이중 인코딩 방지)
                .build()
                .toUri();
        return restTemplate.getForObject(uri, AladinSearchResponse.class);
    }



}
