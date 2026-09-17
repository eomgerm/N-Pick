# Jenkins CI 초기 세팅 (S15P21A501-22)

EC2에 Docker로 Jenkins를 올리고, GitLab push가 자동으로 빌드를 트리거하게 만드는 절차서다.

**근거 문서**: SSAFY 제공 `[CI/CD] Jenkins 설치 가이드` 의 **1. docker 방식 설치**.
1~6장은 가이드를 그대로 따르고(2장 Docker 설치는 추가), 7장부터(플러그인 추가·GitLab 연동·웹훅·파이프라인)가
이 프로젝트에서 추가한 부분이다.

관련 이슈: BE 초기 세팅(S15P21A501-13), FE 초기 세팅(S15P21A501-14)

> **명령은 전부 EC2 안에서 실행한다.** 로컬 PC가 아니다.

---

## 0. 시작 전에

이 작업으로 만들어지는 것:

| 결과물 | 위치 |
|---|---|
| Jenkins 컨테이너 | EC2, 포트 18080 |
| Jenkins 데이터(잡·설정·플러그인) | EC2 `/home/ubuntu/jenkins-data` |
| 파이프라인 정의 | 레포 루트 `Jenkinsfile` (커밋됨) |
| 관리자 계정 / GitLab 토큰 | **EC2 안에만 존재. 레포에 커밋하지 않는다** |

> **비밀값 취급 원칙.** 관리자 비밀번호와 GitLab 토큰은 Jenkins 웹 UI에서 입력하고 EC2에만 남긴다.
> 이 레포에는 어떤 형태로도 적지 않는다. 팀 공유가 필요하면 노션 비공개 페이지 등 비밀 채널을 쓴다.
> 실수로 커밋했다면 히스토리를 지우려 하기 전에 **토큰부터 폐기하고 재발급**한다. 그게 더 빠르고 확실하다.

### EC2 접속

컨설턴트 공지 기준. `<팀ID>`는 우리 팀 ID(예: `a501`)로 바꿔 넣는다.

```bash
ssh -i J15<팀ID>T.pem ubuntu@j15<팀ID>.p.ssafy.io
```

| 항목 | 값 |
|---|---|
| 도메인 | `j15<팀ID>.p.ssafy.io` |
| pem 키 | `J15<팀ID>T.pem` |
| 계정 | `ubuntu` |
| 초기 방화벽 | **22번(SSH)만 허용** |
| 재부팅 | `sudo reboot` |
| 제공 기간 | 특화 PJT 종료 시 (종료 후 7일 내 삭제) |

### 쓰면 안 되는 명령어

컨설턴트가 명시한, **오사용 시 EC2 접속 불능 가능성이 높은** 명령어들이다.
이 절차서에는 이 명령이 하나도 없다 — 진행 중 어디선가 이걸 쳐야 할 것 같으면 뭔가 잘못된 것이니 멈추고 확인한다.

```
sudo iptables    sudo chmod      sudo poweroff
sudo shutdown    sudo halt       sudo init       sudo rm -rf /
```

> 방화벽은 `iptables` 대신 **`ufw`만** 쓴다(1장). 권한 문제는 `chmod` 대신 컨테이너 설정으로 푼다.

---

## 1. 포트 접근 제한 (ufw)

> **전제 1: 팀은 AWS 콘솔 접근 권한이 없다.** 보안 그룹은 손댈 수 없고 **ufw가 유일한 방화벽**이다.
> 이슈 제약은 "외부 노출 포트는 **보안 그룹으로** 팀 접근만 허용"이지만, 수단만 다르고
> (보안 그룹 → ufw) **접근 제한이라는 목적은 동일하게 달성한다.** MR 설명에 이 대체 사실을 적는다.
>
> **전제 2: 발급 시점에 22번(SSH)만 열려 있다.** (컨설턴트 공지)
>
> **전제 3 (2026-09-07 정정): ufw 로 Docker publish 포트를 제한할 수 없다.**
> Docker 가 `-p` 로 포트를 열면 자체 NAT/forwarding 규칙을 추가하고, 그 순서 때문에 ufw 의
> 일반 incoming 규칙이 적용되지 않는다. 외부 IP 두 곳에서 `:8080` 에 접속해 Jenkins 로그인
> 화면을 확인했다 — `ufw allow from <GitLab IP> to any port 8080` 규칙이 있는 상태였다.
> 포트 번호를 옮겨도 같다.
>
> 그래서 아래 1-2·1-3 의 ufw 규칙은 **효과가 없다.** 유효한 통제는 바인딩 주소이며,
> `127.0.0.1` 로 퍼블리시하고 nginx 를 앞에 둔다. 절차는 3장을 따른다.
> `sudo iptables` 로 `DOCKER-USER` 체인을 쓰는 방법은 SSAFY 금지 명령이라 쓰지 않는다.

### 1-1. 현재 상태 확인

```bash
sudo ufw status numbered
```

22번만 보이는 게 정상이다.

### 1-2. GitLab 웹훅용 한 줄만 추가 (권장)

**사람은 18080을 열지 않고 SSH 터널로 접속한다.** 그러면 추가할 규칙은 이 한 줄뿐이다.

```bash
GITLAB_IP=$(getent hosts lab.ssafy.com | awk '{print $1}' | head -1)
echo "GitLab IP: $GITLAB_IP"
sudo ufw allow from "$GITLAB_IP" to any port 18080 proto tcp
sudo ufw status numbered
```

팀원은 각자 이렇게 접속한다.

```bash
ssh -i J15<팀ID>T.pem -L 18080:localhost:18080 ubuntu@j15<팀ID>.p.ssafy.io
# 터널을 띄운 뒤 브라우저에서 http://localhost:18080
```

| 이 방식의 이점 | |
|---|---|
| 팀원 공인 IP를 모아둘 필요가 없다 | 6명 IP 수집 + ufw 6줄 관리가 사라진다 |
| 팀원 IP가 바뀌어도 영향이 없다 | 집 인터넷은 공인 IP가 자주 바뀐다 |
| 18080이 GitLab에게만 열린다 | 노출면이 최소가 된다 |

단점은 접속할 때마다 터널 명령을 한 번 더 쳐야 하는 것뿐이다.

> **주의**: 이때 Jenkins의 URL 설정(5장)은 여전히 `http://j15<팀ID>.p.ssafy.io:18080` 이다.
> 브라우저로는 `localhost:18080`으로 보지만, GitLab이 웹훅을 보낼 주소는 EC2의 실제 도메인이다.
> 이걸 `localhost`로 적으면 GitLab에 찍히는 빌드 링크가 깨진다.

### 1-3. 대안 — 팀원 IP를 직접 허용

터널이 번거로우면 팀원 IP를 열어도 된다. 각자 https://ifconfig.me 에서 공인 IP를 확인해 공유한다.

```bash
# 팀원별로 한 줄씩
sudo ufw allow from <팀원-공인-IP> to any port 18080 proto tcp
sudo ufw status numbered
```

> **`sudo ufw allow 18080` (소스 없이)은 쓰지 않는다.** 컨설턴트 공지의 포트 추가 예시가 이 형태지만,
> 그건 전체 공개라 이슈 제약("팀 접근만 허용")을 위반한다. Jenkins를 인터넷에 그대로 열면
> 크리덴셜 스캐닝 대상이 된다.
>
> 실수로 넣었다면 **먼저 지운다.** ufw는 위에서부터 첫 매칭 규칙을 적용하므로, 전체 허용 규칙이
> 위에 남아 있으면 아래 IP 제한 규칙이 아무 의미가 없다.
> ```bash
> sudo ufw status numbered
> sudo ufw delete <번호>
> ```

### 1-4. GitLab IP는 왜 반드시 열어야 하는가

웹훅은 팀원 브라우저가 아니라 **GitLab 서버**가 보내는 HTTP 요청이다. 사람 쪽만 해결하면
웹 UI는 보이는데 푸시해도 빌드가 안 걸리고, 원인을 엉뚱한 데서 찾게 된다.

DNS로 나온 IP와 실제 웹훅 발신 IP가 다를 수 있다. 10장의 웹훅 Test가 타임아웃되면 아래로
실제 발신 IP를 확인해 그 주소를 허용한다.

```bash
sudo docker logs jenkins 2>&1 | grep -i 'project/' | tail
```

---

## 2. Docker 설치

> SSAFY 가이드는 "docker가 이미 설치되어 있다는 가정"으로 시작하지만, **발급된 EC2에는 안 깔려 있다**
> (2026-08-31 실측: Ubuntu 24.04.4 LTS, docker 미설치). 먼저 설치한다.
> 가이드가 안내한 공식 문서(https://docs.docker.com/engine/install/ubuntu/) 방식이다.

```bash
# 1) apt 저장소 등록에 필요한 것들
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg

# 2) Docker GPG 키
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

# 3) 저장소 추가
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable"   | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

# 4) 설치
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

설치 확인:

```bash
sudo docker --version
sudo systemctl is-active docker      # active 여야 한다
```

> **`sudo chmod a+r` 는 컨설턴트의 금지 명령(`sudo chmod`)과 겹쳐 보이지만 다른 경우다.**
> 금지 경고는 시스템 디렉터리나 홈 디렉터리 권한을 바꿔 SSH 인증이 깨지는 상황을 막으려는 것이고,
> 여기서는 방금 내려받은 GPG 키 파일 하나에 읽기 권한을 주는 것이다(Docker 공식 문서의 표준 절차).
> **`~/.ssh`, `/home/ubuntu`, `/etc` 전체 같은 대상에는 절대 chmod 하지 않는다.**

> 이후 절차는 전부 `sudo docker ...` 로 실행한다. `ubuntu` 계정을 docker 그룹에 넣어
> sudo 없이 쓰는 방법도 있지만(`sudo usermod -aG docker ubuntu` + 재로그인), 이 절차서는
> 가이드와 동일하게 `sudo`를 붙이는 쪽으로 통일한다.

---

## 3. Jenkins 컨테이너 생성 및 기동

```bash
cd /home/ubuntu && mkdir jenkins-data
```

**CD 를 쓰는 환경은 아래 이미지와 마운트로 만든다** (S15P21A501-132).

```bash
sudo docker build -t npick/jenkins:lts --build-arg DOCKER_GID=$(getent group docker | cut -d: -f3) infra/jenkins
```

```bash
sudo docker run -d --name jenkins --network npick_default -p 127.0.0.1:18080:8080 -v /home/ubuntu/jenkins-data:/var/jenkins_home -v /var/run/docker.sock:/var/run/docker.sock -v /home/ubuntu/S15P21A501:/home/ubuntu/S15P21A501 -e JENKINS_OPTS=--prefix=/jenkins --restart=unless-stopped npick/jenkins:lts
```

| 옵션 | 이유 |
|---|---|
| `npick/jenkins:lts` | docker CLI·buildx·compose plugin 을 넣은 이미지. `infra/jenkins/Dockerfile` |
| `--network npick_default` | nginx 가 `jenkins:8080` 으로 찾을 수 있게 한다 |
| `-p 127.0.0.1:18080:8080` | 외부에 열지 않는다. nginx 장애 시 SSH 터널용 예비 경로 |
| `-v /var/run/docker.sock` | 호스트 Docker 데몬으로 빌드·배포를 실행한다 |
| `-v <배포경로>:<같은 경로>` | **양쪽 경로가 같아야 한다.** compose 가 보는 경로가 그대로 데몬에 전달되므로, 다르면 상대 경로 볼륨 마운트가 실패한다 |
| `JENKINS_OPTS=--prefix=/jenkins` | nginx 서브패스 노출용. 없으면 정적 리소스 경로가 깨진다 |

그리고 Jenkins 전역 환경변수에 배포 경로를 등록한다 — `Jenkinsfile` 이 이 값을 읽는다.

```
Manage Jenkins → System → Global properties → Environment variables
  NPICK_DEPLOY_DIR = /home/ubuntu/S15P21A501
```

**CD 없이 Jenkins 만 쓰는 환경**은 아래로도 충분하다.

```bash
sudo docker run -d -p 127.0.0.1:18080:8080 -v /home/ubuntu/jenkins-data:/var/jenkins_home --restart=unless-stopped --name jenkins jenkins/jenkins:lts
```

> **호스트 포트는 18080이다** (S15P21A501-151). 8080은 애플리케이션 서버 몫이라
> `compose.yaml`의 `BACKEND_PORT` 기본값과 충돌한다. 컨테이너 내부 포트는 8080 그대로 두고
> 퍼블리시만 옮긴다.
>
> **이미 8080으로 돌고 있다면** 아래로 옮긴다. 잡·설정·플러그인은 `/home/ubuntu/jenkins-data`
> 볼륨에 있으므로 컨테이너를 지워도 유실되지 않는다.
>
> ```bash
> sudo docker rm -f jenkins
> sudo docker run -d -p 18080:8080 -v /home/ubuntu/jenkins-data:/var/jenkins_home --restart=unless-stopped --name jenkins jenkins/jenkins:lts
> GITLAB_IP=$(getent hosts lab.ssafy.com | awk '{print $1}' | head -1)
> sudo ufw allow from "$GITLAB_IP" to any port 18080 proto tcp
> sudo ufw status numbered   # 8080 규칙 번호를 확인해서 지운다
> sudo ufw delete <번호>
> ```
>
> 컨테이너를 옮긴 뒤 **두 곳을 같이 고쳐야 한다.** 안 고치면 웹훅이 끊긴다.
> 1. Jenkins → Manage Jenkins → System → **Jenkins URL** 을 `:18080` 으로 (5장·10장)
> 2. GitLab → Settings → Webhooks → URL 을 `:18080` 으로 (10장)

> **`--restart=unless-stopped` 는 SSAFY 가이드 명령에 없는 것을 추가한 것이다.** 이게 없으면
> EC2를 재부팅하거나 docker 데몬이 재시작될 때 Jenkins가 자동으로 올라오지 않는다. 그 사이
> 들어오는 웹훅은 전부 실패하고, 누군가 손으로 `sudo docker start jenkins` 를 해줘야 한다.
>
> 이미 옵션 없이 만든 컨테이너라면 다시 만들 필요 없이 한 줄로 바꿀 수 있다.
> ```bash
> sudo docker update --restart=unless-stopped jenkins
> ```
> ```bash
> sudo docker inspect -f '{{.HostConfig.RestartPolicy.Name}}' jenkins
> ```
> `unless-stopped` 가 나오면 된다. `always` 와 달리 사람이 일부러 `docker stop` 한 것은
> 재부팅 때 되살리지 않는다.

초기 관리자 비밀번호가 로그에 찍힌다. **5장에서 쓰므로 복사해 둔다.**

```bash
sudo docker logs jenkins
```

```
Jenkins initial setup is required. An admin user has been created and a password generated.
Please use the following password to proceed to installation:

2f81e36371c1470388a363ff85b261f4
```

환경 설정을 먼저 바꿔야 하므로 여기서 **컨테이너를 멈춘다.**

```bash
sudo docker stop jenkins
sudo docker ps -a
```

`STATUS`가 `Exited`인지 확인한다.

---

## 4. 업데이트 미러 교체 (매우 중요)

> **이 단계를 건너뛰면 플러그인 설치가 실패한다.**
> SSAFY 가이드가 명시한 필수 단계다 — 기본 미러(`updates.jenkins.io`)가 접속되지 않는 현상이 있어
> 특정 미러로 바꿔야 한다. 5장에서 "Install suggested plugins"가 줄줄이 실패하면 원인은 대부분 여기다.

> 명령은 `cd` 없이 **절대 경로**로 쓴다. 여러 줄을 한 번에 붙여넣을 때 줄바꿈이 누락되면
> 앞 명령과 뒤 명령이 붙어버리는 일이 있어(2026-08-31 실제 발생), 한 줄씩 실행하는 편이 안전하다.

Jenkins가 부팅하며 만든 대상 파일이 있는지 먼저 확인한다. 없으면 3장의 기동이 제대로 안 된 것이다.

```bash
ls -l /home/ubuntu/jenkins-data/hudson.model.UpdateCenter.xml
```

미러 검증에 필요한 CA 파일을 받는다.

```bash
mkdir -p /home/ubuntu/jenkins-data/update-center-rootCAs
```

```bash
wget https://cdn.jsdelivr.net/gh/lework/jenkins-update-center/rootCA/update-center.crt -O /home/ubuntu/jenkins-data/update-center-rootCAs/update-center.crt
```

`saved [1212/1212]` 처럼 크기가 찍혀야 한다. `0`이면 실패다.

업데이트 사이트 URL을 교체한다.

```bash
sudo sed -i 's#https://updates.jenkins.io/update-center.json#https://raw.githubusercontent.com/lework/jenkins-update-center/master/updates/tencent/update-center.json#' /home/ubuntu/jenkins-data/hudson.model.UpdateCenter.xml
```

**바뀌었는지 눈으로 확인한다.** 이 장에서 가장 중요한 확인이다.

```bash
cat /home/ubuntu/jenkins-data/hudson.model.UpdateCenter.xml
```

```xml
<?xml version='1.1' encoding='UTF-8'?>
<sites>
  <site>
    <id>default</id>
    <url>https://raw.githubusercontent.com/lework/jenkins-update-center/master/updates/tencent/update-center.json</url>
  </site>
</sites>
```

재기동한다. **필수** — 재기동해야 새 미러에서 플러그인 목록을 받아온다.

```bash
sudo docker restart jenkins
```

30초쯤 기다린 뒤, **새 미러가 실제로 적용됐는지 확인한다.** 이게 이 장의 최종 검증이다.

```bash
grep -o 'https://[^"]*\.hpi' /home/ubuntu/jenkins-data/updates/default.json | head -3
```

`updates/default.json` 은 Jenkins가 UpdateCenter.xml의 주소에서 받아 캐시하는 플러그인 목록이고,
그 안에 플러그인별 실제 다운로드 URL이 들어 있다. 여기가 새 미러를 가리켜야 교체가 먹은 것이다.

```
https://mirrors.cloud.tencent.com/jenkins/plugins/...      ← 적용됨
https://updates.jenkins.io/download/plugins/...            ← 아직 구 미러
```

구 미러로 나오면 캐시가 교체 전에 만들어진 것이다. 지우고 재기동해 다시 받게 한다.

```bash
sudo rm -f /home/ubuntu/jenkins-data/updates/default.json
```

```bash
sudo docker restart jenkins
```

> **로그로 확인하려 하지 말 것.** `docker logs | grep "check updates server"` 는 신뢰할 수 없다.
> (1) `docker logs`는 컨테이너 전체 이력을 보여줘서 교체 **전** 기록이 잡히고,
> (2) Jenkins가 이 경로에서 항상 그 로그를 남기지도 않는다.
> 실제로 2026-08-31 진행 중 교체 성공했는데도 해당 로그가 안 찍혀 혼란이 있었다.

다른 미러 목록: https://github.com/lework/jenkins-update-center

---

## 5. 초기 설정 (웹 마법사)

브라우저에서 `http://localhost:18080` 접속. (1-2의 SSH 터널을 띄운 상태여야 한다. 1-3 방식이면 `http://j15<팀ID>.p.ssafy.io:18080`)

1. **Unlock Jenkins** — 3장에서 복사한 초기 비밀번호 입력
2. **Customize Jenkins** — `Install suggested plugins` 클릭
3. **Create First Admin User** — 관리자 계정 생성
   - 비밀번호는 12자 이상. `openssl rand -base64 24` 로 만들어 쓰면 편하다
   - **이 계정 정보는 레포에 적지 않는다** (0장)
4. **Instance Configuration** — Jenkins URL을 **`http://j15<팀ID>.p.ssafy.io:18080`** 으로 설정
   - 기본값이 맞아 보여도 확인한다. 틀리면 GitLab에 찍히는 빌드 링크가 엉뚱한 주소가 된다

---

## 6. 보안 설정 확인 (필수)

Jenkins에는 GitLab 토큰과 서버 접근 권한이 쌓인다. 설정이 느슨하면 그대로 침해 경로가 된다.

### 6-1. 설정 파일

```bash
vi /home/ubuntu/jenkins-data/config.xml
```

아래 두 값이 `true`인지 확인한다. 아니면 고치고 `sudo docker restart jenkins`.

```xml
<useSecurity>true</useSecurity>
...
<securityRealm class="hudson.security.HudsonPrivateSecurityRealm">
    <disableSignup>true</disableSignup>
```

### 6-2. 웹 UI

**Jenkins 관리 → Security**

| 항목 | 값 |
|---|---|
| Security Realm | Jenkins' own user database |
| 사용자의 가입 허용 | **체크 해제** |
| Authorization | Logged-in users can do anything |
| Allow anonymous read access | **체크 해제** |

---

## 7. 추가 플러그인 설치

`Install suggested plugins`로는 **GitLab 연동과 Docker 사용에 필요한 플러그인이 안 깔린다.** 직접 설치한다.

**Jenkins 관리 → Plugins → Available plugins** 에서 검색해 설치:

| 플러그인 | 왜 필요한가 |
|---|---|
| **GitLab** | 웹훅 트리거(`/project/...` 엔드포인트), 빌드 결과를 GitLab 커밋 상태로 리포트 |
| **Docker Pipeline** | 파이프라인에서 `docker` 사용 (BE/FE 빌드를 컨테이너로 돌릴 때) |

이슈가 요구한 4종 중 **Git / Pipeline은 suggested에 포함**되어 이미 깔려 있다.
설치 후 "Restart Jenkins when installation is complete and no jobs are running"을 체크한다.

> 설치가 계속 실패하면 4장의 미러 설정을 다시 확인한다. 그래도 안 되면 13장 트러블슈팅 (2).

---

## 8. GitLab 연동

### 8-1. Personal Access Token 발급

`lab.ssafy.com` → 프로필 → **Preferences** → **Access Tokens** → Add new token

- **scopes**: `api`, `read_repository`
- **만료일**: 프로젝트 종료일 이후로 — 만료되면 웹훅은 살아 있는데 커밋 상태 리포트만 조용히 죽는다
- 토큰 값은 생성 직후 **한 번만** 보인다. 바로 복사할 것

### 8-2. Jenkins에 Credentials 등록

**Jenkins 관리 → Credentials → System → Global credentials → Add Credentials**

2개를 만든다.

| # | Kind | ID | 값 | 용도 |
|---|---|---|---|---|
| 1 | **GitLab API token** | `gitlab-api-token` | API token = 발급한 PAT | 빌드 결과를 GitLab에 리포트 |
| 2 | **Username with password** | `gitlab-repo-credentials` | Username = lab.ssafy.com 로그인 ID<br>Password = 발급한 PAT | 저장소 clone |

> SSH 키 대신 PAT를 쓰는 이유: EC2에 키를 따로 배치·관리할 필요가 없고, GitLab에서 토큰만
> 폐기하면 접근이 즉시 끊긴다. EC2의 `~/.ssh/authorized_keys`는 건드리지 않는 게 안전하다(부록).

### 8-3. GitLab 서버 연결

**Jenkins 관리 → System → GitLab**

| 항목 | 값 |
|---|---|
| Connection name | `ssafy-gitlab` |
| GitLab host URL | `https://lab.ssafy.com` |
| Credentials | `gitlab-api-token` |

**Test Connection**이 `Success`여야 다음으로 간다.

> Connection name은 반드시 `ssafy-gitlab` 로 한다. 레포의 `Jenkinsfile`이 이 이름을 참조한다.
> 다른 이름을 쓰면 빌드가 실패하므로, 바꾸려면 `Jenkinsfile`의 `gitLabConnection` 값도 같이 고쳐야 한다.

### 8-4. 웹훅 토큰용 전역 환경변수

웹훅 인증에 쓸 Secret token 을 Jenkins 전역 환경변수에 넣는다. `Jenkinsfile` 은 이 **이름만**
참조하므로 값이 레포에 들어가지 않는다.

토큰 값 생성:

```bash
openssl rand -hex 24
```

**Jenkins 관리 → System → Global properties** → ☑ `Environment variables` → `Add`

| 칸 | 값 |
|---|---|
| Name | `GITLAB_WEBHOOK_TOKEN` |
| Value | 위에서 생성한 값 |

**Save**. 이 값은 10장의 GitLab 웹훅에도 그대로 넣는다.

> **왜 잡 UI의 `Generate` 버튼을 쓰지 않는가.** declarative 는 빌드마다 잡의 트리거 설정을
> `Jenkinsfile` 선언대로 재설정한다. UI에서 Generate한 토큰은 첫 빌드에서 지워진다.
> 전역 환경변수에 두면 매 빌드마다 같은 값이 다시 주입되어 유지된다.
> 트리거에 복사될 때 Jenkins 가 암호화해 보관한다(`<secretToken>{AQAAAB...}</secretToken>`).
>
> Global properties 의 원본 값은 Jenkins 설정 파일에 평문으로 남는다. 서버 접근 권한자만 볼 수
> 있으므로 실용상 문제는 없지만, 레포에 커밋하지 않는다는 제약은 이 방식으로 충족된다.

---

## 9. 샘플 파이프라인 잡 생성

**New Item** → 이름 `npick-ci` → **Pipeline** → OK

| 항목 | 값 |
|---|---|
| Build Triggers | ☑ **Build when a change is pushed to GitLab** |
| └ **Advanced** → Secret token | **비워둔다** — 8-4의 환경변수에서 자동 주입된다 |
| Pipeline → Definition | **Pipeline script from SCM** |
| SCM | Git |
| Repository URL | `https://lab.ssafy.com/s15-ai-image-sub1/S15P21A501.git` |
| Credentials | `gitlab-repo-credentials` |
| Branch Specifier | `*/<작업 브랜치명>` (초기 검증용 — 아래 참고) |
| Script Path | `Jenkinsfile` |

> **Secret token 은 손대지 않는다.** `Jenkinsfile` 의 `triggers` 블록이 8-4의 환경변수 값으로
> 채워준다. 아래 수동 빌드를 한 번 돌린 뒤 저장 여부를 확인한다.
>
> ```bash
> sudo grep -c secretToken /home/ubuntu/jenkins-data/jobs/npick-ci/config.xml
> ```
>
> `1` 이상이어야 한다. `0` 이면 8-4의 환경변수 이름이 틀렸거나 저장되지 않은 것이다.

Save 후 **Build Now로 한 번 수동 빌드한다.**

> **이 수동 빌드를 건너뛰면 웹훅이 안 먹는다.** `Jenkinsfile`의 `triggers { gitlab(...) }` 블록은
> Jenkins가 Jenkinsfile을 최소 한 번 읽은 뒤에야 등록되기 때문이다. 여기서 시간을 버리는 경우가 많다.

이 빌드가 Success여야 10장으로 간다.

> **왜 dev 가 아니라 작업 브랜치인가.** 이 잡을 만드는 시점에 `Jenkinsfile`은 아직 `dev`에
> 없고 작업 브랜치에만 있다. `*/dev` 로 두면 빌드할 대상이 없어 11장의 동작 확인을 할 수 없다.
> 그래서 처음에는 작업 브랜치를 직접 지정한다. 예:
>
> ```
> */infra/chore/jenkins-setup-S15P21A501-22
> ```
>
> **`**` 는 쓰지 말 것.** 일반 Pipeline 잡에서는 이 값이 refspec `refs/heads/**` 로 변환되는데
> JGit이 이를 거부해 빌드가 시작조차 못 한다(2026-08-31 실측).
> ```
> java.lang.IllegalArgumentException: Invalid refspec refs/heads/**
> ```
> `**` 문법은 Multibranch Pipeline 잡의 브랜치 필터에서 쓰는 것이고, 여기서는 통하지 않는다.
>
> **머지 후에는 `*/dev` 로 바꾼다.** 통합 브랜치가 `dev` 이기 때문이다
> (`.gitlab/CONTRIBUTING.md`). 모든 브랜치를 검사하고 싶으면 Branch Specifier를 **비워둔다**
> (Git 플러그인의 공식 "any branch" 표기). 이 경우도 한 번 빌드해서 확인하고 넘어갈 것.

---

## 10. Webhook 연결

GitLab 프로젝트 → **Settings → Webhooks** → Add new webhook

| 항목 | 값 |
|---|---|
| URL | `https://j15<팀ID>.p.ssafy.io/jenkins/project/npick-ci` |
| Secret token | 9장에서 Generate한 토큰 |
| Trigger | ☑ Push events ☑ Merge request events |
| SSL verification | 체크한다 — 실제 인증서라 검증을 통과한다 |

> **nginx 경유 주소다** (S15P21A501-132). Jenkins 가 `127.0.0.1:18080` 에만 바인딩되어
> GitLab 이 직접 닿을 수 없다. 포트가 없는 이유는 nginx 의 443 을 쓰기 때문이다.

> URL이 `/job/npick-ci`가 아니라 **`/project/npick-ci`** 다. gitlab-plugin 전용 엔드포인트이고,
> 이 경로만 Jenkins의 CSRF 보호에서 예외 처리되어 있다. `/job/...`으로 넣으면 403이 뜬다.

저장 후 **Test → Push events** → **HTTP 200** 확인.

| 결과 | 원인 |
|---|---|
| `Hook executed successfully: HTTP 200` | 정상 |
| 타임아웃 / `Connection refused` | ufw에서 GitLab 서버 IP가 허용되지 않음 (1-3, 1-4) |
| `403` | URL이 `/job/`으로 되어 있거나 Secret token 불일치 |

---

## 11. 동작 확인 (완료 조건 증빙)

9장에서 잡의 Branch Specifier 를 작업 브랜치로 지정했으므로, **그 브랜치에 푸시해서** 확인한다.

```bash
git switch <작업 브랜치>
```

```bash
git commit --allow-empty -m ":wrench: chore(infra): 웹훅 트리거 동작 확인 (지라키)"
```

```bash
git push
```

푸시 직후 `npick-ci` 잡에 **빌드가 자동으로 하나 뜨면 성공**이다. 빌드 로그 앞부분:

```
 ─────────────────────────────────────────────
  브랜치 : infra/chore/jenkins-setup-S15P21A501-22
  커밋   : 8bce415 — :wrench: chore(infra): 웹훅 자동 빌드 최종 확인 (S15P21A501-22)
  작성자 : crolvlee
  트리거 : Started by GitLab push by 이다인
 ─────────────────────────────────────────────
```

**세 가지가 모두 맞아야 유효한 증빙이다.**

| 항목 | 맞는 값 | 틀린 경우 |
|---|---|---|
| 트리거 | `Started by GitLab push` | `Started by user` → 수동 빌드다 |
| 브랜치 | 방금 푸시한 브랜치 | `main` → 웹훅 **Test 버튼**으로 만든 가짜 이벤트다 |
| 커밋 | 방금 푸시한 커밋 해시 | 이전 커밋 → 다른 빌드를 보고 있다 |

이 화면을 캡처해 MR에 첨부한다 — 이슈의 "실제 동작 확인 (GitLab 푸시 → Jenkins 빌드 자동 실행 로그)" 증빙이다.

빌드가 끝난 뒤 토큰이 유지되는지도 함께 확인한다. 이게 8-4 방식이 제대로 먹었다는 증거다.

```bash
sudo grep -c secretToken /home/ubuntu/jenkins-data/jobs/npick-ci/config.xml
```

`1` 이 유지되어야 한다. `0` 이 되면 13장 (2-2).

> 확인용 빈 커밋은 MR을 올리기 전에 정리한다. 이 프로젝트는 **일반 머지 커밋**을 쓰고 Squash 가
> 프로젝트 설정에서 막혀 있어(`.gitlab/CONTRIBUTING.md`), 브랜치의 커밋이 그대로 `dev` 히스토리에 남는다.

### 머지 **전에** 반드시 할 것

잡의 **Branch Specifier 를 `*/dev` 로 바꾼다.**

> **머지 후가 아니라 머지 전이다.** 팀 규칙은 머지 후 소스 브랜치를 삭제하는데
> (`.gitlab/CONTRIBUTING.md`), Branch Specifier 가 작업 브랜치를 가리킨 채로 머지하면
> 곧바로 이런 일이 생긴다.
>
> ```
> 머지 + 소스 브랜치 삭제
>   → dev push 이벤트로 트리거 발동
>   → 잡이 이미 삭제된 작업 브랜치를 체크아웃 시도
>   → "Couldn't find any revision to build" → 빌드 실패
>   → dev 브랜치 커밋에 빨간 ✗
> ```
>
> 코드 문제가 아니라 잡 설정 때문인데, 모르면 머지 직후 dev 가 빨간불이 되어 당황하게 된다.
> 미리 바꿔두면 머지 시점의 dev push 가 곧바로 정상 빌드된다.
>
> 바꾼 직후 수동 빌드를 하면 dev 에 아직 `Jenkinsfile` 이 없어 실패할 수 있다. 정상이며,
> 머지되면 해결된다. 확인이 목적이 아니면 Save 만 하고 넘어가도 된다.

운영 시 잡의 Branch Specifier 는 계속 **`*/dev`** 로 둔다. 이 값은 실행할 코드를 항상 dev 의
신뢰된 `Jenkinsfile` 로 고정하는 역할을 한다. 실제 검증 대상은 `Jenkinsfile` 의 Checkout 단계가
GitLab 웹훅 환경변수를 보고 명시적으로 선택한다.

트리거는 `RegexBasedFilter` 로 대상 브랜치가 `dev` 인 이벤트만 받는다. 그래서 일반 기능 브랜치
push는 잡을 실행하지 않고, dev push와 dev 대상 MR 이벤트만 실행한다.

| 이벤트 | 모드 | 실제 체크아웃 | 이후 동작 |
|---|---|---|---|
| dev 대상 MR 생성·업데이트 | `MR` | MR 소스 + 최신 `origin/dev` 임시 병합 | migration 단조증가 검사만 수행, 배포 안 함 |
| dev push(머지 포함) | `DEPLOY` | `dev` | 실제 DB 검사 → 이미지 빌드 → 배포 → 검증 |
| 그 외 브랜치 push | - | - | 필터에서 제외 |

MR 모드에서는 `/deploy` 동기화, Docker 이미지 빌드, 서비스 재기동, 롤백을 전부 건너뛴다.
실패해도 운영 컨테이너에는 영향을 주지 않는다. Checkout 단계는 최신 `origin/dev`를 먼저 받은 뒤
워크스페이스 저장소에 Jenkins 전용 `user.name`과 `user.email`을 설정하고 MR 소스 ref를
`git merge --no-edit`로 명시적으로 합친다. Pipeline 잡에서 deprecated 된 `PreBuildMerge`는 쓰지
않는다. 실제 병합 충돌이 있으면 명시적 merge가 실패하므로 같은 Checkout 단계에서 드러난다.

---

## 12. 주요 명령어

```bash
sudo docker start jenkins
sudo docker stop jenkins
sudo docker restart jenkins
sudo docker logs jenkins
sudo docker logs -f jenkins
```

데이터 백업 — 잡 이력과 설정은 전부 `/home/ubuntu/jenkins-data` 에 있다.

```bash
sudo tar czf ~/jenkins-data-backup.tar.gz -C /home/ubuntu jenkins-data
```

---

## 13. 트러블슈팅

**(1) 웹 UI 접속이 안 된다**

ufw에 내 공인 IP가 허용돼 있는지 확인한다(`sudo ufw status`). 공인 IP가 바뀐 경우가 흔하다
(https://ifconfig.me 로 현재 IP 확인). `sudo docker ps`로 컨테이너가 Up인지도 본다.
SSH 터널로 접속하는 경우라면 ufw와 무관하니 터널이 살아 있는지 본다(1-2).

**(2) 플러그인 설치가 실패한다**

4장의 미러 설정을 확인한다. 그래도 안 되면 캐시된 플러그인 목록을 지우고 재기동해 갱신을 강제한다.

```bash
sudo rm /home/ubuntu/jenkins-data/updates/default.json
sudo docker restart jenkins
```

**(2-1) 빌드가 `Invalid refspec refs/heads/**` 로 즉시 실패한다**

Branch Specifier에 `**` 를 넣은 경우다. 9장의 설명대로 `*/<브랜치명>` 형태로 바꾼다.

**(2-2) 웹훅이 200과 403을 번갈아 낸다 / 빌드가 한 번 돌면 그 뒤로 403**

```
403 anonymous is missing the Job/Build permission
```

GitLab → Settings → Webhooks → Edit → **Recent events** 에서 `200 → 403 → 200 → 403` 패턴이
보이면 이 증상이다. 원인은 **declarative 가 빌드마다 잡의 트리거 설정을 `Jenkinsfile` 선언대로
재설정**하는 것이다.

| `Jenkinsfile` 상태 | 결과 |
|---|---|
| `triggers` 블록 없음 | 트리거가 **통째로 삭제**된다. 웹훅이 아예 안 걸린다 |
| `triggers` 는 있고 `secretToken` 없음 | 매 빌드마다 토큰이 지워져 이후 웹훅이 403 |
| `triggers` + `secretToken: env.…` | **정상.** 매 빌드마다 같은 값이 다시 주입된다 |

즉 **UI에서 Generate한 토큰은 어떤 경우에도 유지되지 않는다.** 잡 설정 화면에서 토큰을 만들지 말고
8-4처럼 전역 환경변수로 주입해야 한다.

확인:

```bash
sudo grep -c secretToken /home/ubuntu/jenkins-data/jobs/npick-ci/config.xml
```

```bash
sudo sed -n '/<triggers>/,/<\/triggers>/p' /home/ubuntu/jenkins-data/jobs/npick-ci/config.xml
```

출력이 비어 있으면 트리거 자체가 없는 것이고, `GitLabPushTrigger` 는 있는데 `secretToken` 이
없으면 토큰만 지워진 것이다.

> `secretToken` 을 `Jenkinsfile` 에 문자열로 적으면 해결되지만 **비밀값을 레포에 커밋하게 되므로
> 쓰지 않는다**(S15P21A501-22 제약 조건). 전역 환경변수 방식이 토큰 인증을 유지하면서 이 제약도
> 지키는 방법이며, 2026-09-01 에 로컬 Jenkins 로 빌드 2회를 돌려 값이 유지되는 것을 확인했다.

**(3) 푸시해도 빌드가 안 걸린다**

순서대로 확인한다.
1. 잡 생성 후 **Build Now로 1회 수동 빌드**를 했는가 (9장의 경고)
2. GitLab 웹훅 **Test**가 200인가 (10장)
3. 웹훅 URL이 `/project/<잡이름>` 형태인가
4. GitLab 웹훅에서 **Merge request events** 가 체크되어 있는가
5. 이벤트가 dev push 또는 dev 대상 MR인가 (`Jenkinsfile` 필터가 그 외 이벤트는 제외한다)

**(3-1) MR Checkout이 `Committer identity unknown`으로 실패한다**

비 fast-forward MR을 임시 병합할 때 merge commit 작성자 정보가 없는 경우다. Git 플러그인의
`PreBuildMerge`를 사용하면 실제 충돌이 없어도 다음 오류와 함께 Checkout이 중단될 수 있다.

```text
Committer identity unknown
fatal: unable to auto-detect email address
```

현재 `Jenkinsfile`은 대상 브랜치를 먼저 체크아웃한 다음 `infra/jenkins/merge-mr.sh`에서 저장소
로컬 identity를 설정하고 명시적으로 병합한다. 같은 오류가 다시 보이면 잡의 Branch Specifier가
`*/dev`인지, 실행 로그의 Jenkinsfile이 최신 `dev`에서 로드됐는지 확인한다.

**(4) 빌드는 도는데 GitLab에 결과가 안 보인다**

8-3의 GitLab 연결 이름이 `ssafy-gitlab`인지, `Test Connection`이 성공하는지 확인한다.
토큰 만료도 흔한 원인이다.

**(5) 파이프라인이 `docker: not found` 로 실패한다**

`jenkins/jenkins:lts` 이미지 안에는 docker CLI가 없다. BE/FE 빌드를 컨테이너로 돌리려면
그때 가서 별도 조치가 필요하다 — 아래 둘 중 하나로 간다.

- 호스트 docker를 컨테이너에 공유(docker CLI 설치 + `/var/run/docker.sock` 마운트)
- 또는 컨테이너 없이 Jenkins 안에 JDK/Node를 직접 설치해서 빌드

지금 `Jenkinsfile`은 docker를 쓰지 않으므로 이 문제가 나지 않는다. BE/FE 세팅(S15P21A501-13, -14)이
끝나 빌드 명령을 채울 때 결정한다.

**(6) EC2 디스크가 가득 찼다**

```bash
sudo docker system prune -af
du -sh /home/ubuntu/jenkins-data
```

빌드 이력이 원인이면 잡 설정의 `Discard old builds`를 조인다. `Jenkinsfile`에 이미 30개 제한이 걸려 있다.

**(7) Flyway가 `Detected resolved migration not applied`로 기동에 실패한다 — 2026-09-14 실측**

이미 더 높은 버전이 적용된 DB에 그보다 낮은 버전의 migration 파일이 나중에 들어온 경우다.
병렬 브랜치에서 만든 파일의 버전과 `dev` 머지 순서가 어긋나면 발생한다. 실제로
`V20260915100000`이 먼저 배포된 뒤 `V20260914170000`이 머지되어 backend가 기동하지 못했다.
`spring.flyway.out-of-order`는 켜지 않고, 버전 역전 자체를 막는다.

검사는 두 겹이다.

1. **MR 생성·업데이트 시** `Validate MR migrations`가 MR에 새로 추가된 파일을 최신
   `origin/dev`의 최대 migration 버전과 비교한다. 낮거나 같은 번호면 머지 전에 실패한다.
2. **dev 배포 시** `Validate migrations`가 실행 중인 PostgreSQL의
   `npick.flyway_schema_history`를 직접 조회한다. 성공 적용된 최대 버전 이하의 미적용 파일이
   있으면 이미지 빌드와 배포 전에 실패한다.

첫 번째 검사는 병렬 브랜치의 머지 순서 역전을 사전에 차단하고, 두 번째 검사는 실제 DB 상태와
Git 이력이 어긋난 경우까지 막는 최종 안전장치다. 두 검사 모두 로그에 문제 파일과 비교 기준
버전을 출력한다.

DB 적용 상태 확인:

```bash
docker compose exec postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT installed_rank, version, description, success FROM npick.flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;"'
```

문제 파일이 DB에 적용되지 않은 것이 확인되면 적용된 최대 버전보다 큰 값으로 파일명을 바꾼다.
이미 성공 적용된 migration은 파일명이나 내용을 바꾸지 않는다. 이 경우에는 후속 migration으로
수정해야 한다.

---

## 부록. ssh key 주의 (가이드 경고 사항)

배포 연동 등으로 EC2에 SSH 키를 만들고 `~/.ssh/authorized_keys`를 수정할 일이 생기면,
**기존 내용을 절대 지우지 않는다.** 지우면 EC2 최초 생성 시 셋업된 pem 키 인증이 불가능해져
서버에 접속할 수 없게 된다.

```bash
ssh-keygen -t rsa
cat id_rsa.pub >> ~/.ssh/authorized_keys
```

`>>` 가 두 개인 점에 주의한다. `>` 하나면 기존 키가 날아간다.

---

## 부록. 다음 단계 (CD)

이 문서는 **CI(푸시 → 자동 빌드)까지**가 범위다. `dev` 머지 시 배포까지 가려면 별도 티켓이
필요하고, 그때 아래 5가지를 손봐야 한다. 미리 알아둘 목적의 목록이며 지금 할 일은 아니다.

**1. Jenkins 컨테이너에 docker CLI가 없다** — 가장 먼저 부딪힌다

`jenkins/jenkins:lts` 이미지에는 `docker` 명령이 없어 파이프라인에서 `sh 'docker build ...'` 가
`docker: not found` 로 실패한다. 두 방향 중 하나를 택한다.

| 방법 | 내용 | 비고 |
|---|---|---|
| Docker-outside-of-Docker | 이미지에 docker CLI 설치 + `/var/run/docker.sock` 마운트 + docker 그룹 GID 매칭 | **컨테이너 재생성 필요.** 데이터는 `jenkins-data` 볼륨에 있어 보존된다 |
| SSH 배포 | Jenkins가 호스트에 SSH로 접속해 배포 스크립트 실행 | 재생성 불필요. 단 `authorized_keys` 를 건드리면 pem 인증이 깨질 수 있다(부록 ssh key 참고) |

**2. 브랜치별 분기 구조** — 현재 단일 Pipeline 잡은 dev 대상 MR에서 migration 단조증가 검사만
수행하고, dev push에서 전체 빌드·배포를 수행한다. 모든 MR에서 백엔드 테스트와 프론트엔드 빌드까지
돌리려면 MR 모드를 확장하거나 CI 잡과 CD 잡을 분리한다. GitLab 플러그인의 MR 환경변수는
Multibranch Pipeline에서 제공되지 않으므로 전환 전 플러그인 동작을 다시 검토해야 한다.

**3. 앱 비밀값** — DB 비밀번호, API 키 등이 생긴다. Jenkins Credentials + `withCredentials` 로
주입한다. **레포에 커밋하지 않는다**는 제약이 그대로 이어진다.

**4. ufw에 앱 포트 추가** — 지금은 18080(GitLab IP 한정)뿐이다. 앱은 80/443을 **소스 제한 없이**
열게 되므로 Jenkins 포트와 정책이 갈린다. nginx + HTTPS도 이 시점에 붙는다.

**5. EC2 자원** — Jenkins + 앱 + DB가 한 대에 올라간다. 빌드 중 앱이 느려지거나 OOM이 날 수 있다.
`numExecutors` 조정을 검토한다. 디스크는 `Jenkinsfile` 의 `buildDiscarder(30)` 과 `cleanWs()` 로
일부 대비되어 있다.

> **1번을 지금 미리 해두지 않은 이유**: BE/FE 스택이 확정되어야 컨테이너로 빌드할지 Jenkins 안에
> 툴체인을 설치할지 정할 수 있다. S15P21A501-13, -14 완료 후 판단한다.
