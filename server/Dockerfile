# Stage 1: Build static Go binary
FROM golang:1.24-alpine AS builder

ARG VERSION=1.5.0-server

WORKDIR /src
COPY go.mod ./
COPY cmd ./cmd
COPY internal ./internal

RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w -X main.Version=${VERSION}" -o /out/HuyaPure-server ./cmd/huban-server

# Stage 2: Minimal runtime image (~15 MB)
FROM alpine:3.21

RUN apk add --no-cache ca-certificates tzdata
ENV TZ=Asia/Shanghai \
    HUBAN_ADDR=:8686 \
    HUBAN_DATA_DIR=/data

WORKDIR /app
COPY --from=builder /out/HuyaPure-server /usr/local/bin/HuyaPure-server

VOLUME ["/data"]
EXPOSE 8686

ENTRYPOINT ["HuyaPure-server"]
