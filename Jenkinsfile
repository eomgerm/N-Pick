// N-Pick CI/CD 파이프라인
// 설치·설정 절차와 트러블슈팅: infra/jenkins/README.md
//
// CI 와 CD 를 가르는 지점은 `--build` 다. CI 가 커밋 해시 태그로 이미지를 만들고
// CD 는 그 이미지를 그대로 띄운다. Container Registry 가 없지만 Jenkins 가 배포 대상
// EC2 위에서 돌아 호스트 Docker 데몬이 저장소 역할을 대신한다.

pipeline {
  agent any

  options {
    timestamps()
    timeout(time: 40, unit: 'MINUTES')
    buildDiscarder(logRotator(numToKeepStr: '30'))
    disableConcurrentBuilds()
    gitLabConnection('ssafy-gitlab')
  }

  triggers {
    // secretToken 은 Jenkins 전역 환경변수에서 읽는다 — 값은 서버에만 있고 레포에는 없다.
    //
    // 이 블록이 반드시 있어야 하는 이유: declarative 는 빌드마다 잡의 트리거 설정을 여기
    // 선언된 대로 재설정한다. 블록을 지우면 트리거가 삭제되고, secretToken 을 빼면 매 빌드마다
    // 토큰이 지워져 웹훅이 거부된다. infra/jenkins/README.md 13장 (2-2).
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
    // Jenkins 컨테이너 안에서 compose 를 돌리면 compose 가 보는 경로가 그대로 호스트
    // Docker 데몬에 전달된다. 상대 경로 볼륨(./infra/nginx/...)이 있으므로 마운트 지점을
    // 호스트 경로와 같게 맞춰야 한다. 값은 환경마다 다르므로 Jenkins 전역 환경변수
    // NPICK_DEPLOY_DIR 에서 읽는다 (Manage Jenkins → System → Global properties).
    DEPLOY_DIR = "${env.NPICK_DEPLOY_DIR ?: '/deploy'}"
  }

  stages {
    stage('Checkout') {
      steps {
        checkout scm
        script {
          // GIT_BRANCH 는 origin/dev 형태로 온다. fetch 인자로 쓰려면 접두사를 뗀다.
          env.DEPLOY_REF  = (env.GIT_BRANCH ?: 'dev').replaceFirst(/^origin\//, '')
          env.IMAGE_TAG   = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
          env.GIT_AUTHOR  = sh(returnStdout: true, script: 'git log -1 --pretty=%an').trim()
          env.GIT_SUBJECT = sh(returnStdout: true, script: 'git log -1 --pretty=%s').trim()
        }
        echo "커밋 ${env.IMAGE_TAG} — ${env.GIT_SUBJECT} (${env.GIT_AUTHOR})"
        echo "배포 ref: ${env.DEPLOY_REF}"
      }
    }

    stage('Sync deploy dir') {
      // /deploy 는 EC2 의 실제 배포 디렉터리다(호스트 마운트). 여기서 compose 를 실행해야
      // .env 4개와 같은 compose 프로젝트를 쓴다. 워크스페이스에서 up 하면 프로젝트 이름이
      // 달라져 별개 스택이 뜬다.
      //
      // 빌드 중인 브랜치를 그대로 배포한다. 머지 전에도 파이프라인 전체를 시험할 수 있고,
      // 머지 후에는 dev 가 넘어와 같은 경로로 동작한다.
      steps {
        withCredentials([usernamePassword(
          credentialsId: 'gitlab-repo-credentials',
          usernameVariable: 'GIT_USER',
          passwordVariable: 'GIT_PASS'
        )]) {
          sh 'infra/jenkins/sync-deploy.sh'
        }
      }
    }

    stage('Detect changes') {
      // 모노레포이므로 바뀐 앱만 빌드한다. 인프라 파일이 바뀌면 전체를 다시 만든다.
      steps {
        script {
          def changed = sh(returnStdout: true, script: 'infra/jenkins/changed-paths.sh').trim()
          def lines = changed ? changed.split('\n') as List : []
          def infra = lines.any { it.startsWith('compose.yaml') || it.startsWith('infra/') || it.startsWith('.env.example') }

          env.BUILD_BACKEND  = (infra || lines.any { it.startsWith('backend/') })  ? 'yes' : 'no'
          env.BUILD_FRONTEND = (infra || lines.any { it.startsWith('frontend/') }) ? 'yes' : 'no'
          env.BUILD_AI       = (infra || lines.any { it.startsWith('ai/') })       ? 'yes' : 'no'

          echo "변경 ${lines.size()}개 · 인프라 변경 ${infra}"
          echo "빌드 대상 — backend ${env.BUILD_BACKEND} · frontend ${env.BUILD_FRONTEND} · ai ${env.BUILD_AI}"
        }
      }
    }

    stage('Build images') {
      // 코드 빌드가 각 Dockerfile 의 build 스테이지 안에서 일어난다. 그래서 Jenkins 에
      // JDK·Node·uv 를 설치하지 않는다. 태그는 compose 의 ${IMAGE_TAG} 로 붙는다.
      when {
        expression { env.BUILD_BACKEND == 'yes' || env.BUILD_FRONTEND == 'yes' || env.BUILD_AI == 'yes' }
      }
      steps {
        script {
          def targets = []
          if (env.BUILD_BACKEND  == 'yes') targets << 'backend'
          if (env.BUILD_FRONTEND == 'yes') targets << 'frontend'
          if (env.BUILD_AI       == 'yes') targets << 'ai-worker'
          withEnv(["BUILD_TARGETS=${targets.join(' ')}"]) {
            sh 'infra/jenkins/build-images.sh'
          }
        }
      }
    }

    stage('Deploy') {
      // --build 를 쓰지 않는다. 앞 단계에서 만든 이미지를 그대로 띄운다.
      // 안 바뀐 앱은 실행 중 이미지에 새 태그를 붙여 compose 가 찾을 수 있게 한다.
      // backend 가 기동하면서 Flyway 가 마이그레이션을 실행한다.
      steps {
        sh 'infra/jenkins/deploy.sh'
      }
    }

    stage('Verify') {
      steps {
        sh 'infra/jenkins/verify.sh'
      }
    }
  }

  post {
    success {
      updateGitlabCommitStatus name: 'jenkins', state: 'success'
    }
    failure {
      // 이미지만 되돌린다. Flyway 마이그레이션은 롤백되지 않으므로 스키마 변경이 포함된
      // 배포가 실패하면 사람이 판단해야 한다.
      sh 'infra/jenkins/rollback.sh || true'
      updateGitlabCommitStatus name: 'jenkins', state: 'failed'
    }
    aborted {
      updateGitlabCommitStatus name: 'jenkins', state: 'canceled'
    }
    cleanup {
      // always 가 아니라 cleanup 이다. always 는 failure 보다 먼저 실행되어
      // rollback.sh 를 지워버린다(2026-09-07 실측).
      cleanWs()
    }
  }
}
