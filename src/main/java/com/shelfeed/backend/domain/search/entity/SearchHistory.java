package com.shelfeed.backend.domain.search.entity;

import com.shelfeed.backend.domain.member.entity.Member;
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(nullable = false, length = 200)
    private String keyword;

    @CreatedDate
    @Column(nullable = false)
    private LocalDateTime createdAt;

    public void touch() {
        this.createdAt = LocalDateTime.now();
    }

    // 정적 메서드
    public static SearchHistory create(Member member, String keyword) {
        SearchHistory searchHistory = new SearchHistory();
        searchHistory.member = member;
        searchHistory.keyword = keyword;
        return searchHistory;
    }
}
