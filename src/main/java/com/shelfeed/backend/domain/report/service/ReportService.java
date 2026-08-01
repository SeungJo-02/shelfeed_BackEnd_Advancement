package com.shelfeed.backend.domain.report.service;

import com.shelfeed.backend.domain.comment.entity.Comment;
import com.shelfeed.backend.domain.comment.repository.CommentRepository;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.repository.MemberRepository;
import com.shelfeed.backend.domain.report.dto.request.ReportRequest;
import com.shelfeed.backend.domain.report.dto.response.ReportResponse;
import com.shelfeed.backend.domain.report.entity.Report;
import com.shelfeed.backend.domain.report.enums.ReportTargetType;
import com.shelfeed.backend.domain.report.repository.ReportRepository;
import com.shelfeed.backend.domain.review.entity.Review;
import com.shelfeed.backend.domain.review.repository.ReviewRepository;
import com.shelfeed.backend.global.common.exception.BusinessException;
import com.shelfeed.backend.global.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService {

    private final MemberRepository memberRepository;
    private final ReviewRepository reviewRepository;
    private final CommentRepository commentRepository;
    private final ReportRepository reportRepository;

@Transactional
    public ReportResponse createReport(Long memberUserId, ReportRequest request) {
        Member reporter = memberRepository.findByMemberUserId(memberUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        ReportTargetType targetType = request.getTargetType();
        Long targetId = request.getTargetId();

        Report report = switch (targetType) {
            case REVIEW -> createReviewReport(reporter.getMemberId(), memberUserId, request);
            case COMMENT -> createCommentReport(reporter.getMemberId(), memberUserId, request);
        };

        Report saved = reportRepository.save(report);
        return ReportResponse.of(saved, targetType, targetId);
    }

    private Report createReviewReport(Long reporterMemberId, Long memberUserId, ReportRequest request) {
        Long reviewId = request.getTargetId();

        if (reportRepository.existsByMemberIdAndReviewId(reporterMemberId, reviewId)) {
            throw new BusinessException(ErrorCode.REPORT_ALREADY_EXISTS);
        }

        Review review = reviewRepository.findByReviewIdAndIsDeletedFalse(reviewId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_TARGET_NOT_FOUND));

        if (review.getMember().getMemberUserId().equals(memberUserId)) {
            throw new BusinessException(ErrorCode.REPORT_SELF_NOT_ALLOWED);
        }

        return Report.createReviewReport(reporterMemberId, reviewId, request.getReason(), request.getDescription());
    }

    private Report createCommentReport(Long reporterMemberId, Long memberUserId, ReportRequest request) {
        Long commentId = request.getTargetId();

        if (reportRepository.existsByMemberIdAndCommentId(reporterMemberId, commentId)) {
            throw new BusinessException(ErrorCode.REPORT_ALREADY_EXISTS);
        }

        Comment comment = commentRepository.findByCommentIdAndIsDeletedFalse(commentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_TARGET_NOT_FOUND));

        // 댓글이 PK를 들고 있으므로 신고자 PK와 직접 비교한다(추가 조회 불필요).
        if (comment.getMemberId().equals(reporterMemberId)) {
            throw new BusinessException(ErrorCode.REPORT_SELF_NOT_ALLOWED);
        }

        return Report.createCommentReport(reporterMemberId, commentId, request.getReason(), request.getDescription());
    }
}

