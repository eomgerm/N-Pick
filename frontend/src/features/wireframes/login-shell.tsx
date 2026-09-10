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
import { type FormEvent, type RefObject, useEffect, useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ApiErrorNotice } from '@/components/api-error-notice';
import { login } from '@/lib/auth/api';
import { announceSessionChange, cleanBrowserDrafts, discardQueries } from '@/lib/auth/browser';
import { postLoginPath } from '@/lib/auth/member';
import { routes } from '@/lib/routes';

import { EntryHeader, EntryFooter } from '@/features/wireframes/entry-chrome';
import {
  getLoginErrorPresentation,
  type LoginErrorDialogContent,
} from '@/features/wireframes/login-error';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import { AppBackdrop } from '@/components/app-backdrop';
import styles from '@/features/wireframes/entry.module.css';

interface LoginShellProps {
  role: 'editor' | 'reviewer';
  theme: WireframeTheme;
  returnTo?: string;
  reason?: string;
}

interface LoginErrorDialogProps extends LoginErrorDialogContent {
  onClose: () => void;
  returnFocusRef: RefObject<HTMLInputElement | null>;
}

function LoginErrorDialog({
  eyebrow,
  message,
  onClose,
  returnFocusRef,
  title,
}: LoginErrorDialogProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    const returnFocusTarget = returnFocusRef.current;
    const previousOverflow = document.body.style.overflow;
    dialog?.showModal();
    document.body.style.overflow = 'hidden';
    return () => {
      dialog?.close();
      document.body.style.overflow = previousOverflow;
      returnFocusTarget?.focus({ preventScroll: true });
    };
  }, [returnFocusRef]);

  return (
    <dialog
      aria-describedby="login-error-message"
      aria-labelledby="login-error-title"
      className={styles.loginErrorDialog}
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
      ref={dialogRef}
    >
      <div className={styles.loginErrorPopup}>
        <span aria-hidden="true" className={styles.loginErrorIcon}>
          <LockKeyhole />
        </span>
        <p className={styles.loginErrorEyebrow}>{eyebrow}</p>
        <h2 id="login-error-title">{title}</h2>
        <p id="login-error-message">{message}</p>
        <button className={styles.primaryButton} onClick={onClose} type="button">
          확인
        </button>
      </div>
    </dialog>
  );
}

export function LoginShell({ role, theme, returnTo, reason }: LoginShellProps) {
  const client = useQueryClient();
  const [errors, setErrors] = useState<{ userId?: string; password?: string }>({});
  const [isLogoutNoticeVisible, setIsLogoutNoticeVisible] = useState(reason === 'logout');
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
  const loginErrorPresentation = mutation.isError
    ? getLoginErrorPresentation(mutation.error)
    : undefined;
  const isInvalidCredentialsFailure = loginErrorPresentation?.kind === 'invalid-credentials';
  const loginErrorDialogContent =
    loginErrorPresentation?.kind === 'dialog' ? loginErrorPresentation.content : undefined;
  const hasInlineLoginError = loginErrorPresentation?.kind === 'inline';

  useEffect(() => {
    // A Server Component can redirect an expired session here without a client
    // query error. Clear the old account's cache on this entry path as well.
    void discardQueries(client);
  }, [client]);

  useEffect(() => {
    if (!isLogoutNoticeVisible) return;
    const timeoutId = window.setTimeout(() => setIsLogoutNoticeVisible(false), 3_000);
    return () => window.clearTimeout(timeoutId);
  }, [isLogoutNoticeVisible]);

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

  function handleLoginErrorClose() {
    mutation.reset();
  }

  return (
    <div className={`${styles.shell} ${styles.loginShell}`} data-theme={theme}>
      <AppBackdrop unveiled />
      <EntryHeader />
      {isLogoutNoticeVisible && (
        <div aria-atomic="true" className={styles.logoutToast} role="status">
          로그아웃 되었습니다.
        </div>
      )}
      {loginErrorDialogContent && (
        <LoginErrorDialog
          {...loginErrorDialogContent}
          onClose={handleLoginErrorClose}
          returnFocusRef={passwordRef}
        />
      )}
      <main className={styles.loginMain}>
        <section
          aria-labelledby="login-title"
          className={`${styles.loginCard} backdrop-blur-2xl backdrop-saturate-150`}
          data-invalid-credentials={isInvalidCredentialsFailure}
        >
          <Link className={styles.backLink} href={routes.landing}>
            <ArrowLeft aria-hidden="true" />
            역할 다시 선택
          </Link>
          <div className={styles.loginHeading}>
            <span className={styles.loginIcon}>
              {role === 'editor' ? (
                <Clapperboard aria-hidden="true" />
              ) : (
                <ShieldCheck aria-hidden="true" />
              )}
            </span>
            <h2 id="login-title">로그인</h2>
          </div>
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
          {hasInlineLoginError && <ApiErrorNotice id="login-api-error" error={mutation.error} />}
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
                    : hasInlineLoginError
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
            <p
              aria-live="polite"
              className={`${styles.error} ${styles.userIdError}`}
              id="login-id-error"
            >
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
                    : hasInlineLoginError
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
            <button
              className={styles.primaryButton}
              data-login-failed={isInvalidCredentialsFailure}
              disabled={isSubmitting || isInvalidCredentialsFailure}
              type="submit"
            >
              {isInvalidCredentialsFailure
                ? '아이디 또는 비밀번호가 틀렸습니다.'
                : isSubmitting
                  ? '로그인 중…'
                  : '로그인'}
              {!isInvalidCredentialsFailure && <ArrowRight aria-hidden="true" />}
            </button>
            <span aria-live="polite" className={styles.srOnly}>
              {isInvalidCredentialsFailure
                ? '아이디 또는 비밀번호를 수정하면 다시 로그인할 수 있습니다.'
                : isSubmitting
                  ? '계정을 확인하고 있습니다.'
                  : ''}
            </span>
          </form>
        </section>
      </main>
      <EntryFooter />
    </div>
  );
}
