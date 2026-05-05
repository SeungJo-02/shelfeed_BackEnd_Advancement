package com.shelfeed.backend.global.common.exception;
//에러코드 관리
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {//enum 타입은 public 불가(자동 private) + 객체 생성 시 new 안됨
    //enum 규칙
    //상수 선언이 먼저, 다음 필드, 다음 생성자(어노테이션)
    //
    //멤버
    MEMBER_NOT_FOUND(404, "M001", "존재하지 않는 회원입니다."),
    EMAIL_ALREADY_EXISTS(409, "M002", "이미 사용 중인 이메일입니다."),
    NICKNAME_ALREADY_EXISTS(409, "M003", "이미 사용 중인 닉네임입니다."),
    ONBOARDING_ALREADY_COMPLETED(409, "M006", "이미 온보딩이 완료되었습니다."),
    INVALID_CURRENT_PASSWORD(400, "M004", "현재 비밀번호가 올바르지 않습니다."),
    NO_PASSWORD_ACCOUNT(400, "M005", "소셜 로그인 계정은 비밀번호를 변경할 수 없습니다."),
    //인증
    INVALID_TOKEN(401, "A001", "유효하지 않은 토큰입니다."),
    EXPIRED_TOKEN(401, "A002", "만료된 토큰입니다."),
    UNAUTHORIZED(401, "A003", "인증이 필요합니다."),
    INVALID_PASSWORD(401,"A004", "비밀번호가 일치하지 않습니다."),
    WITHDRAWN_MEMBER(403,"A005", "탈퇴한 계정입니다."),
    INVALID_EMAIL_CODE(400,"A006", "인증 코드가 올바르지 않습니다."),
    CODE_EXPIRED(400,"A007", "인증 코드가 만료되었습니다."),
    CODE_ATTEMPTS_EXCEEDED(429,"A008", "인증 시도 횟수를 초과했습니다. 코드를 재발송해주세요."),
    ALREADY_VERIFIED_EMAIL(409,"A009", "이미 인증된 이메일입니다."),
    RESEND_COOLDOWN(429,"A010", "60초 후에 다시 시도해주세요."),
    INVALID_PASSWORD_RESET_TOKEN(400, "A011", "유효하지 않거나 만료된 비밀번호 재설정 토큰입니다."),
    SAME_PASSWORD(400,"A012", "기존 비밀번호와 동일합니다."),
    TOKEN_REUSE_DETECTED(401,"A013", "토큰 재사용이 감지되었습니다. 모든 세션이 로그아웃됩니다."),
    INVALID_OAUTH_STATE(400,"A014", "유효하지 않은 OAuth state입니다."),
    OAUTH_PROVIDER_ERROR(502,"A015", "OAuth 제공자 연동 중 오류가 발생했습니다."),
    EMAIL_SEND_FAILED(502,"A016", "이메일 발송에 실패했습니다."),
    //도서
    BOOK_NOT_FOUND(404, "B001", "존재하지 않는 도서입니다."),
    //장르
    GENRE_NOT_FOUND(404, "G001", "존재하지 않는 장르입니다."),
    //일반
    INVALID_INPUT(400, "C002", "잘못된 입력값입니다."),
    FORBIDDEN(403, "C001", "접근 권한이 없습니다."),
    //서재
    LIBRARY_BOOK_NOT_FOUND(404, "L001", "서재에서 도서를 찾을 수 없습니다."),
    ALREADY_IN_LIBRARY(409, "L002", "이미 서재에 추가된 도서입니다."),
    REVIEW_EXISTS(409, "L003", "감상이 존재하는 도서는 제거할 수 없습니다. 감상을 먼저 삭제해주세요."),
    //감상
    REVIEW_NOT_FOUND(404, "R001", "존재하지 않는 감상입니다."),
    DUPLICATE_REVIEW(409, "R002", "이미 해당 도서에 대한 감상이 존재합니다."),
    NOT_REVIEW_OWNER(403, "R003", "본인의 감상만 수정/삭제할 수 있습니다."),
    CONTENT_OR_QUOTE_REQUIRED(400, "R004", "감상 내용 또는 인용구 중 하나는 필수입니다."),
    INVALID_RATING(400, "R005", "평점은 1~5 사이의 정수여야 합니다."),
    TOO_MANY_TAGS(400, "R006", "태그는 최대 5개까지 등록할 수 있습니다."),
    PRIVATE_REVIEW(403, "R007", "비공개 감상입니다."),
    REVIEW_NOT_PUBLISHED(400, "R008", "발행된 리뷰에만 댓글을 작성할 수 있습니다."),
    // 댓글
    COMMENT_NOT_FOUND(404, "CM001", "존재하지 않는 댓글입니다."),
    NOT_COMMENT_OWNER(403, "CM002", "본인의 댓글만 수정/삭제할 수 있습니다."),
    PARENT_COMMENT_NOT_FOUND(404, "CM003", "부모 댓글을 찾을 수 없습니다."),
    NESTED_REPLY_NOT_ALLOWED(400, "CM004", "대댓글에 대한 답글은 작성할 수 없습니다."),
    //좋아요
    SELF_LIKE_NOT_ALLOWED(400, "LK001", "본인의 게시물에는 좋아요할 수 없습니다."),
    REVIEW_LIKE_NOT_FOUND(404, "LK002", "감상 좋아요 내역이 존재하지 않습니다."),
    COMMENT_LIKE_NOT_FOUND(404, "LK003", "댓글 좋아요 내역이 존재하지 않습니다."),
    ALREADY_REVIEW_LIKED(409, "LK004", "이미 좋아요한 감상입니다."),
    ALREADY_COMMENT_LIKED(409, "LK005", "이미 좋아요한 댓글입니다."),


    //알림
    NOTIFICATION_NOT_FOUND(404, "N001", "알림을 찾을 수 없습니다."),
    //신고
    REPORT_TARGET_NOT_FOUND(404, "RP001", "신고 대상을 찾을 수 없습니다."),
    REPORT_SELF_NOT_ALLOWED(400, "RP002", "자기 자신 또는 자기 콘텐츠는 신고할 수 없습니다."),
    REPORT_ALREADY_EXISTS(409, "RP003", "이미 신고한 대상입니다."),


    //팔로우
    FOLLOW_NOT_FOUND(404,"F001" ,"팔로우 내역이 존재하지 않습니다."),
    ALREADY_FOLLOWING(409,"F002" ,"이미 팔로우한 사용자입니다."),
    CANNOT_FOLLOW_SELF(409,"F003" ,"자기 자신은 팔로우할 수 없습니다."),
    //검색
    SEARCH_QUERY_REQUIRED(400, "S001", "검색어를 입력해주세요."),
    INVALID_SEARCH_TYPE(400, "S002", "유효하지 않은 검색 타입입니다. (all, book, user)"),
    //차단
    SELF_BLOCK_NOT_ALLOWED(400, "BL001", "자기 자신을 차단할 수 없습니다."),
    ALREADY_BLOCKED(409, "BL002", "이미 차단된 사용자입니다."),
    BLOCK_NOT_FOUND(404, "BL003", "차단 관계를 찾을 수 없습니다."),
    BLOCKED_USER(403, "BL004", "차단된 사용자입니다."),
    ;

    private final int status;
    private final String code;
    private final String message;

}
