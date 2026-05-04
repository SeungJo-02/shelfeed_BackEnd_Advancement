package com.shelfeed.backend.domain.genre.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "genres")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Genre {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    private Long genreId;

    @Column(length = 50, nullable = false)
    private String genreName;

    @Column(length = 100)
    private String categoryPattern;
}
