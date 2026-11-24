FROM node:24-bookworm-slim AS build

WORKDIR /app

COPY . .

RUN npm ci
RUN npm run build


FROM node:24-bookworm-slim AS runtime

WORKDIR /app

ENV NODE_ENV=production
ENV HOST=0.0.0.0
ENV PORT=3000

COPY --from=build /app/package.json /app/package-lock.json /app/.npmrc ./

RUN npm ci --omit=dev --ignore-scripts

COPY --from=build --chown=node:node /app/build ./build

USER node

EXPOSE 3000

CMD ["node", "build"]