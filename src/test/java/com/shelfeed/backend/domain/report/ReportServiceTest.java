package com.shelfeed.backend.domain.report;

import com.shelfeed.backend.domain.book.entity.Book;
import com.shelfeed.backend.domain.comment.entity.Comment;
import com.shelfeed.backend.domain.comment.repository.CommentRepository;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.repository.MemberRepository;
import com.shelfeed.backend.domain.report.dto.request.ReportRequest;
import com.shelfeed.backend.domain.report.dto.response.ReportResponse;
import com.shelfeed.backend.domain.report.entity.Report;
import com.shelfeed.backend.domain.report.enums.ReportReason;
import com.shelfeed.backend.domain.report.enums.ReportTargetType;
import com.shelfeed.backend.domain.report.repository.ReportRepository;
import com.shelfeed.backend.domain.report.service.ReportService;
import com.shelfeed.backend.domain.review.entity.Review;
import com.shelfeed.backend.domain.review.enums.ReviewStatus;
import com.shelfeed.backend.domain.review.enums.ReviewVisibility;
import com.shelfeed.backend.domain.review.repository.ReviewRepository;
import com.shelfeed.backend.global.common.exception.BusinessException;
import com.shelfeed.backend.global.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReportService 단위 테스트")
class ReportServiceTest {

    @Mock MemberRepository memberRepository;
    @Mock ReviewRepository reviewRepository;
    @Mock CommentRepository commentRepository;
    @Mock ReportRepository reportRepository;

    @InjectMocks ReportService reportService;

    private Member reporter;
    private Member owner;
    private Book book;
    private Review review;

    @BeforeEach
    void setUp() {
        reporter = Member.createLocal(1L, "reporter@test.com", "encoded", "신고자", "bio");
        owner    = Member.createLocal(2L, "owner@test.com",   "encoded", "작성자", "bio");

        book = Book.create("9791234567890", "테스트 책", "작가", "출판사",
                null, null, null, null, null, null);

        review = Review.create(owner, book, null, (byte) 5, "내용", null,
                false, null, ReviewVisibility.PUBLIC, ReviewStatus.PUBLISHED);
        ReflectionTestUtils.setField(review, "reviewId", 10L);
    }

    private ReportRequest reviewRequest(Long targetId, ReportReason reason) {
        ReportRequest req = new ReportRequest();
        ReflectionTestUtils.setField(req, "targetType", ReportTargetType.REVIEW);
        ReflectionTestUtils.setField(req, "targetId", targetId);
        ReflectionTestUtils.setField(req, "reason", reason);
        return req;
    }

    private ReportRequest commentRequest(Long targetId, ReportReason reason) {
        ReportRequest req = new ReportRequest();
        ReflectionTestUtils.setField(req, "targetType", ReportTargetType.COMMENT);
        ReflectionTestUtils.setField(req, "targetId", targetId);
        ReflectionTestUtils.setField(req, "reason", reason);
        return req;
    }

    // ────────────────────────────────────────────────────────
    // 공통
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("공통")
    class Common {

        @Test
        @DisplayName("존재하지 않는 회원이면 MEMBER_NOT_FOUND 예외가 발생한다")
        void 회원_없음_예외() {
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> reportService.createReport(1L, reviewRequest(10L, ReportReason.SPAM)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        }
    }

    // ────────────────────────────────────────────────────────
    // 감상 신고
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("감상 신고")
    class ReviewReport {

        @Test
        @DisplayName("이미 신고한 감상이면 REPORT_ALREADY_EXISTS 예외가 발생한다")
        void 중복_감상_신고_예외() {
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(reporter));
            given(reportRepository.existsByMemberAndReviewId(reporter, 10L)).willReturn(true);

            assertThatThrownBy(() -> reportService.createReport(1L, reviewRequest(10L, ReportReason.SPAM)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.REPORT_ALREADY_EXISTS);
        }

        @Test
        @DisplayName("존재하지 않는 감상이면 REPORT_TARGET_NOT_FOUND 예외가 발생한다")
        void 감상_없음_예외() {
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(reporter));
            given(reportRepository.existsByMemberAndReviewId(reporter, 10L)).willReturn(false);
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(10L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> reportService.createReport(1L, reviewRequest(10L, ReportReason.SPAM)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.REPORT_TARGET_NOT_FOUND);
        }

        @Test
        @DisplayName("본인 감상을 신고하면 REPORT_SELF_NOT_ALLOWED 예외가 발생한다")
        void 본인_감상_신고_예외() {
            Review ownReview = Review.create(reporter, book, null, (byte) 4, "내용", null,
                    false, null, ReviewVisibility.PUBLIC, ReviewStatus.PUBLISHED);
            ReflectionTestUtils.setField(ownReview, "reviewId", 20L);

            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(reporter));
            given(reportRepository.existsByMemberAndReviewId(reporter, 20L)).willReturn(false);
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(20L)).willReturn(Optional.of(ownReview));

            assertThatThrownBy(() -> reportService.createReport(1L, reviewRequest(20L, ReportReason.SPAM)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.REPORT_SELF_NOT_ALLOWED);
        }

        @Test
        @DisplayName("감상 신고에 성공하면 reportId, targetType, targetId, reason을 반환한다")
        void 감상_신고_성공() {
            Report saved = Report.createReviewReport(reporter, 10L, ReportReason.SPAM, null);
            ReflectionTestUtils.setField(saved, "reportId", 100L);

            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(reporter));
            given(reportRepository.existsByMemberAndReviewId(reporter, 10L)).willReturn(false);
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(10L)).willReturn(Optional.of(review));
            given(reportRepository.save(any())).willReturn(saved);

            ReportResponse response = reportService.createReport(1L, reviewRequest(10L, ReportReason.SPAM));

            assertThat(response.getReportId()).isEqualTo(100L);
            assertThat(response.getTargetType()).isEqualTo(ReportTargetType.REVIEW);
            assertThat(response.getTargetId()).isEqualTo(10L);
            assertThat(response.getReason()).isEqualTo(ReportReason.SPAM);
        }
    }

    // ────────────────────────────────────────────────────────
    // 댓글 신고
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("댓글 신고")
    class CommentReport {

        @Test
        @DisplayName("이미 신고한 댓글이면 REPORT_ALREADY_EXISTS 예외가 발생한다")
        void 중복_댓글_신고_예외() {
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(reporter));
            given(reportRepository.existsByMemberAndCommentId(reporter, 50L)).willReturn(true);

            assertThatThrownBy(() -> reportService.createReport(1L, commentRequest(50L, ReportReason.INAPPROPRIATE)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.REPORT_ALREADY_EXISTS);
        }

        @Test
        @DisplayName("존재하지 않는 댓글이면 REPORT_TARGET_NOT_FOUND 예외가 발생한다")
        void 댓글_없음_예외() {
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(reporter));
            given(reportRepository.existsByMemberAndCommentId(reporter, 50L)).willReturn(false);
            given(commentRepository.findByCommentIdAndIsDeletedFalse(50L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> reportService.createReport(1L, commentRequest(50L, ReportReason.INAPPROPRIATE)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.REPORT_TARGET_NOT_FOUND);
        }

        @Test
        @DisplayName("본인 댓글을 신고하면 REPORT_SELF_NOT_ALLOWED 예외가 발생한다")
        void 본인_댓글_신고_예외() {
            Comment ownComment = mock(Comment.class);
            given(ownComment.getMember()).willReturn(reporter);

            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(reporter));
            given(reportRepository.existsByMemberAndCommentId(reporter, 50L)).willReturn(false);
            given(commentRepository.findByCommentIdAndIsDeletedFalse(50L)).willReturn(Optional.of(ownComment));

            assertThatThrownBy(() -> reportService.createReport(1L, commentRequest(50L, ReportReason.INAPPROPRIATE)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.REPORT_SELF_NOT_ALLOWED);
        }

        @Test
        @DisplayName("댓글 신고에 성공하면 reportId, targetType, targetId, reason을 반환한다")
        void 댓글_신고_성공() {
            Comment otherComment = mock(Comment.class);
            given(otherComment.getMember()).willReturn(owner);

            Report saved = Report.createCommentReport(reporter, 50L, ReportReason.INAPPROPRIATE, null);
            ReflectionTestUtils.setField(saved, "reportId", 200L);

            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(reporter));
            given(reportRepository.existsByMemberAndCommentId(reporter, 50L)).willReturn(false);
            given(commentRepository.findByCommentIdAndIsDeletedFalse(50L)).willReturn(Optional.of(otherComment));
            given(reportRepository.save(any())).willReturn(saved);

            ReportResponse response = reportService.createReport(1L, commentRequest(50L, ReportReason.INAPPROPRIATE));

            assertThat(response.getReportId()).isEqualTo(200L);
            assertThat(response.getTargetType()).isEqualTo(ReportTargetType.COMMENT);
            assertThat(response.getTargetId()).isEqualTo(50L);
            assertThat(response.getReason()).isEqualTo(ReportReason.INAPPROPRIATE);
        }
    }
}
