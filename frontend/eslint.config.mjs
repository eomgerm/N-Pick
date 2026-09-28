import coreWebVitals from 'eslint-config-next/core-web-vitals';
import typescript from 'eslint-config-next/typescript';

const eslintConfig = [
  ...coreWebVitals,
  ...typescript,
  { ignores: ['.next/**', 'out/**', 'node_modules/**', 'next-env.d.ts'] },
];

export default eslintConfig;
