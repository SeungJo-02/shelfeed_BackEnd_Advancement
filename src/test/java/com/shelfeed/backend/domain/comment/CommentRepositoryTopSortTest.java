package com.shelfeed.backend.domain.comment;

import com.shelfeed.backend.domain.book.entity.Book;
import com.shelfeed.backend.domain.book.repository.BookRepository;
import com.shelfeed.backend.domain.comment.entity.Comment;
import com.shelfeed.backend.domain.comment.repository.CommentRepository;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.repository.MemberRepository;
import com.shelfeed.backend.domain.review.entity.Review;
import com.shelfeed.backend.domain.review.enums.ReviewStatus;
import com.shelfeed.backend.domain.review.enums.ReviewVisibility;
import com.shelfeed.backend.domain.review.repository.ReviewRepository;
import com.shelfeed.backend.global.config.JpaConfig;
import com.shelfeed.backend.support.TestContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인기순 댓글 조회({@code findTopParentComments})의 정렬 규칙을 실제 MySQL로 검증한다.
 *
 * <p>실 DB를 쓰는 이유는 두 가지다. ORDER BY 안의 상관 서브쿼리로 대댓글 수를 세는데 이건
 * 인메모리 H2와 MySQL의 동작이 갈릴 수 있고, {@code Pageable}이 그 쿼리에 LIMIT을 제대로
 * 붙이는지도 실제 방언에서만 확인된다.
 *
 * <p>고정하려는 규칙은 넷이다 — 좋아요가 1순위, 동률이면 대댓글 수, 그것도 같으면 최신,
 * 그리고 삭제된 댓글은 아예 빠진다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@DisplayName("인기순 댓글 정렬 통합 테스트")
class CommentRepositoryTopSortTest extends TestContainerSupport {

    @Autowired CommentRepository commentRepository;
    @Autowired ReviewRepository reviewRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired BookRepository bookRepository;

    private Review review;
    private Member author;

    @BeforeEach
    void setUp() {
        author = memberRepository.save(
                Member.createLocal(7001L, "top-sort@test.com", "encoded", "댓글러", null));
        Book book = bookRepository.save(Book.create(
                "9780000009999", "정렬 테스트용 책", "저자", "출판사",
                null, null, 100, null, "1", "국내도서>소설", "소설"));
        review = reviewRepository.save(Review.create(
                author, book, null, (byte) 5, "감상 본문", null, false, 10,
                ReviewVisibility.PUBLIC, ReviewStatus.PUBLISHED));
    }

    /** 원 댓글 하나를 만들어 저장한다. 저장 순서가 곧 commentId 순서라 "최신" 기준이 된다. */
    private Comment comment(String content) {
        return commentRepository.save(Comment.createOriginComment(review, author.getMemberId(), content));
    }

    /** 좋아요 수를 실제 증가 쿼리로 올린다. 엔티티에 세터가 없어 운영과 같은 경로를 쓴다. */
    private void like(Comment comment, int times) {
        for (int i = 0; i < times; i++) {
            commentRepository.increaseLikeCount(comment.getCommentId());
        }
    }

    private void reply(Comment parent, int count) {
        for (int i = 0; i < count; i++) {
            commentRepository.save(
                    Comment.createReply(review, author.getMemberId(), parent, "대댓글 " + i));
        }
    }

    private List<String> topContents(int limit) {
        return commentRepository.findTopParentComments(review, PageRequest.of(0, limit))
                .stream().map(Comment::getContent).toList();
    }

    @Test
    @DisplayName("좋아요가 많은 댓글이 먼저 온다")
    void 좋아요_우선() {
        Comment few = comment("좋아요 1개");
        Comment many = comment("좋아요 5개");
        like(few, 1);
        like(many, 5);

        assertThat(topContents(10)).containsExactly("좋아요 5개", "좋아요 1개");
    }

    @Test
    @DisplayName("좋아요가 같으면 대댓글이 많은 댓글이 먼저 온다")
    void 좋아요_동률이면_대댓글수() {
        Comment quiet = comment("대댓글 없음");
        Comment busy = comment("대댓글 3개");
        like(quiet, 2);
        like(busy, 2);
        reply(busy, 3);

        assertThat(topContents(10)).containsExactly("대댓글 3개", "대댓글 없음");
    }

    @Test
    @DisplayName("좋아요·대댓글이 모두 같으면 최신 댓글이 먼저 온다")
    void 모두_동률이면_최신() {
        comment("먼저 쓴 댓글");
        comment("나중에 쓴 댓글");

        assertThat(topContents(10)).containsExactly("나중에 쓴 댓글", "먼저 쓴 댓글");
    }

    @Test
    @DisplayName("삭제된 댓글은 좋아요가 많아도 인기순에서 빠진다")
    void 삭제된_댓글_제외() {
        Comment deleted = comment("삭제된 인기 댓글");
        Comment alive = comment("살아있는 댓글");
        like(deleted, 99);
        like(alive, 1);

        // 소프트 삭제는 엔티티 메서드로 수행한다. 좋아요가 압도적이어도 목록에 남으면 안 된다.
        Comment managed = commentRepository.findById(deleted.getCommentId()).orElseThrow();
        managed.softDelete();
        commentRepository.saveAndFlush(managed);

        assertThat(topContents(10)).containsExactly("살아있는 댓글");
    }

    @Test
    @DisplayName("삭제된 대댓글은 대댓글 수에 세지 않는다")
    void 삭제된_대댓글은_미집계() {
        Comment a = comment("대댓글 2개 살아있음");
        Comment b = comment("대댓글 3개지만 2개 삭제");
        reply(a, 2);
        reply(b, 3);

        List<Comment> bReplies = commentRepository.findByParentComment(b);
        bReplies.subList(0, 2).forEach(Comment::softDelete);
        commentRepository.saveAllAndFlush(bReplies);

        // b는 살아있는 대댓글이 1개뿐이라 2개인 a보다 뒤로 밀린다.
        assertThat(topContents(10))
                .containsExactly("대댓글 2개 살아있음", "대댓글 3개지만 2개 삭제");
    }

    @Test
    @DisplayName("limit으로 상위 N개만 잘라 온다")
    void 상위_N개만() {
        for (int i = 1; i <= 5; i++) {
            like(comment("댓글 " + i), i);
        }

        assertThat(topContents(3)).containsExactly("댓글 5", "댓글 4", "댓글 3");
    }
}
