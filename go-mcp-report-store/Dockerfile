FROM golang:1.25.3-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN go test ./... && CGO_ENABLED=0 go build -o /app/report-store .

FROM alpine:3.22
COPY --from=build /app/report-store /app/report-store
RUN mkdir -p /app/data/reports
ENV REPORT_DIR=/app/data/reports
EXPOSE 8080
CMD ["/app/report-store"]
