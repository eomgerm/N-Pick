'use client';

import '@fontsource/black-han-sans/400.css';

import { ArrowDown } from 'lucide-react';
import Image from 'next/image';
import Link from 'next/link';
import { type CSSProperties, useCallback, useEffect, useRef, useState } from 'react';

import { AppLogo } from '@/components/app-logo';
import styles from '@/features/wireframes/landing.module.css';
import { routes } from '@/lib/routes';

// 크로스페이드 구간에서 각 섹션이 차지하는 진행도 구간 (0~1).
const HERO_FADE_END = 0.55;
const ROLES_FADE_START = 0.3;
const ROLES_FADE_END = 0.85;

const clamp01 = (value: number) => Math.min(1, Math.max(0, value));

// 헤드라인 wipe가 끝나는 시점. 이후 'EED?'·'!'가 접히며 'N / PICK'만 좌하단에 남고 배경이 드러난다.
// (landing.module.css의 .heroLine 애니메이션 delay + duration과 맞춘다.)
const INTRO_SETTLE_MS = 2400;

export function LandingShell() {
  const shellRef = useRef<HTMLDivElement | null>(null);
  const heroRef = useRef<HTMLElement | null>(null);
  const rolesRef = useRef<HTMLElement | null>(null);
  const brandRef = useRef<HTMLDivElement | null>(null);
  const brandTargetRef = useRef<HTMLDivElement | null>(null);
  const titleRef = useRef<HTMLHeadingElement | null>(null);
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const [isIntroDone, setIsIntroDone] = useState(false);

  // 인트로 동안 헤드라인을 화면 한가운데로 밀어 둘 이동량을 실측한다.
  // (transform의 영향을 받지 않는 offset* 값을 쓴다.)
  useEffect(() => {
    const hero = heroRef.current;
    const brand = brandRef.current;
    const title = titleRef.current;
    if (!hero || !brand || !title) return;

    let isActive = true;
    const measure = () => {
      if (!isActive || shellRef.current?.dataset.intro === 'done') return;
      // 실제로 보이는 가장 긴 줄을 기준으로 가운데를 잡는다.
      // offsetWidth는 정수로 반올림돼 끝이 1px 어긋나므로 소수점까지 있는 rect 폭을 쓴다.
      const lines = [...title.querySelectorAll<HTMLElement>(`.${styles.heroLine}`)];
      const widths = lines.map((line) => line.getBoundingClientRect().width);
      const textWidth = Math.max(...widths, 0);
      // 인트로 동안에는 짧은 줄을 오른쪽으로 밀어 '?'와 '!'의 x를 맞춘다.
      lines.forEach((line, index) => {
        line.style.setProperty('--line-shift', `${textWidth - widths[index]}px`);
      });
      const x = (hero.clientWidth - textWidth) / 2 - brand.offsetLeft - lines[0].offsetLeft;
      const y = (hero.clientHeight - title.offsetHeight) / 2 - brand.offsetTop;
      hero.style.setProperty('--intro-x', `${x}px`);
      hero.style.setProperty('--intro-y', `${y}px`);
    };

    measure();
    void document.fonts.ready.then(measure);
    window.addEventListener('resize', measure);
    return () => {
      isActive = false;
      window.removeEventListener('resize', measure);
    };
  }, []);

  useEffect(() => {
    // 모션을 줄이는 설정이면 인트로를 건너뛰고 바로 정착 상태로 둔다.
    const delay = window.matchMedia('(prefers-reduced-motion: reduce)').matches
      ? 0
      : INTRO_SETTLE_MS;
    const timer = window.setTimeout(() => setIsIntroDone(true), delay);
    return () => window.clearTimeout(timer);
  }, []);

  // 하나의 로고를 역할 영역의 빈 자리로 옮기고, 주변 콘텐츠만 교차 페이드한다.
  useEffect(() => {
    const shell = shellRef.current;
    const hero = heroRef.current;
    const roles = rolesRef.current;
    const brand = brandRef.current;
    const target = brandTargetRef.current;
    if (!shell || !hero || !roles || !brand || !target) return;

    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
    let frame = 0;
    const update = () => {
      frame = 0;
      const progress = clamp01(window.scrollY / Math.max(1, window.innerHeight));
      const heroFade = 1 - clamp01(progress / HERO_FADE_END);
      const rolesFade = clamp01(
        (progress - ROLES_FADE_START) / (ROLES_FADE_END - ROLES_FADE_START),
      );

      if (window.scrollY > 0) setIsIntroDone(true);
      shell.style.setProperty('--hero-fade', String(heroFade));
      shell.style.setProperty(
        '--brand-copy-fade',
        String(clamp01((progress - ROLES_FADE_END) / (1 - ROLES_FADE_END))),
      );
      hero.dataset.faded = String(heroFade <= 0);
      roles.style.setProperty('--roles-fade', String(rolesFade));
      roles.dataset.revealed = String(rolesFade > 0);

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
    window.addEventListener('scroll', requestUpdate, { passive: true });
    window.addEventListener('resize', requestUpdate, { passive: true });
    return () => {
      if (frame) window.cancelAnimationFrame(frame);
      observer.disconnect();
      reducedMotion.removeEventListener('change', requestUpdate);
      window.removeEventListener('scroll', requestUpdate);
      window.removeEventListener('resize', requestUpdate);
    };
  }, []);

  // 배경 영상은 인트로가 끝난 뒤부터 재생해, 처음부터 온전히 보여 준다.
  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;

    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
    const applyMotionPreference = () => {
      if (reducedMotion.matches || !isIntroDone) {
        video.pause();
        return;
      }
      video.play().catch(() => undefined);
    };

    applyMotionPreference();
    reducedMotion.addEventListener('change', applyMotionPreference);
    return () => reducedMotion.removeEventListener('change', applyMotionPreference);
  }, [isIntroDone]);

  const handleScrollCue = useCallback(() => {
    window.scrollTo({
      top: window.innerHeight,
      behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches
        ? 'instant'
        : 'smooth',
    });
  }, []);

  return (
    <div className={styles.shell} data-intro={isIntroDone ? 'done' : 'running'} ref={shellRef}>
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
            <h1 className={styles.heroTitle} id="landing-title" ref={titleRef}>
              <span className={styles.heroLine}>
                <em className={styles.heroWord}>
                  N<span className={styles.heroTrim}>EED</span>
                </em>
                <span className={styles.heroTrim}>?</span>
              </span>
              <span className={styles.heroLine}>
                <em className={styles.heroWord}>PICK</em>
                <span className={styles.heroTrim}>!</span>
              </span>
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
            <div aria-hidden="true" className={styles.wordmarkTarget} ref={brandTargetRef} />
            <p className={styles.wordmarkTagline}>필요한 순간, 정확한 선택.</p>
            <h2 className={styles.rolesTitle} id="role-title">
              어떤 작업을 시작할까요?
            </h2>
            <p className={styles.rolesDescription}>함께할 역할을 선택해 주세요.</p>
          </div>

          <div className={styles.roleList}>
            <Link className={styles.roleCard} href={`${routes.login}?role=editor`}>
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
                <span className={styles.cardCta}>편집 시작하기</span>
              </span>
            </Link>
            <Link className={styles.roleCard} href={`${routes.login}?role=reviewer`}>
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
                <span className={styles.cardCta}>검수 시작하기</span>
              </span>
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
