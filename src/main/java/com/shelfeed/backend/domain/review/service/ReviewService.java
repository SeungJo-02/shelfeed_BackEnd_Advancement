package com.shelfeed.backend.domain.review.service;

import com.shelfeed.backend.domain.book.entity.Book;
import com.shelfeed.backend.domain.book.repository.BookRepository;
import com.shelfeed.backend.domain.feed.service.FeedService;
import com.shelfeed.backend.domain.library.entity.LibraryBook;
import com.shelfeed.backend.domain.library.repository.LibraryRepository;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.repository.MemberRepository;
import com.shelfeed.backend.global.common.helper.MemberLoader;
import com.shelfeed.backend.domain.review.dto.request.ReviewCreateRequest;
import com.shelfeed.backend.domain.review.dto.request.ReviewUpdateRequest;
import com.shelfeed.backend.domain.review.dto.response.*;
import com.shelfeed.backend.domain.review.entity.Review;
import com.shelfeed.backend.domain.review.entity.ReviewTag;
import com.shelfeed.backend.domain.review.enums.ReviewStatus;
import com.shelfeed.backend.domain.review.enums.ReviewVisibility;
import com.shelfeed.backend.domain.block.service.BlockService;
import com.shelfeed.backend.domain.notification.service.NotificationService;
import com.shelfeed.backend.domain.review.repository.ReviewLikeRepository;
import com.shelfeed.backend.domain.review.repository.ReviewRepository;
import com.shelfeed.backend.domain.review.repository.ReviewTagRepository;
import com.shelfeed.backend.domain.review.repository.TagRepository;
import com.shelfeed.backend.domain.tag.entity.Tag;
import com.shelfeed.backend.global.common.exception.BusinessException;
import com.shelfeed.backend.global.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewService {
    private final MemberRepository memberRepository;
    private final MemberLoader memberLoader;
    private final ReviewRepository reviewRepository;
    private final LibraryRepository libraryRepository;
    private final BookRepository bookRepository;
    private final TagRepository tagRepository;
    private final ReviewTagRepository reviewTagRepository;
    private final ReviewLikeRepository reviewLikeRepository;
    private final BlockService blockService;
    private final FeedService feedService;
    private final NotificationService notificationService;

    // ── 1 감상 작성
    @Transactional
    public ReviewCreateResponse createReview(Long memberUserId, ReviewCreateRequest request) {
        //글이랑 인용구 둘 중 하난 필요
        if (request.getContent() == null && request.getQuote() == null) {
            throw new BusinessException(ErrorCode.CONTENT_OR_QUOTE_REQUIRED);
        }
        Member member = memberLoader.getOrThrow(memberUserId);
        Book book = getBook(request.getBookId());
        //동일한 도서를 중복으로 감상 눌렀을 떄 에러
        if (reviewRepository.existsByMemberAndBook_BookIdAndIsDeletedFalse(member, request.getBookId())){
            throw new BusinessException(ErrorCode.DUPLICATE_REVIEW);
        }
        // 서재 도서 소유자 검증 (IDOR 보안 취약점 해결)
        LibraryBook libraryBook = null;
        if (request.getLibraryBookId() != null) {
            libraryBook = libraryRepository
                    .findByLibraryBookIdAndMember(request.getLibraryBookId(), member)
                    .orElseThrow(() -> new BusinessException(ErrorCode.LIBRARY_BOOK_NOT_FOUND));
        }
        //감상 저장
        Review review = Review.create(member, book, libraryBook, request.getRating(), request.getContent(), request.getQuote(),
                request.isSpoiler(), request.getReadPages(), request.getReviewVisibility(), request.getReviewStatus());
        reviewRepository.save(review);

        //테그 처리(List로 나옴)
        List<String> tagNames = saveTags(review, request.getTags());
        //요청상태가 계시된 상태라면 리뷰 카운트 1 up + PUBLIC이면 팔로워 피드 생성
        if (request.getReviewStatus() == ReviewStatus.PUBLISHED) {
            memberRepository.increaseReviewCount(memberUserId);
            if (request.getReviewVisibility() == ReviewVisibility.PUBLIC) {
                feedService.publishToFollowers(member, review);
                notificationService.notifyFollowersOfNewReview(member, review);
            }
        }
        return ReviewCreateResponse.of(review, tagNames);
    }

    //2. 감상 상세 조회
    public ReviewDetailResponse getReview(Long reviewId, Long memberUserId){
        Review review = getReviewOrThrow(reviewId);//삭제 안된 리뷰 여부(소프트 델리트)
        boolean isMine = memberUserId != null && review.getMember().getMemberUserId().equals(memberUserId);//본인확인
        if (!isMine && review.getReviewVisibility() == ReviewVisibility.PRIVATE){//비공개 거나 게시물 주인이 아니면
            throw new BusinessException(ErrorCode.PRIVATE_REVIEW);//비공개 감상
        }
        if (!isMine && memberUserId != null) {
            Member requester = memberLoader.getOrThrow(memberUserId);
            Member owner = review.getMember();
            if (blockService.isBlockedBetween(owner, requester)) {
                throw new BusinessException(ErrorCode.BLOCKED_USER);
            }
        }
        List<String> tags = getTagNames(review);
        boolean isLiked = memberUserId != null && reviewLikeRepository.existsByReview_ReviewIdAndMember_MemberUserId(reviewId, memberUserId);
        return ReviewDetailResponse.of(review, tags, isMine, isLiked);
    }

    //3. 감상 수정
    @Transactional
    public ReviewUpdateResponse updateReview(Long reviewId, Long memberUserId, ReviewUpdateRequest request){
        //content, quote 둘 다 필요
        if (request.getContent() == null && request.getQuote() == null){
            throw new BusinessException(ErrorCode.CONTENT_OR_QUOTE_REQUIRED);
        }

        Review review = getReviewOrThrow(reviewId);//삭제 안된 리뷰 여부(소프트 델리트)
        validateReviewOwner(review, memberUserId);

        ReviewStatus prevStatus = review.getReviewStatus();
        ReviewStatus nextStatus = request.getReviewStatus();
        ReviewVisibility prevVis = review.getReviewVisibility();
        ReviewVisibility nextVis = request.getReviewVisibility();
        if (prevStatus == ReviewStatus.DRAFT && nextStatus == ReviewStatus.PUBLISHED) {
            memberRepository.increaseReviewCount(review.getMember().getMemberUserId());
            if (nextVis == ReviewVisibility.PUBLIC) {
                feedService.publishToFollowers(review.getMember(), review);
                notificationService.notifyFollowersOfNewReview(review.getMember(), review);
            }
        } else if (prevStatus == ReviewStatus.PUBLISHED && nextStatus == ReviewStatus.DRAFT) {
            memberRepository.decreaseReviewCount(review.getMember().getMemberUserId());
            feedService.removeByReview(review);
        } else if (prevStatus == ReviewStatus.PUBLISHED && nextStatus == ReviewStatus.PUBLISHED) {
            boolean wasPublic = prevVis == ReviewVisibility.PUBLIC;
            boolean willBePublic = nextVis == ReviewVisibility.PUBLIC;
            if (wasPublic && !willBePublic) {
                feedService.removeByReview(review);
            } else if (!wasPublic && willBePublic) {
                feedService.publishToFollowers(review.getMember(), review);
            }
        }
        //업데이트
        review.update(
                request.getRating(),request.getContent(), request.getQuote(), request.getReadPages(), request.isSpoiler(),
                request.getReviewVisibility(), request.getReviewStatus()
        );
        //테그 교체
        reviewTagRepository.deleteByReview(review);
        List<String> tagNames = saveTags(review,request.getTags());

        return ReviewUpdateResponse.of(review,tagNames);
    }
    //4. 감상 삭제
    @Transactional
    public void deleteReview(Long reviewId, Long memberUserId) {
        Review review = getReviewOrThrow(reviewId);//삭제 안된 리뷰 여부(소프트 델리트)
        validateReviewOwner(review, memberUserId);
        review.softDelete();
        //PUBLISHED 였으면 리뷰 카운트 감소
        if (review.getReviewStatus() == ReviewStatus.PUBLISHED){
            memberRepository.decreaseReviewCount(review.getMember().getMemberUserId());
        }
        feedService.removeByReview(review);
    }
    //5. 내 감상 목록
    public List<ReviewSummaryResponse> getMyReviews(Long memberUserId, ReviewStatus status, Long cursor, int limit){
        Member member = memberLoader.getOrThrow(memberUserId);
        List<Review> reviews = reviewRepository.findMyReviews(member, status, cursor, PageRequest.of(0, limit + 1));
        boolean hasNext = reviews.size() > limit;
        if (hasNext) reviews = reviews.subList(0, limit);

        List<Long> reviewIds = reviews.stream().map(Review::getReviewId).toList();
        Set<Long> likedIds = reviews.isEmpty() ? Set.of() : reviewLikeRepository.findLikedReviewIds(reviewIds, memberUserId);
        Map<Long, List<String>> tagMap = getTagNamesByReviews(reviews);
        return reviews.stream()
                .map(review -> ReviewSummaryResponse.of(review,
                        tagMap.getOrDefault(review.getReviewId(), Collections.emptyList()),
                        likedIds.contains(review.getReviewId())))
                .toList();
    }
    //6. 타 유저 감상 목록
    public List<ReviewSummaryResponse> getUserReviews(Long userId, Long requestingUserId, Long cursor, int limit) {
        Member member = memberLoader.getOrThrow(userId);
        if (requestingUserId != null && !requestingUserId.equals(userId)) {
            Member requester = memberLoader.getOrThrow(requestingUserId);
            if (blockService.isBlockedBetween(member, requester)) {
                throw new BusinessException(ErrorCode.BLOCKED_USER);
            }
        }
        List<Review> reviews = reviewRepository.findUserReviews(member, cursor, PageRequest.of(0, limit + 1));
        boolean hasNext = reviews.size() > limit;// 다음 페이지 확인
        if (hasNext) reviews = reviews.subList(0, limit);// 한 개 빼기

        List<Long> reviewIds = reviews.stream().map(Review::getReviewId).toList();
        Set<Long> likedIds = (requestingUserId != null && !reviews.isEmpty())
                ? reviewLikeRepository.findLikedReviewIds(reviewIds, requestingUserId) : Set.of();
        Map<Long, List<String>> tagMap = getTagNamesByReviews(reviews);
        return reviews.stream()
                .map(review -> ReviewSummaryResponse.of(review,
                        tagMap.getOrDefault(review.getReviewId(), Collections.emptyList()),
                        likedIds.contains(review.getReviewId())))
                .toList();
    }

    //헬퍼 메소드

    private Book getBook(Long bookId) {
        return bookRepository.findById(bookId).orElseThrow(()-> new BusinessException(ErrorCode.BOOK_NOT_FOUND));
    }
    //태그 저장
    private List<String> saveTags(Review review, List<String> tagNames) {
        if (tagNames == null || tagNames.isEmpty()) return List.of();
        List<String> uniqueTagNames = tagNames.stream().distinct().toList();


        //기존 태그 일괄 조회 (IN절 — 쿼리 1번)
        Map<String, Tag> existingTags = tagRepository.findByTagNameIn(uniqueTagNames).stream()
                .collect(Collectors.toMap(Tag::getTagName, t -> t));

        // 없는 태그만 일괄 저장 (쿼리 1번)
        List<Tag> newTags = uniqueTagNames.stream()
                .filter(name -> !existingTags.containsKey(name))
                .map(Tag::create)
                .toList();
        if (!newTags.isEmpty()) tagRepository.saveAll(newTags);

        // 전체 태그 맵 구성
        newTags.forEach(t -> existingTags.put(t.getTagName(), t));

        // ReviewTag 일괄 저장 (쿼리 1번)
        List<ReviewTag> reviewTags = uniqueTagNames.stream()
                .map(name -> ReviewTag.create(review, existingTags.get(name)))
                .toList();
        reviewTagRepository.saveAll(reviewTags);

        return uniqueTagNames;
    }
    private void validateReviewOwner(Review review, Long memberUserId) {
        if (!review.getMember().getMemberUserId().equals(memberUserId)) {
            throw new BusinessException(ErrorCode.NOT_REVIEW_OWNER);
        }
    }

    //삭제안된 리뷰 찾기
    private Review getReviewOrThrow(Long reviewId) {
        return reviewRepository.findByReviewIdAndIsDeletedFalse(reviewId).orElseThrow(()-> new BusinessException(ErrorCode.REVIEW_NOT_FOUND));
    }
    //태그 이름 목록 조회 (단건)
    private List<String> getTagNames(Review review) {
        return reviewTagRepository.findByReview(review).stream()
                .map(reviewTag -> reviewTag.getTag().getTagName()).toList();
    }

    //태그 이름 목록 일괄 조회 (IN절 - N+1 방지)
    private Map<Long, List<String>> getTagNamesByReviews(List<Review> reviews) {
        if (reviews.isEmpty()) return Map.of();
        List<Long> reviewIds = reviews.stream().map(Review::getReviewId).toList();
        return reviewTagRepository.findByReviewIdIn(reviewIds).stream()
                .collect(Collectors.groupingBy(
                        rt -> rt.getReview().getReviewId(),
                        Collectors.mapping(rt -> rt.getTag().getTagName(), Collectors.toList())
                ));
    }
}

