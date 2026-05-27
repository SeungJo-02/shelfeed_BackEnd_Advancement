<!--
  Pull Request Template — Shelfeed Backend
  PR 제목은 Conventional Commits 형식을 따라 주세요.
  예) feat(search): ES Nori 형태소 분석기 적용
  예) fix(auth): JWT refresh 토큰 만료 시 401 응답
-->

## 📌 PR 요약
<!-- 이 PR이 무엇을 하는지 1~2줄로 요약해 주세요. -->


---

## 🎯 변경 목적 / 배경
<!-- 왜 이 변경이 필요한지, 어떤 문제를 해결하는지 설명해 주세요. -->


---

## 🛠️ 변경 사항
<!-- 변경한 내용을 bullet 으로 정리. 파일/모듈 단위로 묶어 주세요. -->
- [ ] 
- [ ] 
- [ ] 

---

## 🔗 관련 이슈
<!-- 자동 close: "Closes #이슈번호" 한 줄로 입력하면 PR 머지 시 이슈가 자동으로 닫힙니다. -->
- Closes #
- Related to #

---

## 🧪 테스트 / 검증
<!-- 어떻게 테스트했는지, 어떤 시나리오로 검증되었는지 명시 -->
- [ ] 단위 테스트 추가/수정 (`./gradlew test`)
- [ ] 통합 테스트 통과 (`./gradlew integrationTest` 또는 수동 검증)
- [ ] 로컬 수동 검증 완료
- [ ] 부하 테스트 (k6) 영향 없음 — 검색/인증 변경 시 필수

**테스트 결과 / 스크린샷**
<!-- API 응답 캡처, k6 결과, 콘솔 로그 등 -->
<details>
<summary>증거 펼치기</summary>

```
여기에 결과 붙여넣기
```

</details>

---

## 📊 영향 범위 (Impact)

| 항목 | 영향 |
|------|------|
| Breaking Change | [ ] Yes / [ ] No |
| DB 마이그레이션 | [ ] Yes / [ ] No |
| 환경 변수 변경 | [ ] Yes / [ ] No |
| 외부 API 의존 변화 | [ ] Yes / [ ] No |
| 성능 영향 (P95 변화) | <!-- 예: -50ms / 변화 없음 --> |

---

## ✅ 셀프 체크리스트 (Definition of Done)
- [ ] 코드 컨벤션 / 린트 통과
- [ ] 빌드 성공 (`./gradlew build -x test` 최소)
- [ ] 테스트 전부 통과
- [ ] 새로 추가된 public API에 Swagger 문서 반영
- [ ] application*.yml / Secret 변경 시 README 및 운영 노트 갱신
- [ ] 로그/메트릭 누락 없음 (Tempo span, log level 적정)

---

## ⚠️ 리뷰어가 주의 깊게 봐줬으면 하는 부분
<!-- 설계상 트레이드오프, 임시 코드, TODO 등 -->


---

## 📸 스크린샷 / API 응답 (선택)
<!-- UI/응답 변화가 있는 경우 -->


---

<!--
🤖 자동화 안내
- PR 제목 prefix(feat/fix/refactor/...) → 자동 type 라벨
- 변경 파일 경로 → 자동 area 라벨
- 변경 라인 수 → 자동 size 라벨 (XS/S/M/L/XL)
- main 머지 시 → Slack 알림 + Release Drafter 노트 자동 갱신
-->
