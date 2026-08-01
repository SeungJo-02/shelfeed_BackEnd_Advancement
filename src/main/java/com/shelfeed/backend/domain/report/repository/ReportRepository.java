package com.shelfeed.backend.domain.report.repository;

import com.shelfeed.backend.domain.report.entity.Report;
import com.shelfeed.backend.domain.report.enums.ReportStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<Report, Long> {

    boolean existsByMemberIdAndReviewId(Long memberId, Long reviewId);

    boolean existsByMemberIdAndCommentId(Long memberId, Long commentId);

    long countByStatus(ReportStatus status);
}
