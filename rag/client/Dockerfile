FROM node:24.13.0-alpine AS build
WORKDIR /workspace/client
COPY client/package*.json ./
RUN npm ci
COPY client/ ./
COPY api/ /workspace/api/
RUN npm run build

FROM nginx:1.28-alpine
COPY client/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/client/dist /usr/share/nginx/html
EXPOSE 80
