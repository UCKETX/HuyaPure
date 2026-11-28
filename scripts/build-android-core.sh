#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${1:-1.7.0-mobile}"
JNILIBS_DIR="${ROOT_DIR}/mobile-android/src/main/jniLibs"

mkdir -p "${JNILIBS_DIR}/arm64-v8a" "${JNILIBS_DIR}/armeabi-v7a" "${JNILIBS_DIR}/x86_64"

echo "Building Go mobile core for Android (version=${VERSION})..."
cd "${ROOT_DIR}/server"

CGO_ENABLED=0 GOOS=linux GOARCH=arm64 \
  go build -trimpath -ldflags="-s -w -X main.Version=${VERSION}" \
  -o "${JNILIBS_DIR}/arm64-v8a/libhubancore.so" ./cmd/huban-server

CGO_ENABLED=0 GOOS=linux GOARCH=arm GOARM=7 \
  go build -trimpath -ldflags="-s -w -X main.Version=${VERSION}" \
  -o "${JNILIBS_DIR}/armeabi-v7a/libhubancore.so" ./cmd/huban-server

CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
  go build -trimpath -ldflags="-s -w -X main.Version=${VERSION}" \
  -o "${JNILIBS_DIR}/x86_64/libhubancore.so" ./cmd/huban-server

ls -lh "${JNILIBS_DIR}/arm64-v8a/libhubancore.so" \
       "${JNILIBS_DIR}/armeabi-v7a/libhubancore.so" \
       "${JNILIBS_DIR}/x86_64/libhubancore.so"
