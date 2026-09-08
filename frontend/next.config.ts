import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
  outputFileTracingRoot: process.cwd(),
  redirects() {
    return [
      {
        source: '/:screen(login|search|wireframes|review)/:theme(yeogi|wanted|gmarket|daangn)',
        destination: '/:screen/shinhan',
        permanent: false,
      },
      {
        source: '/landing/:theme(yeogi|wanted|gmarket|daangn)',
        destination: '/landing',
        permanent: false,
      },
    ];
  },
};

export default nextConfig;
