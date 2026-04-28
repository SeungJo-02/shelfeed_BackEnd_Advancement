package com.shelfeed.backend.domain.genre.controller;

import com.shelfeed.backend.domain.genre.dto.GenreListResponse;
import com.shelfeed.backend.domain.genre.dto.GenreResponse;
import com.shelfeed.backend.domain.genre.repository.GenreRepository;
import com.shelfeed.backend.global.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/genres")
@RequiredArgsConstructor
public class GenreController {

    private final GenreRepository genreRepository;

    @GetMapping
    public ApiResponse<GenreListResponse> getGenres(){
        return ApiResponse.success(200, GenreListResponse.of(genreRepository.findAll()));
    }

}
