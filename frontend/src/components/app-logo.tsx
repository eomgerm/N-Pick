import styles from '@/components/app-logo.module.css';

interface AppLogoProps {
  className?: string;
}

/** 투명 로고 아래에만 서리 낀 유리 표면을 둡니다. */
export function AppLogo({ className }: AppLogoProps) {
  return (
    <div aria-hidden="true" className={`${styles.glass}${className ? ` ${className}` : ''}`}>
      <span className={styles.mark} />
    </div>
  );
}
