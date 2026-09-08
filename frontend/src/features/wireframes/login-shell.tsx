'use client';

import {
  ArrowLeft,
  ArrowRight,
  Clapperboard,
  LockKeyhole,
  ShieldCheck,
  UserRound,
} from 'lucide-react';
import Link from 'next/link';
import { type FormEvent, useEffect, useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ApiErrorNotice } from '@/components/api-error-notice';
import { login } from '@/lib/auth/api';
import { announceSessionChange, cleanBrowserDrafts, discardQueries } from '@/lib/auth/browser';
import { postLoginPath } from '@/lib/auth/member';
import { routes } from '@/lib/routes';

import { EntryHeader, EntryFooter } from '@/features/wireframes/entry-chrome';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import styles from '@/features/wireframes/entry.module.css';

interface LoginShellProps {
  role: 'editor' | 'reviewer';
  theme: WireframeTheme;
  returnTo?: string;
  reason?: string;
}

export function LoginShell({ role, theme, returnTo, reason }: LoginShellProps) {
  const client = useQueryClient();
  const [errors, setErrors] = useState<{ userId?: string; password?: string }>({});
  const inputRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);
  const submissionRef = useRef(false);
  const roleLabel = role === 'editor' ? '편집자' : '검수자';
  const mutation = useMutation({
    // No credentials in mutation variables/cache or browser storage.
    mutationFn: () =>
      login({
        loginId: inputRef.current?.value.trim() ?? '',
        password: passwordRef.current?.value ?? '',
      }),
    gcTime: 0,
    onSuccess: async (member) => {
      cleanBrowserDrafts(member.memberId);
      await discardQueries(client);
      announceSessionChange('login');
      window.location.replace(postLoginPath(member.role, returnTo));
    },
    onSettled: () => {
      submissionRef.current = false;
      if (passwordRef.current) passwordRef.current.value = '';
    },
  });
  const isSubmitting = mutation.isPending;

  useEffect(() => {
    // A Server Component can redirect an expired session here without a client
    // query error. Clear the old account's cache on this entry path as well.
    void discardQueries(client);
  }, [client]);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submissionRef.current) return;
    const form = new FormData(event.currentTarget);
    const userId = form.get('userId');
    const password = form.get('password');
    const nextErrors = {
      userId:
        typeof userId !== 'string' || !userId.trim()
          ? `${roleLabel} ID를 입력해 주세요.`
          : undefined,
      password:
        typeof password !== 'string' || !password.length ? '비밀번호를 입력해 주세요.' : undefined,
    };
    setErrors(nextErrors);
    if (nextErrors.userId || nextErrors.password) {
      (nextErrors.userId ? inputRef : passwordRef).current?.focus();
      return;
    }
    submissionRef.current = true;
    mutation.mutate();
  }

  return (
    <div className={styles.shell} data-theme={theme}>
      <EntryHeader label={`${roleLabel} 워크스페이스`} />
      <main className={styles.loginMain}>
        <section className={styles.welcome} aria-labelledby="welcome-title">
          <p className={styles.eyebrow}>YOUR NEXT SCENE STARTS HERE</p>
          <h1 id="welcome-title">
            <span>안녕하세요</span>
            <span>
              <em>N-Pick</em>과 함께 더 스마트하게
            </span>
          </h1>
          <p className={styles.welcomeDescription}>
            장면을 찾는 순간부터, 더 나은 선택까지.
            <br />
            오늘의 작업을 N-Pick과 시작해 보세요.
          </p>
          <div aria-hidden="true" className={styles.sceneArt}>
            <div className={styles.artBack} />
            <div className={styles.artFront}>
              <span className={styles.artCorners} />
              <span className={styles.artPlay} />
              <span className={styles.artCaption}>THE RIGHT SCENE.</span>
              <span className={styles.artTimeline}>
                <i />
                <i />
                <i />
                <i />
                <i />
              </span>
            </div>
            <span className={styles.artSpark}>✦</span>
          </div>
        </section>
        <section className={styles.loginCard} aria-labelledby="login-title">
          <Link className={styles.backLink} href={routes.landing}>
            <ArrowLeft aria-hidden="true" />
            역할 다시 선택
          </Link>
          <span className={styles.loginIcon}>
            {role === 'editor' ? (
              <Clapperboard aria-hidden="true" />
            ) : (
              <ShieldCheck aria-hidden="true" />
            )}
          </span>
          <h2 id="login-title">로그인</h2>
          <p className={styles.cardDescription}>
            {roleLabel}님, 반가워요.
            <br />
            ID와 비밀번호를 입력하고 작업을 시작하세요.
          </p>
          {reason === 'expired' && (
            <p role="status" className={styles.error}>
              로그인이 만료되었습니다. 다시 로그인해 주세요.
            </p>
          )}
          {reason === 'logout' && <p role="status">로그아웃되었습니다.</p>}
          {mutation.isError && <ApiErrorNotice id="login-api-error" error={mutation.error} />}
          <form aria-label={`${roleLabel} 로그인`} noValidate onSubmit={handleSubmit}>
            <label className={styles.fieldLabel} htmlFor="login-id">
              아이디
            </label>
            <div className={styles.inputWrap} data-invalid={Boolean(errors.userId)}>
              <UserRound aria-hidden="true" />
              <input
                aria-describedby={
                  errors.userId
                    ? 'login-id-error'
                    : mutation.isError
                      ? 'login-api-error'
                      : undefined
                }
                aria-invalid={Boolean(errors.userId)}
                autoComplete="username"
                disabled={isSubmitting}
                id="login-id"
                maxLength={100}
                name="userId"
                onChange={() => {
                  mutation.reset();
                  setErrors((current) => ({ ...current, userId: undefined }));
                }}
                placeholder={`${roleLabel} ID 입력하기`}
                ref={inputRef}
                required
                type="text"
              />
            </div>
            <p aria-live="polite" className={styles.error} id="login-id-error">
              {errors.userId}
            </p>
            <label className={styles.fieldLabel} htmlFor="login-password">
              비밀번호
            </label>
            <div className={styles.inputWrap} data-invalid={Boolean(errors.password)}>
              <LockKeyhole aria-hidden="true" />
              <input
                aria-describedby={
                  errors.password
                    ? 'login-password-error'
                    : mutation.isError
                      ? 'login-api-error'
                      : undefined
                }
                aria-invalid={Boolean(errors.password)}
                autoComplete="current-password"
                disabled={isSubmitting}
                id="login-password"
                name="password"
                onChange={() => {
                  mutation.reset();
                  setErrors((current) => ({ ...current, password: undefined }));
                }}
                placeholder="비밀번호 입력하기"
                ref={passwordRef}
                required
                type="password"
              />
            </div>
            <p aria-live="polite" className={styles.error} id="login-password-error">
              {errors.password}
            </p>
            <button className={styles.primaryButton} disabled={isSubmitting} type="submit">
              {isSubmitting ? '로그인 중…' : '로그인'}
              <ArrowRight aria-hidden="true" />
            </button>
            <span aria-live="polite" className={styles.srOnly}>
              {isSubmitting ? '계정을 확인하고 있습니다.' : ''}
            </span>
          </form>
          <p className={styles.cardFootnote}>좋은 뉴스는, 좋은 장면에서.</p>
        </section>
      </main>
      <EntryFooter />
    </div>
  );
}
