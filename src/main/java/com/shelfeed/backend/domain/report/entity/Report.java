package com.shelfeed.backend.domain.report.entity;

import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.report.enums.ReportReason;
import com.shelfeed.backend.domain.report.enums.ReportStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "reports",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"member_id", "review_id"}),
                @UniqueConstraint(columnNames = {"member_id", "comment_id"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "report_id")
    private Long reportId;

    // 신고한 사람 — 서비스 경계(review → user)를 넘으므로 ID로 참조한다.
    // members.member_id(PK)를 담는다.
    @Column(name = "member_id", nullable = false)
    private Long memberId;

    // 신고 대상 — 감상 신고이면 reviewId만, 댓글 신고이면 commentId만 채워짐
    @Column(name = "review_id")
    private Long reviewId;

    @Column(name = "comment_id")
    private Long commentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportReason reason;

    @Column(length = 200)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportStatus status = ReportStatus.PENDING;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime resolvedAt;

    // 정적 메서드 감상 신고
    public static Report createReviewReport(Long memberId, Long reviewId,
                                            ReportReason reason, String detail) {
        Report report = new Report();
        report.memberId = memberId;
        report.reviewId = reviewId;
        report.reason = reason;
        report.detail = detail;
        return report;
    }

    // 정적 메서드 댓글 신고
    public static Report createCommentReport(Long memberId, Long commentId,
                                             ReportReason reason, String detail) {
        Report report = new Report();
        report.memberId = memberId;
        report.commentId = commentId;
        report.reason = reason;
        report.detail = detail;
        return report;
    }

    //비즈니스 메서드
    // 신고 처리
    public void resolve(){
        this.status = ReportStatus.RESOLVED;
        this.resolvedAt = LocalDateTime.now();
    }
    // 신고 반려
    public void reject(){
        this.status = ReportStatus.REJECTED;
        this.resolvedAt = LocalDateTime.now();
    }
}
