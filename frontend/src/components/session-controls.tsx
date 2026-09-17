'use client';

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { type ReactNode, useEffect, useId, useRef, useState } from 'react';
import { ApiErrorNotice } from '@/components/api-error-notice';
import { useMember } from '@/components/session-boundary';
import { logout } from '@/lib/auth/api';
import { announceSessionChange, leaveSession } from '@/lib/auth/browser';
import styles from '@/components/session-controls.module.css';

interface SessionControlsProps {
  className?: string;
  isDisabled?: boolean;
  /** 사진 배경 위의 밝은 헤더에서는 계정 정보를 유리 알약으로 감싸 대비를 확보합니다. */
  tone?: 'light' | 'brand';
  navigation?: ReactNode;
}

export function SessionControls({
  className = '',
  isDisabled = false,
  tone = 'brand',
  navigation,
}: SessionControlsProps) {
  const [isOpen, setIsOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuId = useId();
  const member = useMember();
  const client = useQueryClient();
  const mutation = useMutation({
    mutationFn: logout,
    onSuccess: async () => {
      announceSessionChange('logout');
      await leaveSession(client, 'logout');
    },
  });

  const isLight = tone === 'light';

  useEffect(() => {
    if (!isOpen) return;
    function handleOutside(event: PointerEvent) {
      if (!mutation.isPending && !containerRef.current?.contains(event.target as Node))
        setIsOpen(false);
    }
    document.addEventListener('pointerdown', handleOutside);
    return () => document.removeEventListener('pointerdown', handleOutside);
  }, [isOpen, mutation.isPending]);

  const logoutButton = (
    <button
      className={`shrink-0 border border-current focus-visible:outline-2 disabled:opacity-50 ${
        isLight
          ? 'rounded-full px-3.5 py-1 font-semibold focus-visible:outline-offset-2'
          : 'rounded-lg px-3 py-2 focus-visible:outline-offset-4'
      }`}
      disabled={isDisabled || mutation.isPending}
      onClick={() => {
        if (!isDisabled) mutation.mutate();
      }}
      type="button"
    >
      {mutation.isPending ? '로그아웃 중…' : '로그아웃'}
    </button>
  );

  if (navigation) {
    return (
      <div
        className={`${styles.account} ${className}`}
        ref={containerRef}
        onBlur={(event) => {
          if (
            event.relatedTarget &&
            !event.currentTarget.contains(event.relatedTarget) &&
            !mutation.isPending
          )
            setIsOpen(false);
        }}
        onKeyDown={(event) => {
          if (event.key === 'Escape' && isOpen) {
            event.stopPropagation();
            setIsOpen(false);
            triggerRef.current?.focus();
          }
        }}
      >
        <button
          aria-controls={menuId}
          aria-expanded={isOpen}
          aria-label={member.loginId}
          className={styles.accountTrigger}
          disabled={isDisabled || mutation.isPending}
          onClick={() => setIsOpen(!isOpen)}
          ref={triggerRef}
          type="button"
        >
          <span aria-hidden="true" className={styles.accountAvatar} />
        </button>
        <div
          aria-hidden={!isOpen}
          className={styles.dropdown}
          data-open={isOpen}
          id={menuId}
          inert={!isOpen}
          onClick={(event) => {
            if (!isDisabled && (event.target as HTMLElement).closest('a')) setIsOpen(false);
          }}
        >
          <div className={styles.accountProfile}>
            <p className={styles.accountName}>{member.loginId}</p>
            <p className={styles.accountRole}>
              {member.role === 'REVIEWER' ? '검수자' : '편집기자'}
            </p>
          </div>
          {navigation}
          <div className={styles.logoutAction}>{logoutButton}</div>
          {mutation.isError && <ApiErrorNotice error={mutation.error} />}
        </div>
      </div>
    );
  }

  return (
    <div className={`flex flex-col items-end gap-2 text-sm ${className}`}>
      <div
        className={`flex min-w-0 flex-wrap items-center justify-end ${
          isLight
            ? 'gap-2 rounded-full border border-white/60 bg-white/70 py-1.5 pr-1.5 pl-4 shadow-[0_10px_28px_rgb(10_18_32/12%)] backdrop-blur-xl'
            : 'gap-3'
        }`}
      >
        <span className="flex min-w-0 flex-wrap justify-end gap-x-1">
          <span className="max-w-48 break-all">{member.loginId}</span>
          <span className="whitespace-nowrap">
            · {member.role === 'REVIEWER' ? '검수자' : '편집기자'}
          </span>
        </span>
        {logoutButton}
      </div>
      {mutation.isError && (
        <div className="w-full max-w-lg">
          <ApiErrorNotice error={mutation.error} />
        </div>
      )}
    </div>
  );
}
