import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
  outputFileTracingRoot: process.cwd(),
  redirects() {
    return [
      {
        source: '/:screen(login|search|review)/:theme(shinhan|yeogi|wanted|gmarket|daangn)',
        destination: '/:screen',
        permanent: false,
      },
      {
        source: '/wireframes/:theme(shinhan|yeogi|wanted|gmarket|daangn)',
        destination: '/search/results',
        permanent: false,
      },
      {
        source: '/landing/:theme(shinhan|yeogi|wanted|gmarket|daangn)',
        destination: '/landing',
        permanent: false,
      },
    ];
  },
};

export default nextConfig;
