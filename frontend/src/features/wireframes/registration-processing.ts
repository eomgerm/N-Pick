import type { RegisteredVideo } from '@/features/wireframes/video-registration';

export interface PipelineStage {
  label: string;
  status: 'succeeded' | 'running' | 'failed' | 'pending';
  summary: string;
}

export interface RegisteredScene {
  id: string;
  title: string;
  start: number;
  end: number;
  description: string;
  shotType: string;
}

export interface ProcessingClip {
  id: string;
  title: string;
  fileName: string;
  servingStatus: 'queued' | 'ready' | 'failed';
  servingSummary: string;
  latestRun: 'queued' | 'running' | 'failed' | 'succeeded';
  latestRunLabel: string;
  activeVersion: string;
  indexVersion: string;
  missingChannels: string[];
  retryable: boolean;
  requestId?: string;
  stages: PipelineStage[];
  registration?: RegisteredVideo;
  completedAt?: string;
  sceneCount?: number;
  scenes?: RegisteredScene[];
  totalSeconds?: number;
  broadcastDate?: string;
}

export function getRegisteredClip(video: RegisteredVideo): ProcessingClip {
  return {
    id: video.id,
    title: video.fileName,
    fileName: video.fileName,
    servingStatus: 'queued',
    servingSummary: '등록 내용을 확인했어요. 영상 처리가 끝나면 검색할 수 있어요.',
    latestRun: 'queued',
    latestRunLabel: '영상 처리를 기다리고 있어요',
    activeVersion: '처리 전',
    indexVersion: '검색 반영 전',
    missingChannels: [],
    retryable: false,
    registration: video,
    stages: ['영상 파일 확인하기', '장면 나누기', '영상 정보 정리하기', '검색에 반영하기'].map(
      (label) => ({
        label,
        status: 'pending',
        summary: '아직 시작하지 않았어요',
      }),
    ),
  };
}

// 완료 조회 화면의 고정 시연 구간. 실제 영상 분석 결과가 아닙니다.
const completedSceneCuts: [number, string, string][] = [
  [6, '서울역 외부 전경', '서울역 건물과 출입구를 오가는 사람들이 보여요.'],
  [11, '대합실 입구', '짐을 든 사람들이 대합실로 들어오고 있어요.'],
  [18, '귀성객으로 붐비는 대합실', '대합실에 열차를 기다리는 귀성객들이 모여 있어요.'],
  [23, '열차 출발 안내판', '열차 출발 시각과 승강장 안내판이 보여요.'],
  [29, '안내판을 확인하는 승객', '승객들이 고개를 들어 열차 안내판을 보고 있어요.'],
  [33, '이동하는 여행 가방', '승객들이 여행 가방을 끌고 이동하고 있어요.'],
  [40, '가족 단위 귀성객', '가족들이 짐을 들고 대합실을 지나가고 있어요.'],
  [46, '매표소 앞 대기 줄', '승객들이 매표소 앞에서 차례를 기다리고 있어요.'],
  [51, '자동 발권기 이용', '승객이 자동 발권기에서 승차권을 확인하고 있어요.'],
  [57, '대합실 좌석 전경', '대합실 좌석에 앉아 열차를 기다리는 사람들이 보여요.'],
  [62, '승차권을 확인하는 손', '승객이 손에 든 승차권을 확인하고 있어요.'],
  [68, '승강장 안내 표지', '승강장으로 가는 방향을 알리는 표지가 보여요.'],
  [75, '승강장으로 이동하는 인파', '여러 승객이 짐을 들고 승강장 방향으로 이동하고 있어요.'],
  [80, '에스컬레이터 탑승', '승객들이 에스컬레이터를 이용해 내려가고 있어요.'],
  [86, '열차 도착 안내', '열차 도착을 안내하는 전광판이 보여요.'],
  [90, '승강장 대기 승객', '승객들이 승강장에서 열차를 기다리고 있어요.'],
  [97, '승강장에 들어오는 열차', '열차가 승강장으로 들어오고 있어요.'],
  [103, '열차 탑승 대기 줄', '열차 문 앞에 승객들이 줄을 서 있어요.'],
  [108, '열차에 오르는 귀성객', '승객들이 짐을 들고 열차에 오르고 있어요.'],
  [114, '출발하는 열차', '승객을 태운 열차가 승강장을 떠나고 있어요.'],
  [119, '대합실 통로', '다음 열차를 기다리는 사람들이 통로를 오가고 있어요.'],
  [125, '역 안내 데스크', '승객들이 안내 데스크에서 정보를 확인하고 있어요.'],
  [132, '대합실 전체 모습', '대합실과 이동하는 승객들의 전체 모습이 보여요.'],
  [138, '서울역 마무리 전경', '귀성객들이 오가는 서울역 전경이 보여요.'],
];

// 신한 완료 조회 화면의 고정 시연 자료. 등록한 파일의 처리 결과가 아닙니다.
export const completedDemoClip: ProcessingClip = {
  id: 'clip-completed-demo',
  title: '설 연휴 서울역 대합실 전경',
  fileName: 'KBC_20260214_서울역_전경.mp4',
  servingStatus: 'ready',
  servingSummary: '24개 장면을 검색할 수 있어요.',
  latestRun: 'succeeded',
  latestRunLabel: '모든 단계를 완료하고 검색에 반영했어요',
  activeVersion: 'pipeline-v3.7',
  indexVersion: 'idx-2026.09.07-01',
  missingChannels: ['음성 대본'],
  retryable: false,
  completedAt: '2026.09.07 09:32',
  sceneCount: completedSceneCuts.length,
  totalSeconds: 138,
  broadcastDate: '2026.02.14',
  scenes: completedSceneCuts.map(([end, title, description], index) => ({
    id: `clip-completed-demo-scene-${index + 1}`,
    title,
    start: index === 0 ? 0 : completedSceneCuts[index - 1][0],
    end,
    description,
    shotType: '정보 없음',
  })),
  stages: [
    { label: '영상 파일 확인하기', status: 'succeeded', summary: '영상 파일을 확인했어요' },
    { label: '장면 나누기', status: 'succeeded', summary: '24개 장면과 대표 화면 58개' },
    {
      label: '영상 정보 정리하기',
      status: 'succeeded',
      summary: '장면 설명·영상 속 글자를 정리했어요. 음성 대본은 포함되지 않았어요',
    },
    {
      label: '검색에 반영하기',
      status: 'succeeded',
      summary: '24개 장면의 검색 반영을 확인했어요',
    },
  ],
};
