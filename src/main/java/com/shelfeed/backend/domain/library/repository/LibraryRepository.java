package com.shelfeed.backend.domain.library.repository;

import com.shelfeed.backend.domain.library.entity.LibraryBook;
import com.shelfeed.backend.domain.library.enums.ReadingStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * member/book이 ID 참조로 바뀌면서 조인을 쓸 수 없다.
 * 도서 정보가 필요한 조회는 호출측(LibraryService)이 bookId를 모아 IN 쿼리로 조립한다.
 */
public interface LibraryRepository extends JpaRepository<LibraryBook,Long> {
    //중복 확인
    boolean existsByMemberIdAndBookId(Long memberId, Long bookId);

    // 서재 목록 공통 쿼리 (내 서재 / 타 유저 서재 공용)
    @Query("SELECT lb FROM LibraryBook lb WHERE lb.memberId = :memberId " +
            "AND (:status IS NULL OR lb.status = :status) " +//전체 조회 + 필터링 조회
            "AND (:cursor IS NULL OR lb.libraryBookId < :cursor) " + // 커서 페이지 네이션
            "ORDER BY lb.libraryBookId DESC")
    List<LibraryBook> findLibraryBooks(@Param("memberId") Long memberId, @Param("status") ReadingStatus status,
                                       @Param("cursor") Long cursor,
                                       Pageable pageable);

    //유저 본인의 서재인가 확인
    Optional<LibraryBook> findByLibraryBookIdAndMemberId(Long libraryBookId, Long memberId);
    Optional<LibraryBook> findByMemberIdAndBookId(Long memberId, Long bookId);

    // 서재에 담긴 도서 ID 목록 일괄 조회 (N+1 방지)
    @Query("SELECT lb.bookId FROM LibraryBook lb WHERE lb.memberId = :memberId AND lb.bookId IN :bookIds")
    Set<Long> findBookIdsByMemberAndBookIdIn(@Param("memberId") Long memberId, @Param("bookIds") List<Long> bookIds);

    // 지혜의 탑 — 완독 도서 목록 (완독일 최신순)
    @Query("SELECT lb FROM LibraryBook lb WHERE lb.memberId = :memberId " +
            "AND lb.status = 'FINISHED' ORDER BY lb.finishedAt DESC, lb.libraryBookId DESC")
    List<LibraryBook> findFinishedBooksForTower(@Param("memberId") Long memberId);

    // 추천용 — 읽은/읽는 중 도서 ID. 장르 집계는 book을 소유한 catalog 쪽(BookRepository)에서 이어서 한다.
    // (기존엔 lb.book 조인 한 방이었으나, 경계를 넘는 조인이라 2단계로 분리했다.)
    @Query("SELECT lb.bookId FROM LibraryBook lb WHERE lb.memberId = :memberId " +
            "AND lb.status IN ('READING', 'FINISHED')")
    List<Long> findReadBookIdsByMember(@Param("memberId") Long memberId);
}
