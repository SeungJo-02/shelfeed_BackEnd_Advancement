package com.shelfeed.backend.domain.search.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "search_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class SearchHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "search_history_id")
    private Long searchHistoryId;

    // 서비스 경계(catalog → user)를 넘으므로 객체가 아닌 ID로 참조한다.
    // members.member_id(PK)를 담는다 — 공개 식별자인 memberUserId가 아니다.
    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(nullable = false, length = 200)
    private String keyword;

    @CreatedDate
    @Column(nullable = false)
    private LocalDateTime createdAt;

    public void touch() {
        this.createdAt = LocalDateTime.now();
    }

    // 정적 메서드
    public static SearchHistory create(Long memberId, String keyword) {
        SearchHistory searchHistory = new SearchHistory();
        searchHistory.memberId = memberId;
        searchHistory.keyword = keyword;
        return searchHistory;
    }
}
