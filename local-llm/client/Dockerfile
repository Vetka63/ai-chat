FROM node:24.13.0-alpine AS build
WORKDIR /workspace
COPY client/package*.json ./
RUN npm ci
COPY client/ ./
RUN npm run test && npm run build

FROM nginx:1.28-alpine
COPY client/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html
EXPOSE 80
