import type { Metadata } from 'next';
import localFont from 'next/font/local';
import type { ReactNode } from 'react';
import { QueryProvider } from '@/components/query-provider';
import { MountainBackdrop } from '@/components/mountain-backdrop';

import './globals.css';

const pretendard = localFont({
  src: '../../public/fonts/pretendard/PretendardVariable.woff2',
  variable: '--font-ui',
  weight: '45 920',
  style: 'normal',
  display: 'swap',
  fallback: ['Apple SD Gothic Neo', 'Malgun Gothic', 'sans-serif'],
});

export const metadata: Metadata = {
  title: 'N-Pick',
  description: '필요한 뉴스 장면을 찾고 문의를 검수하는 N-Pick 워크스페이스',
};

export default function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <html className={pretendard.variable} data-scroll-behavior="smooth" lang="ko">
      <body>
        <MountainBackdrop>
          <QueryProvider>{children}</QueryProvider>
        </MountainBackdrop>
      </body>
    </html>
  );
}
