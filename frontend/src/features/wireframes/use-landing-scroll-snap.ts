'use client';

import { type RefObject, useCallback, useEffect, useRef } from 'react';

/** 히어로와 역할 선택 사이에서만 같은 방향의 실제 스크롤 거리를 누적합니다. */
export function useLandingScrollSnap(heroRef: RefObject<HTMLElement | null>) {
  const snapTarget = useRef<number | null>(null);
  const scrollToSection = useCallback((top: number) => {
    snapTarget.current = top;
    window.scrollTo({
      top,
      behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches
        ? 'instant'
        : 'smooth',
    });
  }, []);

  useEffect(() => {
    const hero = heroRef.current;
    if (!hero) return;
    const roleTop = () => hero.offsetHeight;
    const position = () => Math.max(0, Math.min(window.scrollY, roleTop()));
    let previousPosition = position();
    let travel = 0;
    let settleTimeout = 0;

    function reset() {
      window.clearTimeout(settleTimeout);
      snapTarget.current = null;
      previousPosition = position();
      travel = 0;
    }

    function handleScroll() {
      const currentPosition = position();
      const delta = currentPosition - previousPosition;
      previousPosition = currentPosition;

      if (snapTarget.current !== null) {
        // 자동 이동으로 생긴 scroll 이벤트를 새 사용자 의도로 누적하지 않습니다.
        travel = 0;
        if (Math.abs(window.scrollY - snapTarget.current) <= 1) reset();
        else {
          // 사용자 입력으로 smooth scroll이 취소되거나 scrollend가 없는 브라우저도 복구합니다.
          window.clearTimeout(settleTimeout);
          settleTimeout = window.setTimeout(reset, 180);
        }
        return;
      }

      if (currentPosition <= 0 || currentPosition >= roleTop()) {
        travel = 0;
        return;
      }
      if (!delta) return;
      travel = Math.sign(delta) === Math.sign(travel) ? travel + delta : delta;
      const threshold = Math.min(120, Math.max(64, window.innerHeight * 0.1));
      if (Math.abs(travel) >= threshold) {
        scrollToSection(travel > 0 ? roleTop() : 0);
      }
    }

    function handleScrollEnd() {
      if (snapTarget.current !== null && Math.abs(window.scrollY - snapTarget.current) <= 1)
        reset();
    }

    function handleResize() {
      if (snapTarget.current !== null)
        window.scrollTo({ top: window.scrollY, behavior: 'instant' });
      reset();
    }

    window.addEventListener('scroll', handleScroll, { passive: true });
    document.addEventListener('scrollend', handleScrollEnd);
    window.addEventListener('resize', handleResize);
    return () => {
      window.removeEventListener('scroll', handleScroll);
      document.removeEventListener('scrollend', handleScrollEnd);
      window.removeEventListener('resize', handleResize);
      if (snapTarget.current !== null)
        window.scrollTo({ top: window.scrollY, behavior: 'instant' });
      reset();
    };
  }, [heroRef, scrollToSection]);

  return useCallback(() => {
    scrollToSection(heroRef.current?.offsetHeight ?? window.innerHeight);
  }, [heroRef, scrollToSection]);
}
