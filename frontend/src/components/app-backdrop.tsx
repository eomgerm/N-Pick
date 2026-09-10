const veils = {
  /** 요소가 성긴 화면(로그인·검색)에서는 사진을 조금 더 드러냅니다. */
  clear: 'bg-white/22',
  /** 정보가 빽빽한 화면(결과·검수)에서는 본문 대비를 위해 더 덮습니다. */
  muted: 'bg-white/46',
} as const;

interface AppBackdropProps {
  tone?: keyof typeof veils;
  unveiled?: boolean;
}

/**
 * 로그인·검색·결과·검수 화면이 함께 쓰는 사진 배경.
 * 원본 사진은 색상 보정 없이 그대로 사용합니다.
 * 필요한 화면에서만 균일한 베일과 가운데 스크림을 덮어 본문 대비를 확보합니다.
 * 랜딩 화면은 자체 영상 배경을 쓰므로 이 배경을 사용하지 않습니다.
 */
export function AppBackdrop({ tone = 'clear', unveiled = false }: AppBackdropProps) {
  return (
    <div aria-hidden="true" className="pointer-events-none fixed inset-0 -z-10 overflow-hidden">
      <div className="absolute inset-0 bg-[url('/images/app-backdrop-living-room.png')] bg-cover bg-center bg-no-repeat" />
      {!unveiled && (
        <>
          <div className={`absolute inset-0 ${veils[tone]}`} />
          <div className="absolute inset-0 bg-linear-to-b from-white/45 via-transparent to-white/60" />
          <div className="absolute inset-0 bg-radial-[at_50%_46%] from-white/88 from-10% via-white/34 via-46% to-transparent to-74%" />
        </>
      )}
    </div>
  );
}
