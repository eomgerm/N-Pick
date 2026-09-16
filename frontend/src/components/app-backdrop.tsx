const veils = {
  /** 요소가 성긴 검색 화면에서는 사진을 조금 더 드러냅니다. */
  clear: 'bg-white/22',
  /** 정보가 빽빽한 화면(결과·검수)에서는 본문 대비를 위해 더 덮습니다. */
  muted: 'bg-white/46',
  dark: 'bg-[#071f1a]/55',
} as const;

interface AppBackdropProps {
  tone?: keyof typeof veils;
  unveiled?: boolean;
}

/**
 * 검색·결과·검수 화면이 함께 쓰는 정지 배경.
 * 로그인 파라랙스의 기본 위치를 한 장으로 렌더링한 이미지를 사용합니다.
 * 필요한 화면에서만 균일한 베일과 가운데 스크림을 덮어 본문 대비를 확보합니다.
 * 랜딩 화면은 자체 영상 배경을 쓰므로 이 배경을 사용하지 않습니다.
 */
export function AppBackdrop({ tone = 'clear', unveiled = false }: AppBackdropProps) {
  return (
    <div
      aria-hidden="true"
      className="pointer-events-none fixed inset-0 -z-10 overflow-hidden bg-sky-200"
      data-static-mountain-backdrop
    >
      <div className="absolute -inset-16 bg-[url('/images/app-mountain-backdrop.webp')] bg-cover bg-center bg-no-repeat" />
      {tone !== 'dark' && (
        <div className="absolute inset-0 bg-[linear-gradient(to_bottom,rgb(242_247_253/15%),transparent_25%,transparent_80%,rgb(242_247_253/30%))]" />
      )}
      {!unveiled && (
        <>
          <div className={`absolute inset-0 ${veils[tone]}`} />
          {tone === 'dark' ? (
            <div className="absolute inset-0 bg-linear-to-b from-[#061b17]/25 via-transparent to-[#061b17]/55" />
          ) : (
            <>
              <div className="absolute inset-0 bg-linear-to-b from-white/45 via-transparent to-white/60" />
              <div className="absolute inset-0 bg-radial-[at_50%_46%] from-white/88 from-10% via-white/34 via-46% to-transparent to-74%" />
            </>
          )}
        </>
      )}
    </div>
  );
}
