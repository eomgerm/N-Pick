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
