'use client';

import '@fontsource/black-han-sans/400.css';

import { ArrowDown } from 'lucide-react';
import Image from 'next/image';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { type CSSProperties, useCallback, useEffect, useRef, useState } from 'react';

import { AppLogo } from '@/components/app-logo';
import { useMountainBackdropSettled } from '@/components/mountain-backdrop';
import styles from '@/features/wireframes/landing.module.css';
import { routes } from '@/lib/routes';

// 크로스페이드 구간에서 각 섹션이 차지하는 진행도 구간 (0~1).
const HERO_FADE_END = 0.55;
const ROLES_FADE_START = 0.3;
const ROLES_FADE_END = 0.85;
// landing.module.css의 일반 문서 흐름 전환 조건과 맞춘다.
const FLOW_LAYOUT_QUERY = '(max-width: 1000px), (max-height: 720px)';

const clamp01 = (value: number) => Math.min(1, Math.max(0, value));

export function LandingShell() {
  const router = useRouter();
  const isBackdropSettled = useMountainBackdropSettled();
  const [pendingRole, setPendingRole] = useState<'editor' | 'reviewer' | null>(null);
  const shellRef = useRef<HTMLDivElement | null>(null);
  const heroRef = useRef<HTMLElement | null>(null);
  const rolesRef = useRef<HTMLElement | null>(null);
  const brandRef = useRef<HTMLDivElement | null>(null);
  const brandTargetRef = useRef<HTMLDivElement | null>(null);
  const videoRef = useRef<HTMLVideoElement | null>(null);

  useEffect(() => {
    if (pendingRole && isBackdropSettled) {
      router.push(`${routes.login}?role=${pendingRole}`);
    }
  }, [isBackdropSettled, pendingRole, router]);

  function handleLoginNavigate(event: { preventDefault: () => void }, role: 'editor' | 'reviewer') {
    if (isBackdropSettled) return;
    event.preventDefault();
    setPendingRole(role);
  }

  // 하나의 로고를 역할 영역의 빈 자리로 옮기고, 주변 콘텐츠만 교차 페이드한다.
  useEffect(() => {
    const shell = shellRef.current;
    const hero = heroRef.current;
    const roles = rolesRef.current;
    const brand = brandRef.current;
    const target = brandTargetRef.current;
    if (!shell || !hero || !roles || !brand || !target) return;

    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
    const flowLayout = window.matchMedia(FLOW_LAYOUT_QUERY);
    let wasFlowLayout = flowLayout.matches;
    let wasInRoles = false;
    let previousHeroHeight = hero.offsetHeight;
    let frame = 0;
    const update = () => {
      frame = 0;
      const isFlowLayout = flowLayout.matches;
      const heroHeight = hero.offsetHeight;
      // 역할 선택 중 배치가 바뀌어도 히어로로 되돌아가거나 카드 아래로 밀리지 않는다.
      if (
        wasInRoles &&
        (isFlowLayout !== wasFlowLayout || (!isFlowLayout && heroHeight !== previousHeroHeight))
      ) {
        window.scrollTo({ top: heroHeight, behavior: 'instant' });
      }
      wasFlowLayout = isFlowLayout;
      previousHeroHeight = heroHeight;
      const progress = clamp01(window.scrollY / Math.max(1, heroHeight));
      wasInRoles = progress >= ROLES_FADE_END;
      const heroFade = 1 - clamp01(progress / HERO_FADE_END);
      const rolesFade = isFlowLayout
        ? 1
        : clamp01((progress - ROLES_FADE_START) / (ROLES_FADE_END - ROLES_FADE_START));

      shell.style.setProperty('--hero-fade', String(heroFade));
      hero.dataset.faded = String(heroFade <= 0);
      roles.style.setProperty('--roles-fade', String(rolesFade));
      roles.dataset.revealed = String(rolesFade > 0);

      if (isFlowLayout) {
        brand.style.removeProperty('--brand-x');
        brand.style.removeProperty('--brand-y');
        brand.style.removeProperty('--brand-scale');
        return;
      }

      const heroBounds = hero.getBoundingClientRect();
      const targetBounds = target.getBoundingClientRect();
      const sourceX = heroBounds.left + brand.offsetLeft;
      const sourceY = heroBounds.top + brand.offsetTop;
      const scale = Math.min(
        1,
        targetBounds.width / Math.max(1, brand.offsetWidth),
        targetBounds.height / Math.max(1, brand.offsetHeight),
      );
      const morph = reducedMotion.matches
        ? Number(progress >= 0.5)
        : progress * progress * (3 - 2 * progress);

      brand.style.setProperty('--brand-x', `${(targetBounds.left - sourceX) * morph}px`);
      brand.style.setProperty('--brand-y', `${(targetBounds.top - sourceY) * morph}px`);
      brand.style.setProperty('--brand-scale', String(1 + (scale - 1) * morph));
    };
    const requestUpdate = () => {
      if (!frame) frame = window.requestAnimationFrame(update);
    };

    update();
    const observer = new ResizeObserver(requestUpdate);
    observer.observe(hero);
    observer.observe(brand);
    observer.observe(target);
    reducedMotion.addEventListener('change', requestUpdate);
    flowLayout.addEventListener('change', requestUpdate);
    window.addEventListener('scroll', requestUpdate, { passive: true });
    window.addEventListener('resize', requestUpdate, { passive: true });
    return () => {
      if (frame) window.cancelAnimationFrame(frame);
      observer.disconnect();
      reducedMotion.removeEventListener('change', requestUpdate);
      flowLayout.removeEventListener('change', requestUpdate);
      window.removeEventListener('scroll', requestUpdate);
      window.removeEventListener('resize', requestUpdate);
    };
  }, []);

  // 진입 즉시 배경 영상을 재생하며, 모션 감소 설정에서는 포스터를 유지한다.
  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;

    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
    const applyMotionPreference = () => {
      if (reducedMotion.matches) {
        video.pause();
        return;
      }
      video.play().catch(() => undefined);
    };

    applyMotionPreference();
    reducedMotion.addEventListener('change', applyMotionPreference);
    return () => reducedMotion.removeEventListener('change', applyMotionPreference);
  }, []);

  const handleScrollCue = useCallback(() => {
    window.scrollTo({
      top: heroRef.current?.offsetHeight ?? window.innerHeight,
      behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches
        ? 'instant'
        : 'smooth',
    });
  }, []);

  return (
    <div className={styles.shell} ref={shellRef}>
      <div aria-hidden="true" className={styles.backdrop}>
        <video
          className={styles.video}
          ref={videoRef}
          poster="/media/landing-hero-poster.jpg"
          preload="auto"
          loop
          muted
          playsInline
          tabIndex={-1}
        >
          <source src="/media/landing-hero.mp4" type="video/mp4" />
        </video>
      </div>
      <div aria-hidden="true" className={styles.overlay} />

      <header className={styles.header}>
        <Link aria-label="N-Pick 홈" className={styles.brand} href={routes.landing}>
          N<span className={styles.brandHyphen}>-</span>Pick
        </Link>
        <div className={styles.headerNote}>
          <span className={styles.headerNoteLead}>필요한 순간, 정확한 선택.</span>
          <span className={styles.headerNoteSub}>영상 검색 시간을 줄이고</span>
          <span className={styles.headerNoteSub}>편집과 창작에 집중할 수 있도록</span>
        </div>
      </header>

      <main className={styles.main}>
        <section aria-labelledby="landing-title" className={styles.hero} ref={heroRef}>
          <div className={styles.brandMotion} ref={brandRef}>
            <AppLogo className={styles.heroIcon} />
            <h1 aria-label="N-Pick" className={styles.heroTitle} id="landing-title">
              <span className={styles.heroLine}>N</span>
              <span className={styles.heroLine}>PICK</span>
            </h1>
          </div>
          <button className={styles.scrollCue} onClick={handleScrollCue} type="button">
            <span className={styles.scrollCueLabel}>Scroll down</span>
            <ArrowDown aria-hidden="true" className={styles.scrollCueArrow} />
          </button>
        </section>

        <section
          aria-labelledby="role-title"
          className={styles.roles}
          data-revealed="false"
          ref={rolesRef}
          style={{ '--roles-fade': 0 } as CSSProperties}
        >
          <div className={styles.rolesBrand}>
            <div aria-hidden="true" className={styles.wordmarkTarget} ref={brandTargetRef}>
              <div className={styles.flowBrand}>
                <AppLogo className={styles.flowIcon} />
                <span className={styles.flowWordmark}>
                  <span>N</span>
                  <span>PICK</span>
                </span>
              </div>
            </div>
            <p className={styles.wordmarkTagline}>필요한 순간, 정확한 선택.</p>
            <h2 className={styles.rolesTitle} id="role-title">
              어떤 작업을 시작할까요?
            </h2>
            <p className={styles.rolesDescription}>함께할 역할을 선택해 주세요.</p>
          </div>

          <div className={styles.roleList}>
            <Link
              className={styles.roleCard}
              href={`${routes.login}?role=editor`}
              aria-busy={pendingRole === 'editor'}
              onNavigate={(event) => handleLoginNavigate(event, 'editor')}
            >
              <span className={styles.cardMedia}>
                <Image
                  alt=""
                  className={styles.cardImage}
                  fill
                  sizes="(max-width: 740px) 92vw, (max-width: 1000px) 46vw, 26vw"
                  src="/images/role-editor.jpg"
                />
              </span>
              <span className={styles.cardBody}>
                <strong className={styles.cardTitle}>편집 기사로 시작하기</strong>
                <span className={styles.cardText}>필요한 뉴스 장면을 빠르게 찾아보세요.</span>
                <span className={styles.cardCta}>
                  {pendingRole === 'editor' ? '준비 중…' : '편집 시작하기'}
                </span>
              </span>
            </Link>
            <Link
              className={styles.roleCard}
              href={`${routes.login}?role=reviewer`}
              aria-busy={pendingRole === 'reviewer'}
              onNavigate={(event) => handleLoginNavigate(event, 'reviewer')}
            >
              <span className={styles.cardMedia}>
                <Image
                  alt=""
                  className={styles.cardImage}
                  fill
                  sizes="(max-width: 740px) 92vw, (max-width: 1000px) 46vw, 26vw"
                  src="/images/role-reviewer.jpg"
                />
              </span>
              <span className={styles.cardBody}>
                <strong className={styles.cardTitle}>아카이브 팀으로 시작하기</strong>
                <span className={styles.cardText}>검수가 필요한 장면을 확인해 주세요.</span>
                <span className={styles.cardCta}>
                  {pendingRole === 'reviewer' ? '준비 중…' : '검수 시작하기'}
                </span>
              </span>
            </Link>
          </div>
          {pendingRole && (
            <p className="sr-only" role="status">
              로그인 화면을 준비하고 있어요.
            </p>
          )}

          <footer className={styles.footer}>
            <span>© N-Pick</span>
            <span>좋은 뉴스는, 좋은 장면에서.</span>
          </footer>
        </section>
      </main>
    </div>
  );
}
