package com.shelfeed.backend.domain.review;

import com.shelfeed.backend.domain.block.service.BlockService;
import com.shelfeed.backend.domain.book.entity.Book;
import com.shelfeed.backend.domain.book.repository.BookRepository;
import com.shelfeed.backend.domain.comment.dto.request.CommentCreateRequest;
import com.shelfeed.backend.domain.comment.entity.Comment;
import com.shelfeed.backend.domain.comment.repository.CommentLikeRepository;
import com.shelfeed.backend.domain.comment.repository.CommentRepository;
import com.shelfeed.backend.domain.comment.service.CommentService;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.repository.MemberRepository;
import com.shelfeed.backend.domain.notification.repository.NotificationRepository;
import com.shelfeed.backend.domain.notification.service.NotificationService;
import com.shelfeed.backend.domain.review.entity.Review;
import com.shelfeed.backend.domain.review.enums.ReviewStatus;
import com.shelfeed.backend.domain.review.enums.ReviewVisibility;
import com.shelfeed.backend.domain.review.repository.ReviewLikeRepository;
import com.shelfeed.backend.domain.review.repository.ReviewRepository;
import com.shelfeed.backend.domain.review.service.ReviewLikeService;
import com.shelfeed.backend.global.common.helper.MemberLoader;
import com.shelfeed.backend.global.common.util.CursorUtils;
import com.shelfeed.backend.global.config.JpaConfig;
import com.shelfeed.backend.support.TestContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 감상 좋아요 / 댓글 작성·좋아요가 실제 DB(Testcontainers MySQL)에서 예외 없이 저장되는지 검증하는 회귀 가드.
 *
 * <p>배경: 카운터 UPDATE(increaseLikeCount/increaseCommentCount)는 {@code @Modifying(clearAutomatically=true)}라
 * 실행 직후 영속성 컨텍스트를 clear한다. 이론상 그 직후 알림 서비스가 lazy 프록시({@code review.getMember()} /
 * {@code comment.getMember()})의 비-id 필드를 읽으면 detach된 프록시라 LazyInitializationException이 날 수 있다.
 *
 * <p>그러나 현재 코드는 <b>안전</b>하다: {@code Member}의 @Id는 {@code memberId}이고 {@code memberUserId}는
 * 별도 컬럼이라, clear 이전에 실행되는 self-like/차단 검사의 {@code owner.getMemberUserId()} 호출이
 * (id가 아닌 필드 접근이므로) 프록시를 그 자리에서 이미 초기화한다. 따라서 clear 이후
 * {@code getNotificationPreferences()}를 읽어도 이미 로드된 값이라 예외가 없다. 이 테스트는 그 불변식을
 * 실제 DB로 고정해, 향후 리팩터링으로 clear 이전 초기화가 사라지면(=진짜 회귀) 곧바로 잡히게 한다.
 * (Mockito 단위 테스트는 clear/detach를 재현하지 못해 이 계층을 커버할 수 없다.)
 *
 * <p>{@code @DataJpaTest} 슬라이스 + 서비스 import: 풀 컨텍스트(외부 시크릿 의존 빈)를 피한다.
 * {@code @Transactional(NOT_SUPPORTED)}: 테스트 트랜잭션을 꺼야 서비스의 @Transactional이 독립 실행되어
 * flush/clear 타이밍이 운영과 같아진다(켜두면 회귀 재현 불가). 각 테스트는 @BeforeEach에서 명시적으로 격리한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ReviewLikeService.class, CommentService.class, BlockService.class,
        NotificationService.class, MemberLoader.class, JpaConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("감상/댓글 좋아요 알림 영속성 통합 테스트 (#28 LazyInit 회귀)")
class ReviewLikeCommentNotificationIntegrationTest extends TestContainerSupport {

    @Autowired ReviewLikeService reviewLikeService;
    @Autowired CommentService commentService;
    @Autowired MemberRepository memberRepository;
    @Autowired BookRepository bookRepository;
    @Autowired ReviewRepository reviewRepository;
    @Autowired ReviewLikeRepository reviewLikeRepository;
    @Autowired CommentRepository commentRepository;
    @Autowired CommentLikeRepository commentLikeRepository;
    @Autowired NotificationRepository notificationRepository;

    // 알림 목록 조회(커서)에서만 쓰이는 의존성. 좋아요/댓글 생성 경로에선 호출되지 않으므로 mock으로 충분
    // (ObjectMapper 의존을 끊어 JPA 슬라이스 컨텍스트 로딩을 단순화).
    @MockBean CursorUtils cursorUtils;

    private Member author;   // 감상 작성자
    private Member actor;    // 좋아요/댓글 행위자
    private Review review;   // author의 공개 감상

    @BeforeEach
    void setUp() {
        // NOT_SUPPORTED라 각 테스트가 독립 커밋된다. 매 테스트 시작 시 관련 테이블을 비워 격리.
        notificationRepository.deleteAllInBatch();
        reviewLikeRepository.deleteAllInBatch();
        commentLikeRepository.deleteAllInBatch();
        commentRepository.deleteAllInBatch();
        reviewRepository.deleteAllInBatch();
        bookRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();

        author = memberRepository.save(
                Member.createLocal(1L, "author@test.com", "encoded", "작성자", "bio"));
        actor = memberRepository.save(
                Member.createLocal(2L, "actor@test.com", "encoded", "행위자", "bio"));
        Book book = bookRepository.save(
                Book.create("9791234567890", "테스트 책", "작가", "출판사",
                        null, null, null, null, null, null, null));
        review = reviewRepository.save(
                Review.create(author, book, null, (byte) 5, "감상 내용", null,
                        false, null, ReviewVisibility.PUBLIC, ReviewStatus.PUBLISHED));
    }

    @Test
    @DisplayName("감상 좋아요가 예외 없이 저장되고 좋아요 수 증가 + 작성자 알림이 생성된다")
    void 감상_좋아요_정상저장() {
        assertThatCode(() -> reviewLikeService.like(review.getReviewId(), actor.getMemberUserId()))
                .doesNotThrowAnyException();

        // 좋아요 행 + 카운트는 DB에서 다시 읽어 검증 (롤백됐다면 0)
        assertThat(reviewLikeRepository.existsByReview_ReviewIdAndMember_MemberUserId(
                review.getReviewId(), actor.getMemberUserId())).isTrue();
        Review reloaded = reviewRepository.findByReviewIdAndIsDeletedFalse(review.getReviewId()).orElseThrow();
        assertThat(reloaded.getLikeCount()).isEqualTo(1);
        // 작성자에게 좋아요 알림 1건
        assertThat(notificationRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("최상위 댓글 작성이 예외 없이 저장되고 댓글 수 증가 + 작성자 알림이 생성된다")
    void 최상위_댓글_정상저장() {
        CommentCreateRequest request = commentRequest("좋은 감상이네요", null);

        assertThatCode(() -> commentService.createComment(
                review.getReviewId(), actor.getMemberUserId(), request))
                .doesNotThrowAnyException();

        assertThat(commentRepository.count()).isEqualTo(1);
        Review reloaded = reviewRepository.findByReviewIdAndIsDeletedFalse(review.getReviewId()).orElseThrow();
        assertThat(reloaded.getCommentCount()).isEqualTo(1);
        // 감상 작성자에게 댓글 알림 1건
        assertThat(notificationRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("댓글 좋아요가 예외 없이 저장되고 좋아요 수 증가 + 댓글작성자 알림이 생성된다")
    void 댓글_좋아요_정상저장() {
        // actor가 댓글 작성 → author가 그 댓글에 좋아요
        commentService.createComment(review.getReviewId(), actor.getMemberUserId(),
                commentRequest("댓글입니다", null));
        Comment comment = commentRepository.findAll().get(0);
        // 위 댓글 작성으로 생긴 알림은 제거하고, 댓글 좋아요 알림만 격리 검증
        notificationRepository.deleteAllInBatch();

        assertThatCode(() -> commentService.likeComment(
                review.getReviewId(), comment.getCommentId(), author.getMemberUserId()))
                .doesNotThrowAnyException();

        assertThat(commentLikeRepository.existsByComment_CommentIdAndMember_MemberUserId(
                comment.getCommentId(), author.getMemberUserId())).isTrue();
        Comment reloaded = commentRepository.findByCommentIdAndIsDeletedFalse(comment.getCommentId()).orElseThrow();
        assertThat(reloaded.getLikeCount()).isEqualTo(1);
        // 댓글 작성자(actor)에게 댓글 좋아요 알림 1건
        assertThat(notificationRepository.count()).isEqualTo(1);
    }

    private CommentCreateRequest commentRequest(String content, Long parentCommentId) {
        CommentCreateRequest r = new CommentCreateRequest();
        ReflectionTestUtils.setField(r, "content", content);
        ReflectionTestUtils.setField(r, "parentCommentId", parentCommentId);
        return r;
    }
}
