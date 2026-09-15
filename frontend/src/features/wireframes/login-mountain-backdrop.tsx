'use client';

import Image from 'next/image';
import { useEffect, useRef, useState } from 'react';

import styles from '@/features/wireframes/login-mountain-backdrop.module.css';

const layers = ['sky', 'mountains', 'foreground'] as const;

/** Login-only decoration. Motion never participates in form or auth state. */
export function LoginMountainBackdrop() {
  const sceneRef = useRef<HTMLDivElement>(null);
  const loadedLayers = useRef(new Set<string>());
  const [isReady, setIsReady] = useState(false);
  const [hasFailed, setHasFailed] = useState(false);

  useEffect(() => {
    const scene = sceneRef.current;
    if (!scene || !isReady || hasFailed) return;

    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
    const finePointer = window.matchMedia('(hover: hover) and (pointer: fine)');
    const current = { x: 0, y: 0, scroll: 0 };
    const target = { ...current };
    let frameId = 0;
    let previousTime = 0;

    function paint() {
      scene!.style.setProperty('--pointer-x', `${current.x.toFixed(3)}px`);
      scene!.style.setProperty('--pointer-y', `${current.y.toFixed(3)}px`);
      scene!.style.setProperty('--scroll-y', `${current.scroll.toFixed(3)}px`);
    }

    function stop() {
      cancelAnimationFrame(frameId);
      frameId = 0;
      previousTime = 0;
    }

    function animate(time: number) {
      frameId = 0;
      const elapsed = previousTime ? Math.min(time - previousTime, 64) : 16;
      previousTime = time;
      const easing = 1 - Math.exp(-elapsed / 120);
      let isSettled = true;
      for (const key of ['x', 'y', 'scroll'] as const) {
        current[key] += (target[key] - current[key]) * easing;
        if (Math.abs(target[key] - current[key]) < 0.02) current[key] = target[key];
        else isSettled = false;
      }
      paint();
      if (!isSettled) frameId = requestAnimationFrame(animate);
      else previousTime = 0;
    }

    function schedule() {
      if (!frameId && !reducedMotion.matches && !document.hidden) {
        frameId = requestAnimationFrame(animate);
      }
    }

    function handlePointerMove(event: PointerEvent) {
      if (!finePointer.matches || event.pointerType !== 'mouse' || reducedMotion.matches) return;
      target.x = (Math.max(0, Math.min(1, event.clientX / window.innerWidth)) * 2 - 1) * -20;
      target.y = (Math.max(0, Math.min(1, event.clientY / window.innerHeight)) * 2 - 1) * -14;
      schedule();
    }

    function handlePointerLeave() {
      target.x = 0;
      target.y = 0;
      schedule();
    }

    function handleScroll() {
      // Bounded displacement keeps the photograph covering even very long pages.
      target.scroll = Math.min(Math.max(window.scrollY, 0) * 0.08, 28);
      schedule();
    }

    function handleMotionChange() {
      stop();
      scene!.dataset.motion = reducedMotion.matches
        ? 'reduced'
        : document.hidden
          ? 'paused'
          : 'active';
      target.x = 0;
      target.y = 0;
      if (reducedMotion.matches) {
        Object.assign(current, { x: 0, y: 0, scroll: 0 });
        paint();
      } else if (!document.hidden) {
        handleScroll();
      }
    }

    window.addEventListener('pointermove', handlePointerMove, { passive: true });
    document.documentElement.addEventListener('pointerleave', handlePointerLeave);
    window.addEventListener('blur', handlePointerLeave);
    window.addEventListener('scroll', handleScroll, { passive: true });
    window.addEventListener('resize', handleMotionChange);
    document.addEventListener('visibilitychange', handleMotionChange);
    reducedMotion.addEventListener('change', handleMotionChange);
    finePointer.addEventListener('change', handleMotionChange);
    handleMotionChange();

    return () => {
      stop();
      window.removeEventListener('pointermove', handlePointerMove);
      document.documentElement.removeEventListener('pointerleave', handlePointerLeave);
      window.removeEventListener('blur', handlePointerLeave);
      window.removeEventListener('scroll', handleScroll);
      window.removeEventListener('resize', handleMotionChange);
      document.removeEventListener('visibilitychange', handleMotionChange);
      reducedMotion.removeEventListener('change', handleMotionChange);
      finePointer.removeEventListener('change', handleMotionChange);
    };
  }, [isReady, hasFailed]);

  return (
    <div
      aria-hidden="true"
      className="pointer-events-none fixed inset-0 -z-10 overflow-hidden bg-sky-200"
      data-mountain-backdrop
    >
      <div className={styles.scene}>
        <Image
          alt=""
          className="object-cover"
          fill
          preload
          sizes="100vw"
          src="/images/login-mountains/original.webp"
          unoptimized
        />
        <div className={styles.layers} data-ready={isReady && !hasFailed} ref={sceneRef}>
          {layers.map((layer) => (
            <div className={`${styles.layer} ${styles[layer]}`} data-depth={layer} key={layer}>
              <Image
                alt=""
                className="object-cover"
                fill
                onError={() => setHasFailed(true)}
                onLoad={() => {
                  loadedLayers.current.add(layer);
                  if (loadedLayers.current.size === layers.length) setIsReady(true);
                }}
                sizes="100vw"
                src={`/images/login-mountains/${layer}.webp`}
                unoptimized
                loading="eager"
              />
            </div>
          ))}
        </div>
      </div>
      <div className={styles.veil} />
    </div>
  );
}
