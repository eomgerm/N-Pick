---
name: N-Pick Glass
description: 사진 배경 위에 떠 있는 미니멀한 반투명 모듈을 위한 N-Pick 시각 디자인 시스템.
status: final
sources:
  - frontend/src/features/wireframes/entry.module.css
  - frontend/src/features/wireframes/login-shell.tsx
  - frontend/src/components/app-backdrop.tsx
updated: 2026-09-09
colors:
  glass-white: '#FFFFFF'
  glass-cool: '#F2F7FD'
  ink-primary: '#17243B'
  ink-muted: '#4D5462'
  accent: '#1473E6'
  action: '#0756C6'
  action-hover: '#06459D'
  error: '#BE2834'
  success-ink: '#166534'
  success-surface: '#F0FDF4'
  shadow-deep: '#233044'
  shadow-near: '#324259'
typography:
  family:
    fontFamily: 'Pretendard Variable, Apple SD Gothic Neo, Malgun Gothic, sans-serif'
  title:
    fontSize: 29px
    fontWeight: '750'
    lineHeight: 'normal'
    letterSpacing: -1px
  body:
    fontSize: 13px
    fontWeight: '400'
    lineHeight: '1.8'
  label:
    fontSize: 12px
    fontWeight: '700'
    lineHeight: 'normal'
  action:
    fontSize: 14px
    fontWeight: '700'
    lineHeight: 'normal'
  toast:
    fontSize: 13px
    fontWeight: '400'
    lineHeight: 'normal'
rounded:
  panel: 38px
  control: 17px
  icon: 16px
  full: 9999px
spacing:
  panel-inline: 40px
  panel-block-start: 32px
  panel-block-end: 28px
  panel-compact: 30px
  control-inline: 16px
  label-to-control: 10px
  field-to-field: 16px
  heading-gap: 14px
  back-to-heading: 15px
  content-to-form: 30px
  module-bottom-space: 40px
components:
  login-glass-panel:
    maxWidth: 470px
    background: 'linear-gradient(145deg, rgba(255,255,255,0.74), rgba(242,247,253,0.43))'
    backdrop: 'Tailwind backdrop-blur-2xl backdrop-saturate-150'
    border: '1px solid transparent'
    radius: '{rounded.panel}'
    padding: '{spacing.panel-block-start} {spacing.panel-inline} {spacing.panel-block-end}'
    shadow: '0 46px 90px rgba(35,48,68,0.24), 0 16px 30px rgba(50,66,89,0.16)'
  glass-input:
    minHeight: 56px
    background: 'rgba(255,255,255,0.52)'
    border: '1px solid transparent'
    radius: '{rounded.control}'
    paddingInline: '{spacing.control-inline}'
  glass-icon-tile:
    size: 46px
    background: 'rgba(255,255,255,0.48)'
    border: 'none'
    radius: '{rounded.icon}'
  primary-button:
    minHeight: 54px
    background: '{colors.action}'
    foreground: '{colors.glass-white}'
    border: 'none'
    radius: '{rounded.control}'
  success-glass-toast:
    top: 28px
    background: 'rgba(240,253,244,0.78)'
    foreground: '{colors.success-ink}'
    border: '1px solid transparent'
    radius: '{rounded.full}'
    backdrop: 'blur(18px) saturate(145%)'
    shadow: '0 14px 36px rgba(22,101,52,0.18)'
---

## Brand & Style

N-Pick Glass는 사진 배경 위에 작업 진입점을 하나의 떠 있는 모듈로 보여 주는 시각 언어다. 분위기는 미니멀하고 정제되어야 하며, 반투명 표면과 큰 곡률로 유리의 존재를 표현한다. 로그인은 사용자 제공 산 사진을 바탕으로 재구성한 하늘·설산·숲과 호수 레이어를 사용한다. 하늘의 푸른색과 숲의 녹색을 유지하고, 상단·하단에만 약한 밝기 보완을 허용한다. 필요한 본문 가독성은 모듈 자체의 반투명 표면이 담당한다.

입체감은 모듈 바깥의 넓고 부드러운 그림자에만 존재한다. 모듈 내부의 아이콘, 입력창과 버튼에는 3D 음영을 넣지 않는다. 흰색 외곽선도 장식으로 사용하지 않는다. 내부는 평평하고 기능적이며, 깊이는 배경과 패널 사이에서만 느껴져야 한다.

## Colors

- **Glass White** `{colors.glass-white}`는 알파값과 함께 패널·입력창·아이콘 타일의 재료로 사용한다. 불투명한 흰색 카드로 만들지 않는다.
- **Glass Cool** `{colors.glass-cool}`은 패널 그라디언트의 차가운 끝점이다. 유리의 색 변화는 미세해야 하며 별도의 장식색처럼 보여서는 안 된다.
- **Ink Primary** `{colors.ink-primary}`와 **Ink Muted** `{colors.ink-muted}`는 밝은 유리 위의 텍스트 계층을 만든다.
- **Accent** `{colors.accent}`는 아이콘과 입력 포커스에, **Action** `{colors.action}`은 주요 버튼에만 사용한다.
- **Error** `{colors.error}`는 입력 오류와 인증 실패 상태에만 사용한다. 장식 목적으로 사용하지 않는다.
- **Success Ink / Surface**는 로그아웃 완료처럼 비차단성 성공 알림에만 사용한다.

## Typography

기본 글꼴은 `{typography.family.fontFamily}`이다. 로그인 제목은 `{typography.title}`을 사용해 짧고 분명하게 보이게 한다. 설명과 알림은 `{typography.body}` 및 `{typography.toast}`를 사용한다. 특히 성공 토스트는 강조를 위해 굵게 만들지 않고 `400`을 유지한다.

랜딩의 한글 문구와 로그인·검색·검수 UI는 Pretendard Variable로 통일한다. [공식 v1.3.9](https://github.com/orioncactus/pretendard/tree/v1.3.9)의 WOFF2와 SIL OFL 라이선스를 `public/fonts/pretendard/`에 함께 보관하고, 루트 layout의 `next/font/local`에서 `45–920` 가변 굵기와 `display: swap`으로 제공한다. 전역 `--font-ui`와 Tailwind `font-sans`는 같은 글꼴을 사용하며 화면별 기본 서체를 다시 지정하지 않는다. 랜딩의 영문 브랜드 헤드라인은 Black Han Sans, 타임코드·코드의 고정폭 글꼴은 유지한다.

라벨은 `{typography.label}`을 사용하고 입력창 위에 항상 노출한다. 플레이스홀더는 라벨을 대체하지 않는다. 주요 버튼만 `{typography.action}`으로 강조한다.

## Layout & Spacing

로그인 모듈은 화면의 주 작업 영역 중앙에 놓고 최대 너비를 `470px`로 제한한다. 좌우 안전 여백은 데스크톱 `24px`, 작은 화면 `20px` 이상을 유지한다. 모듈 바깥 하단에는 `{spacing.module-bottom-space}`를 두며, 작은 화면에서는 `32px`로 줄인다.

패널의 기본 내부 여백은 `{spacing.panel-block-start} {spacing.panel-inline} {spacing.panel-block-end}`이다. `1100px` 이하에서는 네 방향 모두 `{spacing.panel-compact}`로 단순화한다.

필드 리듬은 다음 값을 고정한다.

- 라벨과 입력창: `{spacing.label-to-control}`
- 아이디 입력창과 비밀번호 라벨: `{spacing.field-to-field}`
- 역할 다시 선택과 제목 행: `{spacing.back-to-heading}`
- 제목 아이콘과 제목: `{spacing.heading-gap}`
- 설명과 폼: `{spacing.content-to-form}`

## Elevation & Depth

패널은 `{components.login-glass-panel.shadow}`의 두 겹 그림자로 배경에서 분리한다. 첫 그림자는 멀고 넓은 부유감을, 두 번째 그림자는 가까운 접지감을 담당한다.

내부 컨트롤에는 기본 그림자를 넣지 않는다. 입력 포커스나 오류처럼 상태 전달이 필요한 경우에만 링 또는 상태색을 사용한다. 내부 그림자, bevel, glossy highlight, hover lift는 금지한다.

## Shapes

큰 패널은 `{rounded.panel}`, 입력창과 버튼은 `{rounded.control}`, 아이콘 타일은 `{rounded.icon}`을 사용한다. 토스트는 내용 길이에 따라 늘어나는 완전한 pill 형태 `{rounded.full}`이다.

곡률은 계층에 따라 작아져야 한다. 내부 컨트롤의 모서리가 외부 패널보다 둥글어 보이거나, 서로 다른 컨트롤이 임의의 radius를 가져서는 안 된다.

## Components

### Login glass panel

`{components.login-glass-panel}`이 기준 모듈이다. 배경은 145도 방향의 반투명 흰색·쿨 화이트 그라디언트이며, 흰색 테두리는 사용하지 않는다. `1px transparent`는 오류 상태에서 크기 변화 없이 테두리 색만 바꾸기 위한 구조적 자리다.

### Glass input

`{components.glass-input}`은 아이콘과 실제 `input`을 하나의 표면 안에 배치한다. 기본 테두리는 투명하고, 포커스 시 `{colors.accent}` 테두리와 낮은 불투명도의 3px 링을 표시한다. 오류 시 `{colors.error}`로 바뀐다. 실제 `input`은 투명 배경을 유지한다.

브라우저 자동완성은 비활성화하지 않는다. Chrome 자동완성 배경을 조정할 때는 저장된 계정 기능과 가독성을 유지하면서 글래스 표면과 시각적으로 이어지게 한다.

### Glass icon tile

`{components.glass-icon-tile}`은 제목 아이콘을 담는다. 별도 테두리와 그림자는 없으며 반투명 면과 브랜드 accent만 사용한다.

### Primary button

`{components.primary-button}`은 모듈 안에서 유일한 불투명한 강한 면이다. 내부 입체효과나 그라디언트 없이 단색을 사용하고 hover에서는 `{colors.action-hover}`로만 전환한다.

### Success glass toast

`{components.success-glass-toast}`는 화면 상단 중앙의 비차단성 알림이다. 로그인 패널보다 작고 가벼운 단색 반투명 유리 표면을 사용하며, 테두리와 내부 그라디언트·음영은 넣지 않는다. 부유감은 모듈 바깥 그림자로만 표현한다. 본문은 보통 굵기이며 3초 동안 표시한 뒤 페이드아웃한다.

## Do's and Don'ts

| Do                                                            | Don't                                             |
| ------------------------------------------------------------- | ------------------------------------------------- |
| 로그인 배경 사진을 필터·흰색 베일 없이 그대로 사용한다.       | 배경 전체에 흰 레이어를 올려 색을 탁하게 만든다.  |
| 패널 외곽의 부드러운 그림자로만 깊이를 만든다.                | 입력창·아이콘·버튼에 내부 3D 음영을 추가한다.     |
| 기본 테두리는 없거나 투명하게 유지한다.                       | 카드·아이콘·입력창에 흰색 외곽선을 두른다.        |
| 포커스와 오류 상태에는 명확한 색상 링을 사용한다.             | 미니멀함을 이유로 상태 구분까지 제거한다.         |
| 한 화면에서 주 글래스 모듈을 하나만 강조한다.                 | 모든 작은 요소를 각각 떠 있는 유리 카드로 만든다. |
| 자동완성 기능을 유지하면서 브라우저 배경색을 조정한다.        | 외관을 맞추기 위해 `autocomplete`를 끈다.         |
| 작은 화면에서도 패널과 화면 가장자리 사이에 안전 여백을 둔다. | 패널을 뷰포트 가장자리에 붙인다.                  |
