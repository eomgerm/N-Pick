'use client';

import { ArrowRight, Clapperboard, ShieldCheck } from 'lucide-react';
import dynamic from 'next/dynamic';
import Link from 'next/link';
import { useCallback, useState } from 'react';

import styles from '@/features/wireframes/landing.module.css';

const LandingCube = dynamic(
  () => import('@/features/wireframes/landing-cube').then((module) => module.LandingCube),
  { ssr: false },
);

export function LandingShell() {
  const [isCubeReady, setIsCubeReady] = useState(false);
  const handleCubeReady = useCallback(() => setIsCubeReady(true), []);

  return (
    <div className={styles.shell}>
      <div aria-hidden="true" className={styles.intro}>
        <div className={styles.wordmark}>
          <span>N</span>
          <span className={styles.letters}>eed?</span>
          <span className={styles.wordSpace} />
          <span className={styles.hyphen}>-</span>
          <span>Pick</span>
          <span className={styles.punctuation}>!</span>
        </div>
        <p>필요한 순간, 정확한 선택.</p>
      </div>
      <header className={styles.header}>
        <Link aria-label="N-Pick 홈" className={styles.brand} href="/landing">
          <span className={styles.brandMark} aria-hidden="true">
            N
          </span>
          N-Pick
        </Link>
        <span className={styles.headerNote}>뉴스 장면, 더 정확하게.</span>
      </header>
      <main className={styles.main}>
        <section aria-labelledby="landing-title" className={styles.visual}>
          <p className={styles.eyebrow}>NEED A SCENE? PICK IT.</p>
          <h1 id="landing-title">
            N-Pick<span>.</span>
          </h1>
          <p className={styles.tagline}>필요한 순간, 정확한 선택.</p>
          <div aria-hidden="true" className={styles.cubeStage}>
            <div className={styles.cubeFallback} data-ready={isCubeReady} />
            <LandingCube onReady={handleCubeReady} theme="shinhan" />
          </div>
          <span className={styles.visualCaption}>수많은 뉴스 속, 당신이 찾던 바로 그 장면.</span>
        </section>
        <section aria-labelledby="role-title" className={styles.roles}>
          <p className={styles.eyebrow}>LET’S GET STARTED</p>
          <h2 id="role-title">어떤 작업을 시작할까요?</h2>
          <p className={styles.roleDescription}>함께할 역할을 선택해 주세요.</p>
          <Link className={styles.roleCard} href="/login/shinhan?role=editor">
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
            href="/login/shinhan?role=reviewer"
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
        </section>
      </main>
      <footer className={styles.footer}>
        <span>© N-Pick</span>
        <span>좋은 뉴스는, 좋은 장면에서.</span>
      </footer>
    </div>
  );
}
