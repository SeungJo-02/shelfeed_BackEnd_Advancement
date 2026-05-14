package com.shelfeed.backend.domain.genre.repository;

import com.shelfeed.backend.domain.genre.entity.Genre;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GenreRepository extends JpaRepository<Genre, Long> {

    @Override
    @Cacheable("genres")
    List<Genre> findAll();
}
