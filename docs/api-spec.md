# Shelfeed REST API 명세서

> **출처(기준): 실제 백엔드 코드** (`BackEnd_Project`, Spring Boot 3.4 / Java 21) — 코드에서 자동 추출·검증.
> 작성일: 2026-06-10 · 총 **64개 엔드포인트** / 14개 컨트롤러.

## 공통 규약

- **Base URL**: `/api/v1`
- **응답 봉투**: 모든 응답은 `ApiResponse<T>`로 감쌈 — `{ "status": "SUCCESS|ERROR", "code": <httpStatus>, "message": <string?>, "data": <T> }` (null 필드는 직렬화 제외).
- **인증**: `Authorization: Bearer <accessToken>` 헤더. 리프레시 토큰은 httpOnly 쿠키. 토큰에는 `type`(access/refresh) 클레임 포함 — access 토큰만 인증에 사용 가능.
- **인증 구분**: `공개`(permitAll) / `인증 필요`(로그인) / `ADMIN`(관리자). 일부 GET은 비로그인 허용이되 로그인 시 개인화 필드(isFollowing/isLiked 등) 채워짐.
- **페이지네이션**: 커서 기반. 대부분 `cursor`(Long PK) + `limit`(default 20), 도서리뷰 정렬은 `cursorLike`/`cursorRating` 추가, 알림은 opaque `String cursor`. 목록 응답은 `hasNext`/`nextCursor` 포함.
- **에러**: `status:"ERROR"`, `code`=HTTP 상태, `message`=사유. (참고: 일부 엔드포인트는 비즈니스 에러코드 미노출 — 개선 계획 BE-14 참조)

---

## 1. 인증 · 회원 · 관리자 (Auth / Member / Admin)

### POST /api/v1/auth/signup
- 목적: 이메일 회원가입
- 인증: 공개 (permitAll)
- 파라미터: 없음
- 요청 (SignupRequest):
  - `email` (string, 필수) — @NotBlank, @Email
  - `password` (string, 필수) — @NotBlank, @Pattern `^(?=.*[A-Za-z])(?=.*\d)(?=.*[@$!%*#?&])[A-Za-z\d@$!%*#?&]{8,}$` (8자 이상, 영문+숫자+특수문자)
  - `nickname` (string, 필수) — @NotBlank, @Size(min=2, max=50)
  - `bio` (string, 선택) — @Size(max=300)
- 응답 (SignupResponse):
  - `accessToken` (string)
  - `accessTokenExpiresIn` (number, long, 초 단위)
  - `user` (object):
    - `userId` (number, Long)
    - `email` (string)
    - `nickname` (string)
    - `bio` (string)
    - `emailVerified` (boolean)
- 성공 상태: **201 Created** (refreshToken은 `refreshToken` 쿠키로 설정, path=/api/v1/auth, HttpOnly, Secure, SameSite=Strict)

### POST /api/v1/auth/email/verify
- 목적: 이메일 인증코드 확인
- 인증: 공개 (permitAll, `/api/v1/auth/email/**`)
- 파라미터: 없음
- 요청 (EmailVerifyRequest):
  - `email` (string, 필수) — @NotBlank, @Email
  - `code` (string, 필수) — @NotBlank, @Pattern `^\d{6}$` (6자리 숫자)
- 응답 (EmailVerifyResponse):
  - `email` (string)
  - `emailVerified` (boolean)
- 성공 상태: 200

### POST /api/v1/auth/email/resend
- 목적: 이메일 인증 코드 재발송
- 인증: 공개 (permitAll, `/api/v1/auth/email/**`)
- 파라미터: 없음
- 요청 (EmailResendRequest):
  - `email` (string, 필수) — @NotBlank, @Email
- 응답: `Void` (`data` = null)
- 성공 상태: 200

### POST /api/v1/auth/login
- 목적: 이메일 로그인
- 인증: 공개 (permitAll)
- 파라미터: 없음
- 요청 (LoginRequest):
  - `email` (string, 필수) — @NotBlank, @Email
  - `password` (string, 필수) — @NotBlank
- 응답 (LoginResponse):
  - `accessToken` (string)
  - `accessTokenExpiresIn` (number, Long)
  - `user` (object):
    - `userId` (number, Long)
    - `email` (string)
    - `nickname` (string)
    - `profileImageUrl` (string)
    - `bio` (string)
    - `emailVerified` (boolean)
    - `onboardingCompleted` (boolean)
- 성공 상태: 200 (refreshToken 쿠키 설정)

### GET /api/v1/auth/oauth2/google
- 목적: Google OAuth 로그인 URL 발급
- 인증: 공개 (permitAll, `/api/v1/auth/oauth2/**`)
- 파라미터: 없음
- 요청: 없음
- 응답 (OAuthLoginUrlResponse):
  - `loginUrl` (string)
- 성공 상태: 200

### POST /api/v1/auth/oauth2/google/login
- 목적: Google OAuth 로그인 완료(코드 교환)
- 인증: 공개 (permitAll, `/api/v1/auth/oauth2/**`)
- 파라미터: 없음
- 요청 (OAuthTokenRequest):
  - `code` (string, 필수) — @NotBlank
  - `redirectUri` (string, 필수) — @NotBlank
  - `state` (string, 필수) — @NotBlank
- 응답 (GoogleLoginResponse):
  - `accessToken` (string)
  - `accessTokenExpiresIn` (number, Long)
  - `isNewUser` (boolean) — JSON 키 `isNewUser`
  - `user` (object):
    - `userId` (number, Long)
    - `email` (string)
    - `nickname` (string)
    - `profileImageUrl` (string)
    - `emailVerified` (boolean)
    - `onboardingCompleted` (boolean)
- 성공 상태: 200 (refreshToken 쿠키 설정)

### POST /api/v1/auth/token/refresh
- 목적: 액세스 토큰 갱신
- 인증: 공개 (permitAll) — 단, `refreshToken` 쿠키 필요
- 파라미터:
  - `refreshToken` (cookie, string, 선택; required=false) — 갱신 대상 리프레시 토큰
- 요청 본문: 없음
- 응답 (TokenRefreshResponse):
  - `accessToken` (string)
  - `accessTokenExpiresIn` (number, Long)
- 성공 상태: 200 (새 refreshToken 쿠키 재설정)

### POST /api/v1/auth/logout
- 목적: 로그아웃 (액세스 토큰 블랙리스트 + 리프레시 토큰 폐기)
- 인증: 인증 필요 (PERMIT_ALL/GET_PERMIT_ALL에 없으므로 `anyRequest().authenticated()`)
- 파라미터:
  - `Authorization` (header, string, 필수) — `Bearer {accessToken}` 형식. 없거나 형식 불일치 시 INVALID_TOKEN 예외
  - `refreshToken` (cookie, string, 선택; required=false)
- 요청 본문: 없음
- 응답: `Void` (`data` = null)
- 성공 상태: 200 (refreshToken 쿠키 삭제 maxAge=0)

### POST /api/v1/auth/password/reset-request
- 목적: 비밀번호 재설정 이메일 요청
- 인증: 공개 (permitAll, `/api/v1/auth/password/**`)
- 파라미터: 없음
- 요청 (PasswordResetSendRequest):
  - `email` (string, 필수) — @NotBlank, @Email
- 응답: `Void` (`data` = null)
- 성공 상태: 200

### POST /api/v1/auth/password/reset
- 목적: 비밀번호 재설정(토큰 검증 후 변경)
- 인증: 공개 (permitAll, `/api/v1/auth/password/**`)
- 파라미터: 없음
- 요청 (PasswordResetRequest):
  - `token` (string, 필수) — @NotBlank
  - `newPassword` (string, 필수) — @NotBlank, @Pattern `^(?=.*[A-Za-z])(?=.*\d)(?=.*[@$!%*#?&])[A-Za-z\d@$!%*#?&]{8,}$`
- 응답: `Void` (`data` = null)
- 성공 상태: 200

### GET /api/v1/auth/check-nickname
- 목적: 닉네임 중복 확인
- 인증: 공개 (permitAll)
- 파라미터:
  - `nickname` (query, string, 필수) — @RequestParam, 기본값 없음
- 요청 본문: 없음
- 응답 (AvailableResponse):
  - `available` (boolean)
- 성공 상태: 200

### GET /api/v1/auth/check-email
- 목적: 이메일 중복 확인
- 인증: 공개 (permitAll)
- 파라미터:
  - `email` (query, string, 필수) — @RequestParam, 기본값 없음
- 요청 본문: 없음
- 응답 (AvailableResponse):
  - `available` (boolean)
- 성공 상태: 200

---

## Member / Users (`/api/v1/users`)

### POST /api/v1/users/me/onboarding
- 목적: 온보딩 완료(프로필 + 관심 장르 설정)
- 인증: 인증 필요 (`anyRequest().authenticated()`; `@AuthenticationPrincipal` 사용)
- 파라미터: 없음
- 요청 (OnboardingRequest):
  - `nickname` (string, 필수) — @NotBlank, @Size(max=50)
  - `profileImageUrl` (string, 선택) — 검증 없음
  - `bio` (string, 선택) — @Size(max=300)
  - `genreIds` (array of number/Long, 필수) — @NotEmpty (최소 1개)
- 응답 (OnboardingResponse):
  - `userId` (number, Long)
  - `nickname` (string)
  - `profileImageUrl` (string)
  - `bio` (string)
  - `onboardingCompleted` (boolean)
  - `genres` (array of object):
    - `genreId` (number, Long)
    - `name` (string)
- 성공 상태: **201 Created** (`@ResponseStatus(HttpStatus.CREATED)`)

### PUT /api/v1/users/me/genres
- 목적: 관심 장르 설정/수정
- 인증: 인증 필요
- 파라미터: 없음
- 요청 (UpdateGenresRequest):
  - `genreIds` (array of number/Long, 필수) — @NotEmpty (최소 1개)
- 응답 (UpdateGenresResponse):
  - `genres` (array of object):
    - `genreId` (number, Long)
    - `name` (string)
- 성공 상태: 200

### GET /api/v1/users/me
- 목적: 내 프로필 조회
- 인증: 인증 필요 (`/api/v1/users/me`는 GET_PERMIT_ALL의 `{userId}` 패턴에 매칭되지 않으므로 인증 필요)
- 파라미터: 없음
- 요청 본문: 없음
- 응답 (MyProfileResponse):
  - `userId` (number, Long)
  - `email` (string)
  - `nickname` (string)
  - `profileImageUrl` (string)
  - `bio` (string)
  - `libraryVisibility` (string enum: PUBLIC | PRIVATE)
  - `emailVerified` (boolean)
  - `onboardingCompleted` (boolean)
  - `followerCount` (number, int)
  - `followingCount` (number, int)
  - `reviewCount` (number, int)
  - `genres` (array of object):
    - `genreId` (number, Long)
    - `name` (string)
- 성공 상태: 200

### PATCH /api/v1/users/me
- 목적: 내 프로필 수정
- 인증: 인증 필요
- 파라미터: 없음
- 요청 (UpdateProfileRequest) — 모든 필드 선택(부분 수정):
  - `nickname` (string, 선택) — @Size(max=50)
  - `profileImageUrl` (string, 선택) — 검증 없음
  - `bio` (string, 선택) — @Size(max=300)
- 응답 (UpdateProfileResponse):
  - `userId` (number, Long)
  - `nickname` (string)
  - `profileImageUrl` (string)
  - `bio` (string)
  - `libraryVisibility` (string enum: PUBLIC | PRIVATE)
- 성공 상태: 200

### GET /api/v1/users/{userId}
- 목적: 타 유저 프로필 조회
- 인증: 공개 (GET_PERMIT_ALL의 `/api/v1/users/{userId}`; 로그인 시 인증 정보로 팔로우 여부 채움, 비로그인 허용)
- 파라미터:
  - `userId` (path, number/Long, 필수) — @PathVariable
- 요청 본문: 없음
- 응답 (UserProfileResponse):
  - `userId` (number, Long)
  - `nickname` (string)
  - `profileImageUrl` (string)
  - `bio` (string)
  - `libraryVisibility` (string enum: PUBLIC | PRIVATE)
  - `followerCount` (number, int)
  - `followingCount` (number, int)
  - `reviewCount` (number, int)
  - `isFollowing` (boolean) — JSON 키 `isFollowing`
  - `isFollowedBy` (boolean) — JSON 키 `isFollowedBy`
- 성공 상태: 200

### PUT /api/v1/users/me/password
- 목적: 비밀번호 변경
- 인증: 인증 필요
- 파라미터: 없음
- 요청 (ChangePasswordRequest):
  - `currentPassword` (string, 필수) — @NotBlank
  - `newPassword` (string, 필수) — @NotBlank, @Pattern `^(?=.*[A-Za-z])(?=.*\d)(?=.*[@$!%*#?&])[A-Za-z\d@$!%*#?&]{8,}$`
- 응답: `Void` (`data` = null)
- 성공 상태: 200 (변경 후 새 refreshToken 쿠키 재설정)

### GET /api/v1/users/me/settings
- 목적: 알림/공개범위 설정 조회
- 인증: 인증 필요
- 파라미터: 없음
- 요청 본문: 없음
- 응답 (SettingsResponse):
  - `likeEnabled` (boolean)
  - `commentEnabled` (boolean)
  - `followEnabled` (boolean)
  - `followingReviewEnabled` (boolean)
  - `libraryVisibility` (string enum: PUBLIC | PRIVATE)
- 성공 상태: 200

### PATCH /api/v1/users/me/settings
- 목적: 알림/공개범위 설정 수정
- 인증: 인증 필요
- 파라미터: 없음
- 요청 (SettingsUpdateRequest) — 모든 필드 선택(부분 수정, 검증 없음):
  - `likeEnabled` (boolean, nullable)
  - `commentEnabled` (boolean, nullable)
  - `followEnabled` (boolean, nullable)
  - `followingReviewEnabled` (boolean, nullable)
  - `libraryVisibility` (string enum: PUBLIC | PRIVATE, nullable)
- 응답 (SettingsResponse):
  - `likeEnabled` (boolean)
  - `commentEnabled` (boolean)
  - `followEnabled` (boolean)
  - `followingReviewEnabled` (boolean)
  - `libraryVisibility` (string enum: PUBLIC | PRIVATE)
- 성공 상태: 200

### DELETE /api/v1/users/me
- 목적: 회원 탈퇴
- 인증: 인증 필요
- 파라미터:
  - `Authorization` (header, string, 선택; required=false) — `Bearer {accessToken}` 형식 필요. 없거나 형식 불일치 시 INVALID_TOKEN 예외
- 요청 (WithdrawRequest):
  - `password` (string, 선택) — @Size(max=100)
  - `reason` (string, 선택) — @Size(max=500)
- 응답: `Void` (`data` = null)
- 성공 상태: 200 (refreshToken 쿠키 삭제 maxAge=0)

---

## Admin (`/api/v1/admin`)

클래스 레벨 `@PreAuthorize("hasRole('ADMIN')")` + SecurityConfig `/api/v1/admin/**` → `hasRole("ADMIN")`.

### GET /api/v1/admin/dashboard
- 목적: 관리자 대시보드 통계 조회
- 인증: **ADMIN** (ROLE_ADMIN 필요)
- 파라미터: 없음
- 요청 본문: 없음
- 응답 (AdminDashboardResponse):
  - `totalMembers` (number, long)
  - `pendingReports` (number, long)
  - `totalReports` (number, long)
- 성공 상태: 200

---

## 2. 도서 · 감상 · 서재 (Book / Review / Library)

### GET /api/v1/books/search

- **목적**: 도서 검색 (알라딘 API 연동, 커서 페이지네이션)
- **인증**: 공개 (로그인 시 `inMyLibrary` 반영 — 선택적 인증)
- **파라미터** (query, `@ModelAttribute BookSearchRequest`):
  - `query` (String, 선택 — 코드상 검증 없음, 주석상 검색어): 검색어
  - `limit` (int, 선택, 기본값 `20`, Swagger 제약 min 1 / max 50): 조회 개수
  - `page` (int, 선택, 기본값 `1`, Swagger 제약 min 1): 알라딘 API 페이지 번호
- **요청 본문**: 없음
- **응답** (`BookSearchListResponse`):
  - `content` (array of `BookSummaryResponse`):
    - `bookId` (Long)
    - `isbn13` (String)
    - `title` (String)
    - `author` (String)
    - `publisher` (String)
    - `coverImageUrl` (String)
    - `publishedDate` (LocalDate, `yyyy-MM-dd`)
    - `inMyLibrary` (Boolean)
  - `nextCursor` (Long, nullable — 마지막 항목의 bookId, hasNext=false면 null)
  - `hasNext` (boolean)
  - `size` (int)
- **성공 상태**: 200

### GET /api/v1/books/{bookId}

- **목적**: 도서 상세 조회 (평점/리뷰수/내 서재·리뷰 상태 포함)
- **인증**: 공개 (로그인 시 `myLibraryStatus`/`myLibraryBookId`/`myReviewId` 반영 — 선택적 인증)
- **파라미터**:
  - `bookId` (Long, path, 필수)
- **요청 본문**: 없음
- **응답** (`BookDetailResponse`, 상세조회용 `of(...)`):
  - `bookId` (Long)
  - `isbn13` (String)
  - `title` (String)
  - `author` (String)
  - `publisher` (String)
  - `coverImageUrl` (String)
  - `description` (String)
  - `totalPages` (Integer)
  - `publishedDate` (LocalDate, `yyyy-MM-dd`)
  - `aladinItemId` (String)
  - `averageRating` (Double)
  - `reviewCount` (Long)
  - `myLibraryStatus` (String — ReadingStatus 이름, nullable)
  - `myLibraryBookId` (Long, nullable)
  - `myReviewId` (Long, nullable)
  - (참고: `inMyLibrary` 필드는 이 응답에서는 미설정 → null 이므로 JSON 제외)
- **성공 상태**: 200

### GET /api/v1/books/isbn/{isbn13}

- **목적**: ISBN13으로 도서 조회
- **인증**: 공개 (로그인 시 `inMyLibrary` 반영 — 선택적 인증)
- **파라미터**:
  - `isbn13` (String, path, 필수)
- **요청 본문**: 없음
- **응답** (`BookDetailResponse`, ISBN용 `ofIsbn(...)`):
  - `bookId` (Long)
  - `isbn13` (String)
  - `title` (String)
  - `author` (String)
  - `publisher` (String)
  - `coverImageUrl` (String)
  - `description` (String)
  - `totalPages` (Integer)
  - `publishedDate` (LocalDate, `yyyy-MM-dd`)
  - `aladinItemId` (String)
  - `inMyLibrary` (Boolean)
  - (참고: `averageRating`, `reviewCount`, `myLibraryStatus`, `myLibraryBookId`, `myReviewId` 는 미설정 → null 이므로 JSON 제외)
- **성공 상태**: 200

### GET /api/v1/books/{bookId}/reviews

- **목적**: 특정 도서의 감상 목록 조회 (정렬/커서 페이지네이션)
- **인증**: 공개 (로그인 시 각 항목 `isLiked` 반영 — 선택적 인증)
- **파라미터**:
  - `bookId` (Long, path, 필수)
  - query (`@ModelAttribute BookReviewSearchRequest`):
    - `sort` (String, 선택, 기본값 `latest`, 허용값 `latest`/`popular`/`rating_high`/`rating_low`)
    - `cursor` (Long, nullable): 페이지네이션 커서 — `latest`/`popular`: 직전 마지막 reviewId, `rating_*`: 직전 마지막 reviewId
    - `cursorRating` (Integer, nullable): 평점순 전용 커서 — 직전 마지막 항목의 rating (`rating_high`/`rating_low` 에서만 사용)
    - `cursorLike` (Integer, nullable): 인기순 전용 커서 — 직전 마지막 항목의 likeCount (`popular` 에서만 사용)
    - `limit` (int, 선택, 기본값 `20`, Swagger 제약 min 1 / max 50)
- **요청 본문**: 없음
- **응답** (`BookReviewListResponse`):
  - `content` (array of `BookReviewResponse`):
    - `reviewId` (Long)
    - `user` (object `UserInfo`):
      - `userId` (Long)
      - `nickname` (String)
      - `profileImageUrl` (String)
    - `rating` (int)
    - `content` (String)
    - `quote` (String)
    - `isSpoiler` (Boolean)
    - `likeCount` (int)
    - `commentCount` (int)
    - `isLiked` (Boolean)
    - `createdAt` (LocalDateTime, ISO-8601)
  - `nextCursor` (Long, nullable — 마지막 항목 reviewId)
  - `nextCursorRating` (Integer, nullable — `rating_*` 정렬 시에만 채워짐, 마지막 항목 rating)
  - `nextCursorLike` (Integer, nullable — `popular` 정렬 시에만 채워짐, 마지막 항목 likeCount)
  - `hasNext` (boolean)
  - `size` (int)
- **성공 상태**: 200

---

## Review 도메인 (`/api/v1`, base path 분산)

### POST /api/v1/reviews

- **목적**: 감상(리뷰) 작성
- **인증**: 인증 필요 (`anyRequest().authenticated()`; 컨트롤러가 `userDetails.getMember()` 직접 호출)
- **파라미터**: 없음
- **요청 본문** (`ReviewCreateRequest`, `@Valid`):
  - `bookId` (Long, 필수 — `@NotNull` "도서 ID는 필수입니다.")
  - `libraryBookId` (Long, 선택)
  - `rating` (Byte, 필수 — `@NotNull`, `@Min(1)`/`@Max(5)` "평점은 1~5 사이여야 합니다.")
  - `content` (String, 선택)
  - `quote` (String, 선택)
  - `readPages` (Integer, 선택)
  - `isSpoiler` (boolean, 선택, JSON 키 `isSpoiler`)
  - `reviewVisibility` (ReviewVisibility enum, 필수 — `@NotNull` "공개 범위는 필수입니다.")
  - `reviewStatus` (ReviewStatus enum, 필수 — `@NotNull` "감상 상태는 필수입니다.")
  - `tags` (array of String, 선택 — `@Size(max=5)` "태그는 최대 5개까지 등록할 수 있습니다.")
- **응답** (`ReviewCreateResponse`):
  - `reviewId` (Long)
  - `bookId` (Long)
  - `rating` (byte)
  - `content` (String)
  - `quote` (String)
  - `readPages` (Integer)
  - `isSpoiler` (boolean, JSON 키 `isSpoiler`)
  - `reviewVisibility` (ReviewVisibility)
  - `reviewStatus` (ReviewStatus)
  - `likeCount` (int)
  - `commentCount` (int)
  - `tags` (array of String)
  - `createdAt` (LocalDateTime)
- **성공 상태**: **201 Created** (`@ResponseStatus(CREATED)`, message "감상이 작성되었습니다.")

### GET /api/v1/reviews/{reviewId}

- **목적**: 감상 상세 조회
- **인증**: 공개 (GET_PERMIT_ALL에 `/api/v1/reviews/{reviewId}` 포함; 로그인 시 `isMine`/`isLiked` 반영 — 선택적 인증)
- **파라미터**:
  - `reviewId` (Long, path, 필수)
- **요청 본문**: 없음
- **응답** (`ReviewDetailResponse`):
  - `reviewId` (Long)
  - `user` (object `UserInfo`): `userId` (Long), `nickname` (String), `profileImageUrl` (String)
  - `book` (object `BookInfo`): `bookId` (Long), `isbn13` (String), `title` (String), `author` (String), `coverImageUrl` (String)
  - `rating` (byte)
  - `content` (String)
  - `quote` (String)
  - `readPages` (Integer)
  - `isSpoiler` (boolean, JSON 키 `isSpoiler`)
  - `reviewVisibility` (ReviewVisibility)
  - `reviewStatus` (ReviewStatus)
  - `likeCount` (int)
  - `commentCount` (int)
  - `isLiked` (boolean, JSON 키 `isLiked`)
  - `isMine` (boolean, JSON 키 `isMine`)
  - `tags` (array of String)
  - `createdAt` (LocalDateTime)
  - `updatedAt` (LocalDateTime)
- **성공 상태**: 200

### PUT /api/v1/reviews/{reviewId}

- **목적**: 감상 수정
- **인증**: 인증 필요 (PUT은 permitAll 목록에 없음; `anyRequest().authenticated()`)
- **파라미터**:
  - `reviewId` (Long, path, 필수)
- **요청 본문** (`ReviewUpdateRequest`, `@Valid`):
  - `rating` (Byte, 필수 — `@NotNull`, `@Min(1)`/`@Max(5)`)
  - `content` (String, 선택)
  - `quote` (String, 선택)
  - `readPages` (Integer, 선택)
  - `isSpoiler` (boolean, 선택, JSON 키 `isSpoiler`)
  - `reviewVisibility` (ReviewVisibility enum, 필수 — `@NotNull`)
  - `reviewStatus` (ReviewStatus enum, 필수 — `@NotNull`)
  - `tags` (array of String, 선택 — `@Size(max=5)`)
- **응답** (`ReviewUpdateResponse`):
  - `reviewId` (Long)
  - `rating` (byte)
  - `content` (String)
  - `quote` (String)
  - `readPages` (Integer)
  - `isSpoiler` (boolean, JSON 키 `isSpoiler`)
  - `reviewVisibility` (ReviewVisibility)
  - `reviewStatus` (ReviewStatus)
  - `tags` (array of String)
  - `updatedAt` (LocalDateTime)
- **성공 상태**: 200 (message "감상이 수정되었습니다.")

### DELETE /api/v1/reviews/{reviewId}

- **목적**: 감상 삭제
- **인증**: 인증 필요 (`anyRequest().authenticated()`)
- **파라미터**:
  - `reviewId` (Long, path, 필수)
- **요청 본문**: 없음
- **응답**: `data` 없음 (`Void`) — message만 반환
- **성공 상태**: 200 (message "감상이 삭제되었습니다.")

### GET /api/v1/reviews/me

- **목적**: 내 감상 목록 조회 (상태 필터 + 커서 페이지네이션)
- **인증**: 인증 필요 (SecurityConfig에서 명시적으로 `GET /api/v1/reviews/me` → `authenticated()`; `{reviewId}` 와일드카드 매칭 방지)
- **파라미터** (query):
  - `status` (ReviewStatus enum, 선택, `required=false` — `DRAFT`/`PUBLISHED`)
  - `cursor` (Long, 선택, `required=false`): 직전 마지막 reviewId
  - `limit` (int, 선택, 기본값 `20`)
- **요청 본문**: 없음
- **응답**: `List<ReviewSummaryResponse>` (리스트 래퍼 없이 배열 직접 반환):
  - 각 항목 `ReviewSummaryResponse`:
    - `reviewId` (Long)
    - `book` (object `BookInfo`): `bookId` (Long), `title` (String), `author` (String), `coverImageUrl` (String)
    - `rating` (byte)
    - `content` (String)
    - `quote` (String)
    - `isSpoiler` (boolean, JSON 키 `isSpoiler`)
    - `reviewVisibility` (ReviewVisibility)
    - `reviewStatus` (ReviewStatus)
    - `likeCount` (int)
    - `commentCount` (int)
    - `isLiked` (boolean, JSON 키 `isLiked`)
    - `tags` (array of String)
    - `createdAt` (LocalDateTime)
- **성공 상태**: 200

### GET /api/v1/members/{userId}/reviews

- **목적**: 타 유저 감상 목록 조회 (커서 페이지네이션)
- **인증**: 공개 (GET_PERMIT_ALL에 `/api/v1/members/{userId}/reviews` 포함; 로그인 시 `requestingUserId` 로 `isLiked` 등 반영 — 선택적 인증)
- **파라미터**:
  - `userId` (Long, path, 필수)
  - query:
    - `cursor` (Long, 선택, `required=false`): 직전 마지막 reviewId
    - `limit` (int, 선택, 기본값 `20`)
- **요청 본문**: 없음
- **응답**: `List<ReviewSummaryResponse>` (위 `/reviews/me` 와 동일 항목 구조, 배열 직접 반환)
- **성공 상태**: 200

### POST /api/v1/reviews/{reviewId}/likes

- **목적**: 감상 좋아요
- **인증**: 인증 필요 (`anyRequest().authenticated()`)
- **파라미터**:
  - `reviewId` (Long, path, 필수)
- **요청 본문**: 없음
- **응답** (`ReviewLikeResponse`):
  - `reviewId` (Long)
  - `likeCount` (int)
- **성공 상태**: **201 Created** (`@ResponseStatus(CREATED)`, message "좋아요를 눌렀습니다.")

### DELETE /api/v1/reviews/{reviewId}/likes

- **목적**: 감상 좋아요 취소
- **인증**: 인증 필요 (`anyRequest().authenticated()`)
- **파라미터**:
  - `reviewId` (Long, path, 필수)
- **요청 본문**: 없음
- **응답** (`ReviewLikeResponse`):
  - `reviewId` (Long)
  - `likeCount` (int)
- **성공 상태**: 200 (message "좋아요가 취소되었습니다.")

---

## Library 도메인 (`/api/v1`, base path 분산)

### POST /api/v1/library

- **목적**: 내 서재에 도서 추가
- **인증**: 인증 필요 (`anyRequest().authenticated()`)
- **파라미터**: 없음
- **요청 본문** (`LibraryBookAddRequest`, `@Valid`):
  - `bookId` (Long, 필수 — `@NotNull` "도서 ID는 필수입니다.")
  - `status` (ReadingStatus enum, 필수 — `@NotNull` "독서 상태는 필수입니다." — `WANT_TO_READ`/`READING`/`FINISHED`/`STOPPED`)
- **응답** (`LibraryBookAddResponse`):
  - `libraryBookId` (Long)
  - `bookId` (Long)
  - `status` (ReadingStatus)
  - `startedAt` (LocalDate, nullable)
  - `finishedAt` (LocalDate, nullable)
- **성공 상태**: **201 Created** (`@ResponseStatus(CREATED)`, message "도서가 서재에 추가되었습니다.")

### GET /api/v1/library/me

- **목적**: 내 서재 목록 조회 (상태 필터 + 커서 페이지네이션)
- **인증**: 인증 필요 (`anyRequest().authenticated()`)
- **파라미터** (query):
  - `status` (ReadingStatus enum, 선택, `required=false`)
  - `cursor` (Long, 선택, `required=false`): 직전 마지막 libraryBookId
  - `limit` (int, 선택, 기본값 `20`)
- **요청 본문**: 없음
- **응답** (`LibraryListResponse`):
  - `content` (array of `LibraryBookSummaryResponse`):
    - `libraryBookId` (Long)
    - `book` (object `BookSummary`): `bookId` (Long), `isbn13` (String), `title` (String), `author` (String), `coverImageUrl` (String)
    - `status` (ReadingStatus)
    - `startedAt` (LocalDate, nullable)
    - `finishedAt` (LocalDate, nullable)
    - `hasReview` (boolean)
  - `nextCursor` (Long, nullable — 마지막 항목 libraryBookId)
  - `hasNext` (boolean)
  - `size` (int)
- **성공 상태**: 200

### GET /api/v1/library/{libraryBookId}

- **목적**: 서재 도서 상세 조회 (해당 도서의 내 리뷰 요약 포함)
- **인증**: 인증 필요 (`anyRequest().authenticated()`; 컨트롤러가 `userDetails.getMember()` 직접 호출 — null 비허용)
- **파라미터**:
  - `libraryBookId` (Long, path, 필수)
- **요청 본문**: 없음
- **응답** (`LibraryBookDetailResponse`):
  - `libraryBookId` (Long)
  - `book` (object `BookDetail`): `bookId` (Long), `isbn13` (String), `title` (String), `author` (String), `publisher` (String), `coverImageUrl` (String), `totalPages` (Integer)
  - `status` (ReadingStatus)
  - `startedAt` (LocalDate, nullable)
  - `finishedAt` (LocalDate, nullable)
  - `review` (object `ReviewSummary`, nullable — 리뷰 없으면 null):
    - `reviewId` (Long)
    - `rating` (byte)
    - `content` (String)
    - `createdAt` (LocalDateTime)
- **성공 상태**: 200

### PATCH /api/v1/library/{libraryBookId}/status

- **목적**: 서재 도서의 독서 상태 변경
- **인증**: 인증 필요 (`anyRequest().authenticated()`)
- **파라미터**:
  - `libraryBookId` (Long, path, 필수)
- **요청 본문** (`LibraryStatusUpdateRequest`, `@Valid`):
  - `status` (ReadingStatus enum, 필수 — `@NotNull` "독서 상태는 필수입니다.")
- **응답** (`LibraryStatusUpdateResponse`):
  - `libraryBookId` (Long)
  - `status` (ReadingStatus)
  - `startedAt` (LocalDate, nullable)
  - `finishedAt` (LocalDate, nullable)
- **성공 상태**: 200 (message "독서 상태가 변경되었습니다.")

### DELETE /api/v1/library/{libraryBookId}

- **목적**: 서재에서 도서 제거
- **인증**: 인증 필요 (`anyRequest().authenticated()`)
- **파라미터**:
  - `libraryBookId` (Long, path, 필수)
- **요청 본문**: 없음
- **응답**: `data` 없음 (`Void`) — message만 반환
- **성공 상태**: 200 (message "서재에서 도서가 제거되었습니다.")

### GET /api/v1/members/{userId}/library

- **목적**: 타 유저 서재 목록 조회 (공개/비공개 처리 포함)
- **인증**: 공개 (PERMIT_ALL에 `/api/v1/members/*/library` 포함; 로그인 시 `requestingUserId` 로 권한 판별 — 선택적 인증)
- **파라미터**:
  - `userId` (Long, path, 필수)
  - query:
    - `status` (ReadingStatus enum, 선택, `required=false`)
    - `cursor` (Long, 선택, `required=false`): 직전 마지막 libraryBookId
    - `limit` (int, 선택, 기본값 `20`)
- **요청 본문**: 없음
- **응답** (`UserLibraryResponse`):
  - `libraryVisibility` (LibraryVisibility — `PUBLIC`/`PRIVATE`)
  - `content` (array of `LibraryBookSummaryResponse` — PRIVATE 서재면 빈 배열):
    - `libraryBookId` (Long)
    - `book` (object `BookSummary`): `bookId` (Long), `isbn13` (String), `title` (String), `author` (String), `coverImageUrl` (String)
    - `status` (ReadingStatus)
    - `startedAt` (LocalDate, nullable)
    - `finishedAt` (LocalDate, nullable)
    - `hasReview` (boolean)
  - `nextCursor` (Long, nullable — 마지막 항목 libraryBookId; PRIVATE면 null)
  - `hasNext` (boolean — PRIVATE면 false)
  - `size` (int — PRIVATE면 0)
- **성공 상태**: 200

### GET /api/v1/library/me/wisdom-tower

- **목적**: 지혜의 탑 — 내가 완독한 도서 목록/총 개수 조회
- **인증**: 인증 필요 (`anyRequest().authenticated()`)
- **파라미터**: 없음
- **요청 본문**: 없음
- **응답** (`WisdomTowerResponse`):
  - `totalCount` (int)
  - `books` (array of `TowerItem`):
    - `libraryBookId` (Long)
    - `bookId` (Long)
    - `title` (String)
    - `finishedAt` (LocalDate, nullable)
- **성공 상태**: 200

---

## 3. 피드 · 팔로우 · 차단 · 댓글 · 알림 (Feed / Follow / Block / Comment / Notification)

### GET /api/v1/feed/following
- **목적**: 내가 팔로우한 사용자들의 리뷰 피드를 커서 기반으로 조회.
- **인증**: 인증 필요 (PERMIT_ALL 미포함 → `authenticated()`; `userDetails`를 null 체크 없이 사용).
- **파라미터 (query)**:
  - `cursor`: Long, optional, default 없음 — 직전 페이지 마지막 `feedId` (다음 페이지 시작 커서).
  - `limit`: int, optional, default `20`.
- **요청 바디**: 없음.
- **응답**: `200 OK`, `data` = `FeedListResponse`
  - `content`: `FeedItemResponse[]`
    - `feedId`: Long
    - `review`: object (`ReviewInfo`)
      - `reviewId`: Long
      - `user`: object — `userId`: Long, `nickname`: String, `profileImageUrl`: String
      - `book`: object — `bookId`: Long, `title`: String, `author`: String, `coverImageUrl`: String
      - `rating`: int
      - `content`: String
      - `quote`: String
      - `isSpoiler`: Boolean
      - `likeCount`: int
      - `commentCount`: int
      - `isLiked`: Boolean
      - `tags`: String[]
      - `createdAt`: LocalDateTime (ISO-8601)
  - `nextCursor`: Long (nullable; `hasNext`가 false면 null이며 NON_NULL로 제외됨) — 다음 요청의 `cursor`로 사용.
  - `hasNext`: boolean
  - `size`: int (반환된 content 개수)

### GET /api/v1/feed/recommend
- **목적**: 추천 알고리즘 기반 리뷰 피드 조회 (좋아요수 + reviewId 복합 커서).
- **인증**: 인증 필요.
- **파라미터 (query)**:
  - `cursorLike`: Integer, optional, default 없음 — 직전 페이지 마지막 아이템의 `likeCount` (복합 커서 1).
  - `cursorId`: Long, optional, default 없음 — 직전 페이지 마지막 아이템의 `reviewId` (복합 커서 2).
  - `limit`: int, optional, default `20`.
- **요청 바디**: 없음.
- **응답**: `200 OK`, `data` = `RecommendFeedResponse`
  - `content`: `RecommendItemResponse[]`
    - `reviewId`: Long
    - `user`: object — `userId`: Long, `nickname`: String, `profileImageUrl`: String
    - `book`: object — `bookId`: Long, `title`: String, `author`: String, `coverImageUrl`: String, `category`: String
    - `rating`: byte
    - `content`: String
    - `quote`: String
    - `isSpoiler`: boolean (`@JsonProperty("isSpoiler")`)
    - `likeCount`: int
    - `commentCount`: int
    - `isLiked`: boolean (`@JsonProperty("isLiked")`)
    - `tags`: String[]
    - `createdAt`: LocalDateTime
  - `nextCursorId`: Long (nullable; null이면 제외) — 다음 요청의 `cursorId`.
  - `nextCursorLike`: Integer (nullable; null이면 제외) — 다음 요청의 `cursorLike`.
  - `hasNext`: boolean
  - `size`: int
  - `recommendType`: String (`CONTENT_BASED` | `SOCIAL` | `MIXED` | `POPULAR`)

---

## Follow / Block (`/api/v1/users`)

### POST /api/v1/users/{userId}/follow
- **목적**: 특정 사용자를 팔로우.
- **인증**: 인증 필요.
- **파라미터 (path)**: `userId`: Long, required — 팔로우 대상 사용자 ID.
- **요청 바디**: 없음.
- **성공 상태**: `201 Created` (`@ResponseStatus(CREATED)`), message `"팔로우했습니다."`.
- **응답**: `data` = `FollowResponse`
  - `followId`: Long
  - `followingUserId`: Long — 팔로우된 대상 userId
  - `followerCount`: int — 대상의 팔로워 수
  - `followingCount`: int — 나의 팔로잉 수

### DELETE /api/v1/users/{userId}/follow
- **목적**: 특정 사용자 언팔로우.
- **인증**: 인증 필요.
- **파라미터 (path)**: `userId`: Long, required.
- **요청 바디**: 없음.
- **응답**: `200 OK`, message `"언팔로우했습니다."`, `data` = `UnfollowResponse`
  - `followerCount`: int — 대상의 팔로워 수
  - `followingCount`: int — 나의 팔로잉 수

### GET /api/v1/users/{userId}/followers
- **목적**: 특정 사용자의 팔로워 목록 조회.
- **인증**: 공개 (`GET_PERMIT_ALL`에 포함). 단, 로그인 시 `userDetails`로 관계 플래그가 채워지고, 비로그인 시 null 허용.
- **파라미터**:
  - (path) `userId`: Long, required.
  - (query) `cursor`: Long, optional, default 없음 — 직전 페이지 마지막 `userId`.
  - (query) `limit`: int, optional, default `20`.
- **요청 바디**: 없음.
- **응답**: `200 OK`, `data` = `FollowListResponse`
  - `content`: `FollowMemberResponse[]`
    - `userId`: Long
    - `nickname`: String
    - `profileImageUrl`: String
    - `bio`: String
    - `isFollowing`: Boolean — 내가 이 사람을 팔로우하는지
    - `isFollowedBy`: Boolean — 이 사람이 나를 팔로우하는지
  - `nextCursor`: Long (nullable; null이면 제외) — 다음 요청의 `cursor`.
  - `hasNext`: boolean
  - `size`: int

### GET /api/v1/users/{userId}/following
- **목적**: 특정 사용자가 팔로우하는 사용자 목록 조회.
- **인증**: 공개 (`GET_PERMIT_ALL`에 포함). 로그인 시 관계 플래그 반영, 비로그인 허용.
- **파라미터**:
  - (path) `userId`: Long, required.
  - (query) `cursor`: Long, optional, default 없음 — 직전 페이지 마지막 `userId`.
  - (query) `limit`: int, optional, default `20`.
- **요청 바디**: 없음.
- **응답**: `200 OK`, `data` = `FollowListResponse` (구조는 위 followers와 동일: `content[FollowMemberResponse]`, `nextCursor`(nullable), `hasNext`, `size`).

### POST /api/v1/users/{userId}/block
- **목적**: 특정 사용자 차단.
- **인증**: 인증 필요.
- **파라미터 (path)**: `userId`: Long, required.
- **요청 바디**: 없음.
- **성공 상태**: `201 Created` (`@ResponseStatus(CREATED)`), message `"사용자를 차단했습니다."`.
- **응답**: `data` = 없음 (`ApiResponse<Void>`; `data` 필드는 null → 제외).

### DELETE /api/v1/users/{userId}/block
- **목적**: 특정 사용자 차단 해제.
- **인증**: 인증 필요.
- **파라미터 (path)**: `userId`: Long, required.
- **요청 바디**: 없음.
- **응답**: `200 OK`, message `"차단이 해제되었습니다."`, `data` 없음 (`ApiResponse<Void>`).

### GET /api/v1/users/me/blocks
- **목적**: 내가 차단한 사용자 목록 조회.
- **인증**: 인증 필요 (`GET_PERMIT_ALL` 미포함; `userDetails`를 null 체크 없이 사용).
- **파라미터 (query)**:
  - `cursor`: Long, optional, default 없음.
  - `limit`: int, optional, default `20`.
- **요청 바디**: 없음.
- **응답**: `200 OK`, `data` = `BlockListResponse` (block 도메인 DTO — 본 작업 범위 외이나, 명세 일관성상 cursor/limit는 Long/int 동일).

---

## Comment (`/api/v1/reviews`)

### POST /api/v1/reviews/{reviewId}/comments
- **목적**: 리뷰에 댓글(또는 대댓글) 작성.
- **인증**: 인증 필요 (POST는 `GET_PERMIT_ALL` 영향 없음).
- **파라미터 (path)**: `reviewId`: Long, required.
- **요청 바디** (`CommentCreateRequest`, JSON):
  - `content`: String, **required** — `@NotNull("댓글 내용은 필수입니다.")`.
  - `parentCommentId`: Long, optional (nullable) — null이면 원댓글, 값이 있으면 대댓글.
- **성공 상태**: `201 Created` (`@ResponseStatus(CREATED)`), message `"댓글이 등록되었습니다."`.
- **응답**: `data` = `CommentCreateResponse`
  - `commentId`: Long
  - `reviewId`: Long
  - `user`: object — `userId`: Long, `nickname`: String, `profileImageUrl`: String
  - `content`: String
  - `parentCommentId`: Long (nullable; null이면 제외)
  - `likeCount`: int
  - `createdAt`: LocalDateTime

### GET /api/v1/reviews/{reviewId}/comments
- **목적**: 리뷰의 댓글 목록 조회 (대댓글 포함, 커서 기반).
- **인증**: 공개 (`GET_PERMIT_ALL`에 포함). 로그인 시 `isMine`/`isLiked` 반영, 비로그인 허용.
- **파라미터**:
  - (path) `reviewId`: Long, required.
  - (query) `cursor`: Long, optional, default 없음 — 직전 페이지 마지막 `commentId`.
  - (query) `limit`: int, optional, default `20`.
- **요청 바디**: 없음.
- **응답**: `200 OK`, `data` = `CommentListResponse`
  - `content`: `CommentResponse[]`
    - `commentId`: Long
    - `user`: object — `userId`: Long, `nickname`: String, `profileImageUrl`: String (소프트 삭제 시 `user` = null → 제외)
    - `content`: String (소프트 삭제 시 `"삭제된 댓글입니다"`)
    - `parentCommentId`: Long (nullable; null이면 제외)
    - `likeCount`: int
    - `isLiked`: Boolean
    - `isDeleted`: Boolean
    - `isMine`: Boolean
    - `isEdited`: Boolean
    - `createdAt`: LocalDateTime
    - `replies`: `ReplyResponse[]`
      - `commentId`: Long
      - `user`: object — `userId`: Long, `nickname`: String, `profileImageUrl`: String (소프트 삭제 시 null)
      - `content`: String (소프트 삭제 시 `"삭제된 댓글입니다"`)
      - `parentCommentId`: Long
      - `likeCount`: int
      - `isLiked`: Boolean
      - `isDeleted`: Boolean
      - `isMine`: Boolean
      - `isEdited`: Boolean
      - `createdAt`: LocalDateTime
  - `nextCursor`: Long (nullable; null이면 제외) — 다음 요청의 `cursor`.
  - `hasNext`: boolean
  - `size`: int

### PUT /api/v1/reviews/{reviewId}/comments/{commentId}
- **목적**: 댓글 수정.
- **인증**: 인증 필요 (PUT는 GET 공개 규칙 미적용).
- **파라미터 (path)**: `reviewId`: Long, required; `commentId`: Long, required.
- **요청 바디** (`CommentUpdateRequest`, JSON):
  - `content`: String, **required** — `@NotNull("댓글 내용은 필수입니다.")`.
- **응답**: `200 OK`, message `"댓글이 수정되었습니다."`, `data` = `CommentUpdateResponse`
  - `commentId`: Long
  - `content`: String
  - `updatedAt`: LocalDateTime

### DELETE /api/v1/reviews/{reviewId}/comments/{commentId}
- **목적**: 댓글 삭제 (소프트 삭제).
- **인증**: 인증 필요.
- **파라미터 (path)**: `reviewId`: Long, required; `commentId`: Long, required.
- **요청 바디**: 없음.
- **응답**: `200 OK`, message `"댓글이 삭제되었습니다."`, `data` 없음 (`ApiResponse<Void>`).

### POST /api/v1/reviews/{reviewId}/comments/{commentId}/likes
- **목적**: 댓글 좋아요.
- **인증**: 인증 필요.
- **파라미터 (path)**: `reviewId`: Long, required; `commentId`: Long, required.
- **요청 바디**: 없음.
- **성공 상태**: `201 Created` (`@ResponseStatus(CREATED)`), message `"좋아요를 눌렀습니다."`.
- **응답**: `data` = `CommentLikeResponse`
  - `commentId`: Long
  - `likeCount`: int

### DELETE /api/v1/reviews/{reviewId}/comments/{commentId}/likes
- **목적**: 댓글 좋아요 취소.
- **인증**: 인증 필요.
- **파라미터 (path)**: `reviewId`: Long, required; `commentId`: Long, required.
- **요청 바디**: 없음.
- **응답**: `200 OK`, message `"좋아요가 취소되었습니다."`, `data` = `CommentLikeResponse`
  - `commentId`: Long
  - `likeCount`: int

---

## Notification (`/api/v1/notifications`)

> 컨트롤러에 `@Validated` 적용 → path/query 파라미터 제약 검증 활성화.

### GET /api/v1/notifications
- **목적**: 내 알림 목록 조회 (불투명 String 커서 기반 페이지네이션).
- **인증**: 인증 필요.
- **파라미터 (query)**:
  - `cursor`: **String (opaque)**, optional, default 없음 — 서버가 발급한 불투명 커서 문자열(Long 아님). 다음 페이지 요청 시 직전 응답의 `nextCursor`를 그대로 전달.
  - `limit`: int, optional, default `20`, 제약 `@Min(1)`.
- **요청 바디**: 없음.
- **응답**: `200 OK`, `data` = `NotificationListResponse`
  - `content`: `NotificationItemResponse[]`
    - `notificationId`: Long
    - `type`: `NotificationType` (enum, 문자열로 직렬화)
    - `message`: String
    - `isRead`: boolean (`@JsonProperty("isRead")`)
    - `actor`: object (nullable; actor 없으면 null → 제외)
      - `userId`: Long
      - `nickname`: String
      - `profileImageUrl`: String
    - `reviewId`: Long (nullable; null이면 제외)
    - `commentId`: Long (nullable; null이면 제외)
    - `followId`: Long (nullable; null이면 제외)
    - `createdAt`: LocalDateTime
  - `nextCursor`: String (opaque, nullable; `hasNext` false면 null → 제외) — 다음 요청의 `cursor`.
  - `hasNext`: boolean
  - `size`: int

### PATCH /api/v1/notifications/{notificationId}/read
- **목적**: 단일 알림 읽음 처리.
- **인증**: 인증 필요.
- **파라미터 (path)**: `notificationId`: Long, required — 제약 `@Positive("유효하지 않은 알림 ID입니다.")`.
- **요청 바디**: 없음.
- **응답**: `200 OK`, message `"알림을 읽음 처리했습니다."`, `data` 없음 (`ApiResponse<Void>`).

### PATCH /api/v1/notifications/read-all
- **목적**: 내 모든 알림 일괄 읽음 처리.
- **인증**: 인증 필요.
- **파라미터**: 없음.
- **요청 바디**: 없음.
- **응답**: `200 OK`, message `"모든 알림을 읽음 처리했습니다."`, `data` 없음 (`ApiResponse<Void>`).

### GET /api/v1/notifications/unread-count
- **목적**: 미읽음 알림 개수 조회.
- **인증**: 인증 필요.
- **파라미터**: 없음.
- **요청 바디**: 없음.
- **응답**: `200 OK`, `data` = `UnreadCountResponse`
  - `unreadCount`: long

---

## 4. 장르 · 검색 · OCR · 신고 (Genre / Search / OCR / Report)

### GET /api/v1/genres
- **목적**: 전체 장르 목록 조회
- **인증**: 공개 (SecurityConfig `GET_PERMIT_ALL`에 `/api/v1/genres` 포함)
- **파라미터**: 없음
- **요청**: 없음
- **응답**: `200 OK`, `ApiResponse<GenreListResponse>`
  - `data.genres`: array of object
    - `genreId` (Long)
    - `name` (String) — 장르명(`genreName`)

---

### GET /api/v1/search
- **목적**: 책/사용자 통합 검색 (커서 기반 페이지네이션)
- **인증**: 공개 (`GET_PERMIT_ALL`에 `/api/v1/search` 포함). 단, 로그인 시 토큰의 사용자 기준으로 `users[].isFollowing`이 채워짐(비로그인 시 memberUserId=null)
- **파라미터 (query)**:
  - `query` (String, 필수) — 검색어
  - `type` (String, 선택, default `"all"`) — 검색 대상 구분 (예: `all`/`books`/`users`)
  - `cursor` (Long, 선택) — 다음 페이지 커서
  - `limit` (int, 선택, default `20`) — 페이지 크기
- **요청 바디**: 없음
- **응답**: `200 OK`, `ApiResponse<SearchResponse>`
  - `data.books`: `SearchPageResponse<BookSearchResult>`
    - `content`: array of object
      - `bookId` (Long)
      - `isbn13` (String)
      - `title` (String)
      - `author` (String)
      - `coverImageUrl` (String)
      - `averageRating` (Double, 소수점 1자리 반올림, null 가능)
      - `reviewCount` (Long)
    - `nextCursor` (Long, null 가능)
    - `hasNext` (boolean)
    - `size` (int)
  - `data.users`: `SearchPageResponse<UserSearchResult>`
    - `content`: array of object
      - `userId` (Long)
      - `nickname` (String)
      - `profileImageUrl` (String)
      - `bio` (String)
      - `followerCount` (int)
      - `isFollowing` (Boolean)
    - `nextCursor` (Long, null 가능)
    - `hasNext` (boolean)
    - `size` (int)
  - (검색 type에 따라 books 또는 users가 빈 페이지(`empty()`: content=[], nextCursor=null, hasNext=false, size=0)일 수 있음)

---

### POST /api/v1/ocr/extract-text
- **목적**: Base64 이미지에서 OCR로 텍스트 추출 (전체 텍스트 + 블록별 좌표)
- **인증**: 인증 필요 (PERMIT_ALL/GET_PERMIT_ALL에 없음 → `anyRequest().authenticated()`)
- **파라미터**: 없음
- **요청 바디**: `OcrExtractRequest` (JSON, `@Valid`)
  - `imageData` (String, 필수) — Base64 인코딩 이미지 데이터. `@NotBlank`, `@Size(max=7_000_000)` (약 5MB 초과 불가; 문자열 길이 기준 7,000,000자)
  - `imageFormat` (String, 필수) — 이미지 형식(jpg, png 등). `@NotBlank`
- **응답**: `200 OK`, `ApiResponse<OcrExtractResponse>`
  - `data.extractedText` (String) — 추출된 전체 텍스트
  - `data.fields`: array of `OcrTextField`
    - `text` (String) — 텍스트 블록 내용
    - `lineBreak` (boolean) — true면 블록 뒤 줄바꿈
    - `vertices`: array of `Vertex` — 이미지 내 위치 좌표
      - `x` (double)
      - `y` (double)

---

### POST /api/v1/reports
- **목적**: 리뷰/댓글 신고 접수
- **인증**: 인증 필요 (`anyRequest().authenticated()`; userDetails에서 memberUserId 추출)
- **파라미터**: 없음
- **요청 바디**: `ReportRequest` (JSON, `@Valid`)
  - `targetType` (enum `ReportTargetType`, 필수, `@NotNull`) — 허용값: `REVIEW`, `COMMENT`
  - `targetId` (Long, 필수, `@NotNull`) — 신고 대상 ID
  - `reason` (enum `ReportReason`, 필수, `@NotNull`) — 허용값: `SPOILER`, `SPAM`, `INAPPROPRIATE`, `COPYRIGHT`, `OTHER`
  - `description` (String, 선택) — `@Size(max=200)`
- **응답**: `201 Created` (`@ResponseStatus(HttpStatus.CREATED)`, message `"신고가 접수되었습니다."`), `ApiResponse<ReportResponse>`
  - `data.reportId` (Long)
  - `data.targetType` (enum `ReportTargetType`)
  - `data.targetId` (Long)
  - `data.reason` (enum `ReportReason`)
  - `data.createdAt` (LocalDateTime, ISO-8601)

---
