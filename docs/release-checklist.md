# CUEE Google Play 출시 체크리스트

## 저장소에서 자동 검증할 항목

- [x] `compileSdk`/`targetSdk` 36
- [x] 릴리스 R8 축소 및 리소스 축소
- [x] 디버그 방송 수신기는 디버그 빌드에서만 등록
- [x] 접근성 도구 메타데이터 및 서비스 설명
- [x] 접근성 정보 사용 고지, 명시적 동의, 거부 및 철회
- [x] 마이크 권한을 기능 설명 뒤 사용자 행동으로 요청
- [x] 앱 내부 개인정보 처리방침
- [x] 백업 및 기기 전송에서 로컬 설정·사용 결과 제외
- [x] detekt 정적 분석 통과 (`./gradlew :app:detekt`)
- [x] greenlight Play 정책 사전 점검: CRITICAL 0건, 유일한 HIGH는 접근성 서비스(소명 대상)
- [x] `./gradlew clean test lint bundleRelease` 통과
- [ ] 업로드 키로 AAB 서명 확인
- [ ] 최신 코레일+ 안전 E2E 통과
- [ ] Android 10·13·16 및 실제 삼성 기기에서 접근성·마이크·큰 글꼴 확인

## 출시 전 자동 점검 실행

```bash
# 정적 분석 + 테스트
./gradlew clean test :app:detekt

# Google Play 정책 사전 점검 (greenlight, brew install revylai/tap/greenlight)
./gradlew :app:playPreflight          # 소스 스캔
greenlight playscan --aab app/build/outputs/bundle/release/app-release.aab  # 병합 매니페스트/바이너리
```

접근성 서비스 HIGH는 코드로 없앨 수 없는 정책 **소명** 항목이다. 아래 "접근성 소명 통과 전략"을 따른다.

## 접근성 소명 통과 전략 (반려·정지 예방의 핵심)

- [ ] 스토어 설명 첫 문단에 대상 사용자(인지적 어려움/디지털 취약계층)와 해결하는 어려움을 명시 — 정책은 "설명만 읽어도 누가 왜 쓰는지 자명해야 한다"를 요구
- [ ] "예매 대행"이 아니라 "다음 누를 곳 안내, 사용자가 직접 탭"으로 프레이밍 (일반 음성비서·자동화 도구로 보이면 반려)
- [ ] Permission Declaration Form 작성: `docs/play-console-accessibility-declaration.md` 내용 사용, `isAccessibilityTool=true` 근거 포함
- [ ] 소명 영상: 앱 열기 → 접근성 켜기 → 실제 안내 → 동의/거부 흐름 → 결제 전 안전 종료 장면 필수 포함
- [ ] Data safety 폼: 화면·음성 원문 미저장, 서버 전송 없음을 정확히 선언


## 개발자 계정에서 진행할 항목

- [ ] 개인 또는 조직 계정 유형 결정
- [ ] Google Play 개발자 계정 생성, 비용 결제 및 본인 인증
- [ ] 패키지명 `com.cuee` 사용 가능 여부 확인
- [ ] 개발자명과 문의 이메일 확정
- [ ] 개인정보 처리방침의 `[대괄호 항목]`을 채우고 공개 HTTPS URL에 게시
- [ ] Play App Signing 활성화 및 업로드 인증서 백업
- [ ] 앱 액세스, 광고, 콘텐츠 등급, 타깃층, Data Safety 작성
- [ ] AccessibilityService 권한 신고와 실제 사용 영상 제출
- [ ] 스토어 아이콘, 기능 그래픽, 스크린샷과 설명 등록
- [ ] 내부 테스트 후 12명이 14일 연속 참여하는 비공개 테스트 진행
- [ ] 피드백과 Pre-launch report 문제 수정 후 프로덕션 액세스 신청

## 릴리스 키 설정

1. 업로드 키를 안전한 위치에 생성합니다.
2. `keystore.properties.example`을 `keystore.properties`로 복사합니다.
3. 실제 경로, 별칭과 비밀번호를 입력합니다. 이 파일과 키 파일은 Git에 올리지 않습니다.
4. `./gradlew clean bundleRelease`를 실행합니다.
5. `jarsigner -verify app/build/outputs/bundle/release/app-release.aab`로 서명을 확인합니다.

키와 비밀번호는 암호 관리자와 별도 복구 위치에 보관합니다. 저장소, README, 이슈, 로그에는 기록하지 않습니다.
