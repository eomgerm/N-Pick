// N-Pick CI 파이프라인 (스켈레톤)
// 설치·설정 절차와 트러블슈팅: infra/jenkins/README.md

pipeline {
  agent any

  options {
    timestamps()
    timeout(time: 20, unit: 'MINUTES')
    buildDiscarder(logRotator(numToKeepStr: '30'))
    disableConcurrentBuilds()
    gitLabConnection('ssafy-gitlab')   // Jenkins 관리 → System 의 연결 이름과 같아야 한다
  }

  triggers {
    // secretToken 은 Jenkins 전역 환경변수에서 읽는다 — 값은 Jenkins 서버에만 있고 레포에는 없다
    // (Manage Jenkins → System → Global properties → Environment variables 에 GITLAB_WEBHOOK_TOKEN).
    //
    // 이 블록이 반드시 있어야 하는 이유: declarative 는 빌드마다 잡의 트리거 설정을 여기 선언된
    // 대로 재설정한다. 블록을 지우면 트리거가 통째로 삭제되고, secretToken 을 빼면 매 빌드마다
    // 토큰이 지워져 웹훅이 403(anonymous is missing the Job/Build permission)으로 거부된다.
    // 둘 다 2026-09-01 에 실제로 겪은 증상이다. 자세한 내용은 infra/jenkins/README.md 13장 (2-2).
    //
    // dev 푸시에만 반응한다. branchFilterType 을 'All' 로 두면 어느 브랜치에 푸시하든 잡이
    // 깨어나는데, 잡은 Branch Specifier 가 가리키는 한 브랜치만 체크아웃한다. 그래서 푸시한
    // 브랜치와 실제로 빌드된 코드가 달라지고, 검증되지 않은 커밋에 초록불이 찍힌다.
    //
    // triggerOnMergeRequest 를 끈 이유: MR 이벤트로 빌드해도 Branch Specifier 때문에 MR의
    // 소스 브랜치가 아니라 dev 가 빌드된다. 같은 이유로 오해를 부르므로 켜지 않는다.
    // MR을 머지 전에 검사하려면 Multibranch Pipeline 으로 옮겨야 한다(부록 참고).
    gitlab(
      triggerOnPush: true,
      triggerOnMergeRequest: false,
      branchFilterType: 'NameBasedFilter',
      includeBranchesSpec: 'dev',
      ciSkip: true,
      secretToken: env.GITLAB_WEBHOOK_TOKEN
    )
  }

  environment {
    TZ = 'Asia/Seoul'
  }

  stages {
    stage('Checkout') {
      steps {
        checkout scm
        script {
          env.GIT_COMMIT_SHORT = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
          env.GIT_AUTHOR       = sh(returnStdout: true, script: 'git log -1 --pretty=%an').trim()
          env.GIT_SUBJECT      = sh(returnStdout: true, script: 'git log -1 --pretty=%s').trim()
        }
        echo """
        ─────────────────────────────────────────────
         브랜치 : ${env.gitlabBranch ?: env.BRANCH_NAME ?: 'N/A'}
         커밋   : ${env.GIT_COMMIT_SHORT} — ${env.GIT_SUBJECT}
         작성자 : ${env.GIT_AUTHOR}
         트리거 : ${currentBuild.getBuildCauses()*.shortDescription.join(', ')}
        ─────────────────────────────────────────────
        """.stripIndent()
      }
    }

    stage('Backend') {
      // TODO: 빌드 명령은 후속 티켓에서 채운다 → dir('backend') { sh './gradlew build -x test --no-daemon' }
      // backend/ 는 S15P21A501-13 으로 이미 들어와 있어 이 스테이지는 실행된다.
      when { expression { fileExists('backend') } }
      steps {
        echo 'BE 빌드 명령 미설정 — 후속 티켓에서 추가 예정'
      }
    }

    stage('Frontend') {
      // TODO(S15P21A501-14): FE 디렉터리가 생기면 → dir('frontend') { sh 'npm ci && npm run build' }
      // 디렉터리 이름은 FE 세팅 시점에 확정된 것으로 맞춘다.
      when { expression { fileExists('frontend') } }
      steps {
        echo '[skip] frontend/ 없음'
      }
    }
  }

  post {
    success { updateGitlabCommitStatus name: 'jenkins', state: 'success' }
    failure { updateGitlabCommitStatus name: 'jenkins', state: 'failed' }
    aborted { updateGitlabCommitStatus name: 'jenkins', state: 'canceled' }
    always  { cleanWs() }
  }
}
