#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${ROOT_DIR}/mobile-ios/Frameworks"
XCFRAMEWORK="${OUT_DIR}/HubanCore.xcframework"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

IOS_MIN="16.0"
IPHONEOS_SDK="$(xcrun --sdk iphoneos --show-sdk-path)"
IPHONESIM_SDK="$(xcrun --sdk iphonesimulator --show-sdk-path)"
CLANG="$(xcrun --sdk iphoneos --find clang)"

mkdir -p "${TMP_DIR}/ios/Headers" "${TMP_DIR}/sim/Headers" "${OUT_DIR}"

echo "Building HubanCore static archive for iphoneos/arm64..."
(
  cd "${ROOT_DIR}/server"
  CGO_ENABLED=1 GOOS=ios GOARCH=arm64 \
    CC="${CLANG}" \
    CGO_CFLAGS="-isysroot ${IPHONEOS_SDK} -arch arm64 -miphoneos-version-min=${IOS_MIN}" \
    CGO_LDFLAGS="-isysroot ${IPHONEOS_SDK} -arch arm64 -miphoneos-version-min=${IOS_MIN}" \
    go build -trimpath -buildmode=c-archive -ldflags="-s -w" \
    -o "${TMP_DIR}/ios/libhubancore.a" ./cmd/huban-core-ios
)
mv "${TMP_DIR}/ios/libhubancore.h" "${TMP_DIR}/ios/Headers/libhubancore.h"
cat <<'EOF' > "${TMP_DIR}/ios/Headers/module.modulemap"
module HubanCore {
    header "libhubancore.h"
    export *
}
EOF

echo "Building HubanCore static archive for iphonesimulator/arm64..."
(
  cd "${ROOT_DIR}/server"
  CGO_ENABLED=1 GOOS=ios GOARCH=arm64 \
    CC="${CLANG}" \
    CGO_CFLAGS="-isysroot ${IPHONESIM_SDK} -arch arm64 -mios-simulator-version-min=${IOS_MIN}" \
    CGO_LDFLAGS="-isysroot ${IPHONESIM_SDK} -arch arm64 -mios-simulator-version-min=${IOS_MIN}" \
    go build -trimpath -buildmode=c-archive -ldflags="-s -w" \
    -o "${TMP_DIR}/sim/libhubancore.a" ./cmd/huban-core-ios
)
mv "${TMP_DIR}/sim/libhubancore.h" "${TMP_DIR}/sim/Headers/libhubancore.h"
cat <<'EOF' > "${TMP_DIR}/sim/Headers/module.modulemap"
module HubanCore {
    header "libhubancore.h"
    export *
}
EOF

rm -rf "${XCFRAMEWORK}"
xcodebuild -create-xcframework \
  -library "${TMP_DIR}/ios/libhubancore.a" -headers "${TMP_DIR}/ios/Headers" \
  -library "${TMP_DIR}/sim/libhubancore.a" -headers "${TMP_DIR}/sim/Headers" \
  -output "${XCFRAMEWORK}"

echo "Created ${XCFRAMEWORK}"
du -sh "${XCFRAMEWORK}"
