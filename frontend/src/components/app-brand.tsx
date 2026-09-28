import Link from 'next/link';
import type { MouseEventHandler } from 'react';

import { AppLogo } from '@/components/app-logo';
import { routes } from '@/lib/routes';

interface AppBrandProps {
  ariaDisabled?: boolean;
  className?: string;
  onClick?: MouseEventHandler<HTMLAnchorElement>;
}

/** 랜딩과 업무 화면이 공유하는 N-Pick 앱 아이콘 및 워드마크입니다. */
export function AppBrand({ ariaDisabled, className, onClick }: AppBrandProps) {
  return (
    <Link
      aria-label="N-Pick 홈"
      aria-disabled={ariaDisabled}
      className={`inline-flex w-fit items-center gap-2.5 text-[#17243b] no-underline${className ? ` ${className}` : ''}`}
      href={routes.landing}
      onClick={onClick}
    >
      <AppLogo />
      <span
        aria-hidden="true"
        className="grid gap-0 font-[Arial,Helvetica,sans-serif] text-[13px] leading-[0.88] font-extrabold tracking-[0.1em]"
      >
        <span>N</span>
        <span className="text-[0.77em] tracking-[0.16em]">PICK</span>
      </span>
    </Link>
  );
}
