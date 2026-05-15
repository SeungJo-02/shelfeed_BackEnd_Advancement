package com.shelfeed.backend.domain.book.client;

import com.shelfeed.backend.domain.book.client.dto.AladinSearchResponse;

public interface AladinClient {
    AladinSearchResponse search(String query, int start, int maxResults);
    AladinSearchResponse lookupByIsbn(String isbn13);
}
