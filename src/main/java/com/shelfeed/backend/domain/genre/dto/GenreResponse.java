package com.shelfeed.backend.domain.genre.dto;

import com.shelfeed.backend.domain.genre.entity.Genre;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GenreResponse {
    private Long genreId;
    private String name;

    public static GenreResponse of(Genre genre){
        return GenreResponse.builder()
                .genreId(genre.getGenreId())
                .name(genre.getGenreName())
                .build();
    }
}
