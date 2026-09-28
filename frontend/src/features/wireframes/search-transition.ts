'use client';

import { useLayoutEffect, useRef } from 'react';

import { isSameSearchDestination } from '@/features/wireframes/search-navigation';

interface SearchTransition {
  href: string;
  bounds: DOMRect;
  capturedAt: number;
}

// Keep only the current in-app navigation; direct visits and reloads have no origin.
let pendingTransition: SearchTransition | null = null;

export function prepareSearchTransition(field: HTMLElement | null, href: string) {
  const transition =
    field && !window.matchMedia('(prefers-reduced-motion: reduce)').matches
      ? { href, bounds: field.getBoundingClientRect(), capturedAt: performance.now() }
      : null;
  pendingTransition = transition;

  return () => {
    if (pendingTransition === transition) pendingTransition = null;
  };
}

export function useSearchArrival() {
  const searchFieldRef = useRef<HTMLDivElement>(null);
  const workspaceRef = useRef<HTMLDivElement>(null);
  const transitionRef = useRef<SearchTransition | null>(null);

  useLayoutEffect(() => {
    const transition = transitionRef.current ?? pendingTransition;
    pendingTransition = null;
    const field = searchFieldRef.current;
    const workspace = workspaceRef.current;
    if (
      !transition ||
      !field?.animate ||
      !workspace ||
      window.matchMedia('(prefers-reduced-motion: reduce)').matches ||
      performance.now() - transition.capturedAt > 10_000 ||
      !isSameSearchDestination(window.location.href, transition.href)
    ) {
      return;
    }

    // Retain the origin across React Strict Mode's effect cleanup and replay.
    transitionRef.current = transition;
    const from = transition.bounds;
    const to = field.getBoundingClientRect();
    if (!from.width || !from.height || !to.width || !to.height) return;

    const easing = 'cubic-bezier(0.22, 1, 0.36, 1)';
    const animations = [
      field.animate(
        [
          {
            transformOrigin: 'top left',
            transform: `translate(${from.x - to.x}px, ${from.y - to.y}px) scale(${from.width / to.width}, ${from.height / to.height})`,
          },
          { transformOrigin: 'top left', transform: 'none' },
        ],
        { duration: 680, easing },
      ),
      workspace.animate(
        [
          { opacity: 0, transform: 'translateY(16px)' },
          { opacity: 1, transform: 'none' },
        ],
        { duration: 440, delay: 140, easing, fill: 'backwards' },
      ),
    ];

    return () => animations.forEach((animation) => animation.cancel());
  }, []);

  return { searchFieldRef, workspaceRef };
}
