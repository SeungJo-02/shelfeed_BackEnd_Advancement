package com.shelfeed.backend.domain.genre.dto;

import com.shelfeed.backend.domain.genre.entity.Genre;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class GenreListResponse {

    private List<GenreResponse> genres;

    public static GenreListResponse of(List<Genre> genres){
        return GenreListResponse.builder()
                .genres(genres.stream().map(GenreResponse::of).toList())
                .build();
    }
}
