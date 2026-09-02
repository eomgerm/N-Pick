# drawio 원본

Notion `A501 / 시스템 아키텍처` 의 `.drawio.png` 첨부에서 추출한 편집용 원본이다.
저장소 문서(`../02-container.md`, `../03-deployment.md`)의 Mermaid 다이어그램과
같은 내용을 담으며, 발표 자료용 이미지는 이 파일에서 내보낸다.

| 파일 | 대응 문서 |
| --- | --- |
| `npick-c4-container.drawio` | [02-container.md](../02-container.md) |
| `npick-deployment.drawio` | [03-deployment.md](../03-deployment.md) |

## PNG 내보내기

draw.io 데스크톱 CLI 로 내보낸다. `--embed-diagram` 을 빼면 PNG 에서 다시 편집할 수
없게 되므로 반드시 붙인다.

```bash
draw.io --export --format png --embed-diagram --scale 2 --border 10 \
  --output npick-deployment.drawio.png npick-deployment.drawio
```

내보낸 PNG 는 Notion 각 페이지의 **원본 파일** 섹션 이미지를 교체하는 데 쓴다.
Mermaid 를 고쳤으면 이 파일도 같이 고치고 Notion 이미지까지 교체해야 세 곳이 맞는다.

## 인터랙티브 뷰어 HTML 내보내기

Notion 각 페이지 상단의 **인터랙티브 뷰어** 임베드는 drawio 스킬의
`drawiohtml.py` 로 만든다. 페이지별 SVG 를 하나의 자립 HTML 에 인라인하므로
draw.io 없이 드래그 이동·휠 확대·노드 검색이 된다. 런타임 외부 요청은 없다.

```bash
SKILL=~/.claude/plugins/cache/365-skills/drawio/2.1.0/skills/drawio-skill
python "$SKILL/scripts/drawiohtml.py" npick-deployment.drawio \
  -o npick-deployment-viewer.html
```

`draw.io` CLI 가 PATH 에 있어야 한다 (Windows: `%LOCALAPPDATA%\Programs\draw.io`).

`.drawio` 를 고치면 갱신할 곳이 셋이다. 하나라도 빠지면 Notion 과 저장소가 어긋난다.

1. 이 디렉터리의 `.drawio` 원본
2. `--embed-diagram` PNG → Notion **원본 파일** 섹션 이미지
3. `drawiohtml.py` 뷰어 HTML → Notion 상단 **인터랙티브 뷰어** 임베드
