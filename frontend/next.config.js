/** @type {import('next').NextConfig} */
// 백엔드(Spring) 주소. 로컬 기본 8080, 배포 시 BACKEND_URL 환경변수로 주입.
const BACKEND = process.env.BACKEND_URL || "http://localhost:8080";

const nextConfig = {
  reactStrictMode: true,
  // /api/* 요청을 백엔드로 프록시 → 브라우저는 동일 출처라 CORS 불필요.
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${BACKEND}/api/:path*` }];
  },
};

module.exports = nextConfig;
