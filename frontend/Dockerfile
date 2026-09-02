FROM node:24-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM node:24-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
ARG NEXT_PUBLIC_API_BASE_URL
ARG NEXT_PUBLIC_APP_MODE
ENV NEXT_PUBLIC_API_BASE_URL=${NEXT_PUBLIC_API_BASE_URL}
ENV NEXT_PUBLIC_APP_MODE=${NEXT_PUBLIC_APP_MODE}
RUN npm run build

FROM node:24-alpine AS runtime
WORKDIR /app

# next.config.ts 가 TypeScript 라 next start 가 런타임에도 typescript 를 찾는다.
# --omit=dev 로 빼면 컨테이너 안에서 설치를 시도하다 권한 오류로 죽는다.
# FE 에서 next.config 를 .js 로 바꾸거나 output: 'standalone' 을 켜면 더 줄일 수 있다.
COPY package.json package-lock.json ./
RUN npm ci --include=dev

COPY --from=build /app/.next ./.next
COPY --from=build /app/public ./public
COPY --from=build /app/next.config.ts ./next.config.ts

ENV NODE_ENV=production
RUN chown -R node:node /app
USER node
EXPOSE 3000

# 컨테이너 안에서는 0.0.0.0 으로 listen 한다. 외부 차단은 compose 의 127.0.0.1 bind 가 담당
CMD ["npx", "next", "start", "--hostname", "0.0.0.0", "--port", "3000"]
