package com.shelfeed.backend.domain.search.repository;

import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.search.entity.SearchHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SearchHistoryRepository extends JpaRepository<SearchHistory, Long> {
    Optional<SearchHistory> findByMemberAndKeyword(Member member, String keyword);
}
