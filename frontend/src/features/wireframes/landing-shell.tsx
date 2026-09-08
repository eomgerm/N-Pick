'use client';

import '@fontsource/black-han-sans/400.css';

import { ArrowDown, ArrowRight, Clapperboard, ShieldCheck } from 'lucide-react';
import Link from 'next/link';
import { type CSSProperties, useCallback, useEffect, useRef } from 'react';

import styles from '@/features/wireframes/landing.module.css';
import { routes } from '@/lib/routes';

// 크로스페이드 구간에서 각 섹션이 차지하는 진행도 구간 (0~1).
const HERO_FADE_END = 0.55;
const ROLES_FADE_START = 0.3;
const ROLES_FADE_END = 0.85;

const clamp01 = (value: number) => Math.min(1, Math.max(0, value));

export function LandingShell() {
  const heroRef = useRef<HTMLElement | null>(null);
  const rolesRef = useRef<HTMLElement | null>(null);
  const videoRef = useRef<HTMLVideoElement | null>(null);

  // 두 섹션 모두 sticky로 같은 자리에 고정된 채, 스크롤 진행도로 서로 교차 페이드한다.
  useEffect(() => {
    const hero = heroRef.current;
    const roles = rolesRef.current;
    if (!hero || !roles) return;

    let frame = 0;
    const update = () => {
      frame = 0;
      const progress = clamp01(window.scrollY / Math.max(1, window.innerHeight));
      const heroFade = 1 - clamp01(progress / HERO_FADE_END);
      const rolesFade = clamp01(
        (progress - ROLES_FADE_START) / (ROLES_FADE_END - ROLES_FADE_START),
      );

      hero.style.setProperty('--hero-fade', String(heroFade));
      hero.dataset.faded = String(heroFade <= 0);
      roles.style.setProperty('--roles-fade', String(rolesFade));
      roles.dataset.revealed = String(rolesFade > 0);
    };
    const requestUpdate = () => {
      if (!frame) frame = window.requestAnimationFrame(update);
    };

    update();
    window.addEventListener('scroll', requestUpdate, { passive: true });
    window.addEventListener('resize', requestUpdate, { passive: true });
    return () => {
      if (frame) window.cancelAnimationFrame(frame);
      window.removeEventListener('scroll', requestUpdate);
      window.removeEventListener('resize', requestUpdate);
    };
  }, []);

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
    window.scrollTo({ top: window.innerHeight, behavior: 'smooth' });
  }, []);

  return (
    <div className={styles.shell}>
      <div aria-hidden="true" className={styles.backdrop}>
        <video
          className={styles.video}
          ref={videoRef}
          poster="/media/landing-hero-poster.jpg"
          preload="auto"
          autoPlay
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
        <span className={styles.headerNote}>필요한 순간, 정확한 선택.</span>
      </header>

      <main className={styles.main}>
        <section aria-labelledby="landing-title" className={styles.hero} ref={heroRef}>
          <h1 className={styles.heroTitle} id="landing-title">
            <span className={styles.heroLine}>
              WHAT YOU <em className={styles.accent}>NEED</em>?
            </span>
            <span className={styles.heroLine}>
              I WILL <em className={styles.accentMint}>PICK</em>!
            </span>
          </h1>
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
            <p className={styles.wordmark}>
              N<span className={styles.brandHyphen}>-</span>Pick
            </p>
            <p className={styles.wordmarkTagline}>필요한 순간, 정확한 선택.</p>
            <h2 className={styles.rolesTitle} id="role-title">
              어떤 작업을 시작할까요?
            </h2>
            <p className={styles.rolesDescription}>함께할 역할을 선택해 주세요.</p>
          </div>

          <div className={styles.roleList}>
            <Link className={styles.roleCard} href={`${routes.login}?role=editor`}>
              <span className={styles.roleIcon}>
                <Clapperboard aria-hidden="true" />
              </span>
              <span className={styles.cardCopy}>
                <small>EDITOR</small>
                <strong>편집자로 시작하기</strong>
                <span>필요한 뉴스 장면을 빠르게 찾아보세요.</span>
              </span>
              <ArrowRight aria-hidden="true" className={styles.arrow} />
            </Link>
            <Link
              className={`${styles.roleCard} ${styles.reviewerCard}`}
              href={`${routes.login}?role=reviewer`}
            >
              <span className={styles.roleIcon}>
                <ShieldCheck aria-hidden="true" />
              </span>
              <span className={styles.cardCopy}>
                <small>REVIEWER</small>
                <strong>검수자로 시작하기</strong>
                <span>검수가 필요한 장면을 확인해 주세요.</span>
              </span>
              <ArrowRight aria-hidden="true" className={styles.arrow} />
            </Link>
          </div>

          <footer className={styles.footer}>
            <span>© N-Pick</span>
            <span>좋은 뉴스는, 좋은 장면에서.</span>
          </footer>
        </section>
      </main>
    </div>
  );
}
