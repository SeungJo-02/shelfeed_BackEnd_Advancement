# AWS SSM Parameter Store 마이그레이션 가이드

## 개요

현재 운영 시크릿은 `.env`(평문) + `me.paulschwarz:spring-dotenv`로 주입된다.
이 문서는 운영 환경에서 AWS SSM Parameter Store로 시크릿을 이관하기 위한 절차와 컨벤션을 정의한다.

`application-prod.yml`에 `optional:aws-parameterstore:/config/shelfeed/` import가 추가되어 있으므로,
SSM에 파라미터가 존재하면 자동으로 로드되어 기존 환경변수 값을 오버라이드한다.
`optional:` 접두사 덕분에 SSM 미구성 또는 IAM 권한 부재 시에도 부팅이 중단되지 않고
기존 `.env` 기반 환경변수 fallback이 그대로 동작한다.

---

## SSM 파라미터 경로 컨벤션

모든 파라미터는 `/config/shelfeed/<property-key>` 형식으로 저장한다.

- `<property-key>`는 Spring 프로퍼티 키와 1:1 매핑된다.
- 계층 구분자는 `.`(점)을 사용한다.
- 예: `/config/shelfeed/spring.datasource.password`

Spring Cloud AWS는 `/config/shelfeed/` 경로 아래의 파라미터를 `GetParametersByPath`로 일괄 조회하여
Spring Environment에 주입한다. 경로의 마지막 세그먼트가 프로퍼티 키로 변환된다.

---

## 시크릿 매핑표

| 환경변수 (현재 `.env`) | SSM 파라미터 경로 | application.yml 프로퍼티 키 |
|---|---|---|
| `JWT_SECRET` | `/config/shelfeed/jwt.secret` | `jwt.secret` |
| `GOOGLE_CLIENT_ID` | `/config/shelfeed/oauth2.google.client-id` | `oauth2.google.client-id` |
| `GOOGLE_CLIENT_SECRET` | `/config/shelfeed/oauth2.google.client-secret` | `oauth2.google.client-secret` |
| `GOOGLE_REDIRECT_URI` | `/config/shelfeed/oauth2.google.redirect-uri` | `oauth2.google.redirect-uri` |
| `ALADIN_API_KEY` | `/config/shelfeed/aladin.api.ttbkey` | `aladin.api.ttbkey` |
| `CLOVA_OCR_SECRET_KEY` | `/config/shelfeed/clova.ocr.secret-key` | `clova.ocr.secret-key` |
| `CLOVA_OCR_API_URL` | `/config/shelfeed/clova.ocr.api-url` | `clova.ocr.api-url` |
| `MAIL_USERNAME` | `/config/shelfeed/spring.mail.username` | `spring.mail.username` |
| `MAIL_PASSWORD` | `/config/shelfeed/spring.mail.password` | `spring.mail.password` |
| `ADMIN_EMAIL` | `/config/shelfeed/admin.email` | `admin.email` |
| `ADMIN_PASSWORD` | `/config/shelfeed/admin.password` | `admin.password` |
| `DB_URL` | `/config/shelfeed/spring.datasource.url` | `spring.datasource.url` |
| `DB_USERNAME` | `/config/shelfeed/spring.datasource.username` | `spring.datasource.username` |
| `DB_PASSWORD` | `/config/shelfeed/spring.datasource.password` | `spring.datasource.password` |

> 참고: `GOOGLE_CLIENT_ID`는 시크릿은 아니지만 일관성을 위해 SSM에 함께 관리할 수 있다.
> `MAIL_HOST`, `MAIL_PORT`, `MAIL_BCC` 등 비시크릿 값은 SSM 이관 대상에서 제외해도 무방하다.

---

## EC2 인스턴스 IAM 권한 요구사항

운영 EC2 인스턴스에 연결된 IAM 역할에 아래 정책이 필요하다.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "ssm:GetParametersByPath",
        "ssm:GetParameter",
        "ssm:GetParameters"
      ],
      "Resource": "arn:aws:ssm:<REGION>:<ACCOUNT_ID>:parameter/config/shelfeed/*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "kms:Decrypt"
      ],
      "Resource": "arn:aws:kms:<REGION>:<ACCOUNT_ID>:key/<KMS_KEY_ID>"
    }
  ]
}
```

- `<REGION>`: EC2가 위치한 리전 (예: `ap-northeast-2`)
- `<ACCOUNT_ID>`: AWS 계정 ID
- `<KMS_KEY_ID>`: SecureString 암호화에 사용한 KMS 키 ID (기본 `aws/ssm` 키 사용 시 KMS 정책 불필요)

---

## 남은 수동 단계 체크리스트

### (a) SSM에 SecureString 파라미터 생성

매핑표의 각 항목에 대해 AWS 콘솔 또는 CLI로 파라미터를 생성한다.

```bash
# 예시 (AWS CLI)
aws ssm put-parameter \
  --name "/config/shelfeed/spring.datasource.password" \
  --value "실제_DB_비밀번호" \
  --type SecureString \
  --region ap-northeast-2
```

모든 시크릿 항목은 반드시 `SecureString` 타입으로 저장한다.

### (b) EC2 IAM 역할에 SSM 읽기 권한 부여

- AWS 콘솔 → IAM → 역할 → EC2 인스턴스 프로파일 역할 선택
- 위 정책을 인라인 정책 또는 관리형 정책으로 연결
- 변경 사항은 인스턴스 재시작 없이 즉시 적용됨

### (c) 검증 후 prod yml의 `${ENV}` placeholder 제거 (cutover)

1. 운영 서버에서 애플리케이션 재시작
2. 로그에서 `Fetched config from aws-parameterstore` 확인
3. 엔드포인트 정상 동작 확인 (DB 연결, JWT 인증, 이메일 발송 등)
4. 검증 완료 후 `application-prod.yml`의 `${DB_PASSWORD}`, `${DB_URL}`, `${DB_USERNAME}` 등 placeholder를 제거

### (d) `.env`에서 해당 키 삭제 및 **실제 키 로테이션**

- cutover 검증 완료 후 `.env`에서 SSM으로 이관된 항목 삭제
- **DB 비밀번호, JWT 시크릿, OAuth 클라이언트 시크릿, OCR 키 등 모든 시크릿을 실제로 재발급/로테이션**
- 기존 값이 형상관리(git history, 로그 등)에 노출된 경우 반드시 로테이션 필요

---

## 현재 상태 (groundwork 완료)

- [x] `build.gradle.kts`: `spring-cloud-aws-starter-parameter-store:3.2.1` 의존성 추가
- [x] `application-prod.yml`: `spring.config.import: optional:aws-parameterstore:/config/shelfeed/` 추가
- [ ] SSM 파라미터 생성 (수동)
- [ ] IAM 권한 부여 (수동)
- [ ] 운영 검증 및 cutover (수동)
- [ ] 시크릿 로테이션 (수동)
