'use client';

import { useSyncExternalStore } from 'react';

const storageKey = 'npick:parallax-enabled';

interface ParallaxPreference {
  isInitialized: boolean;
  isEnabled: boolean;
  isReducedMotion: boolean;
}

// The server and initial hydration remain still until browser preferences are known.
const serverSnapshot: ParallaxPreference = {
  isInitialized: false,
  isEnabled: true,
  isReducedMotion: false,
};

let snapshot = serverSnapshot;
let hasStorageFailed = false;
let removeBrowserListeners: (() => void) | undefined;
const listeners = new Set<() => void>();

function updateSnapshot(next: ParallaxPreference) {
  if (
    snapshot.isInitialized === next.isInitialized &&
    snapshot.isEnabled === next.isEnabled &&
    snapshot.isReducedMotion === next.isReducedMotion
  ) {
    return;
  }
  snapshot = next;
  listeners.forEach((listener) => listener());
}

function readPreference() {
  if (!hasStorageFailed) {
    try {
      return window.localStorage.getItem(storageKey) !== 'false';
    } catch {
      hasStorageFailed = true;
    }
  }
  return snapshot.isEnabled;
}

function subscribe(listener: () => void) {
  listeners.add(listener);

  if (listeners.size === 1) {
    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');

    function handleMotionChange() {
      updateSnapshot({ ...snapshot, isReducedMotion: reducedMotion.matches });
    }

    function handleStorage(event: StorageEvent) {
      if (hasStorageFailed || (event.key !== storageKey && event.key !== null)) return;
      try {
        if (event.storageArea !== window.localStorage) return;
      } catch {
        hasStorageFailed = true;
        return;
      }
      updateSnapshot({ ...snapshot, isEnabled: readPreference() });
    }

    window.addEventListener('storage', handleStorage);
    reducedMotion.addEventListener('change', handleMotionChange);
    removeBrowserListeners = () => {
      window.removeEventListener('storage', handleStorage);
      reducedMotion.removeEventListener('change', handleMotionChange);
    };
    updateSnapshot({
      isInitialized: true,
      isEnabled: readPreference(),
      isReducedMotion: reducedMotion.matches,
    });
  }

  return () => {
    listeners.delete(listener);
    if (listeners.size === 0) {
      removeBrowserListeners?.();
      removeBrowserListeners = undefined;
    }
  };
}

function getSnapshot() {
  return snapshot;
}

function getServerSnapshot() {
  return serverSnapshot;
}

export function setParallaxEnabled(isEnabled: boolean) {
  if (!snapshot.isInitialized || snapshot.isReducedMotion) return;

  if (!hasStorageFailed) {
    try {
      window.localStorage.setItem(storageKey, String(isEnabled));
    } catch {
      // Keep this document's choice even when reading still works but writing fails.
      hasStorageFailed = true;
    }
  }
  updateSnapshot({ ...snapshot, isEnabled });
}

export function useParallaxPreference() {
  const preference = useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);
  return {
    ...preference,
    isMotionEnabled:
      preference.isInitialized && preference.isEnabled && !preference.isReducedMotion,
  };
}
