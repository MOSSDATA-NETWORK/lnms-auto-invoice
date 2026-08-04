FROM node:24.14.0-alpine AS build
WORKDIR /workspace
RUN corepack enable && corepack prepare pnpm@10.34.5 --activate
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY frontend ./
RUN pnpm build

FROM nginx:1.29-alpine
RUN rm /etc/nginx/conf.d/default.conf \
    && chown -R nginx:nginx /etc/nginx/conf.d /var/cache/nginx /var/run
COPY frontend/nginx.conf /etc/nginx/templates/default.conf.template
COPY frontend/security-headers.conf /etc/nginx/snippets/auto-invoice-security-headers.conf
COPY --from=build /workspace/dist /usr/share/nginx/html
USER nginx
EXPOSE 8080
