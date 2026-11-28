#!/usr/bin/env bash
set -euo pipefail

TAG="${1:-v1.7.0}"
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
IOS_DIR="${ROOT_DIR}/mobile-ios"
PROJECT="${IOS_DIR}/HubanMobile.xcodeproj"
BUILD_DIR="${IOS_DIR}/build"
DERIVED_DIR="${BUILD_DIR}/DerivedData"
STAGE_DIR="${BUILD_DIR}/ipa-stage"
IPA_PATH="${BUILD_DIR}/HuyaPure-${TAG}-ios-unsigned.ipa"

if [ ! -d "${IOS_DIR}/Frameworks/HubanCore.xcframework" ]; then
  echo "HubanCore.xcframework not found; building it first..."
  "${ROOT_DIR}/scripts/build-ios-xcframework.sh"
fi

rm -rf "${DERIVED_DIR}" "${STAGE_DIR}" "${IPA_PATH}"
mkdir -p "${BUILD_DIR}" "${STAGE_DIR}/Payload"

EXTRA_BUILD_ARGS=()
if [[ "${TAG}" =~ ^v([0-9]+\.[0-9]+(\.[0-9]+)?)$ ]]; then
  EXTRA_BUILD_ARGS+=("MARKETING_VERSION=${BASH_REMATCH[1]}")
fi

echo "Building HubanMobile (Release, generic/platform=iOS, unsigned)..."
xcodebuild \
  -project "${PROJECT}" \
  -scheme HubanMobile \
  -configuration Release \
  -sdk iphoneos \
  -destination 'generic/platform=iOS' \
  -derivedDataPath "${DERIVED_DIR}" \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_IDENTITY="" \
  AD_HOC_CODE_SIGNING_ALLOWED=YES \
  ${EXTRA_BUILD_ARGS[@]+"${EXTRA_BUILD_ARGS[@]}"} \
  build

APP_PATH="${DERIVED_DIR}/Build/Products/Release-iphoneos/HubanMobile.app"
if [ ! -d "${APP_PATH}" ]; then
  echo "Error: Built app not found at ${APP_PATH}" >&2
  exit 1
fi

cp -R "${APP_PATH}" "${STAGE_DIR}/Payload/HubanMobile.app"

echo "Packaging unsigned IPA: ${IPA_PATH}"
(
  cd "${STAGE_DIR}"
  zip -qry "${IPA_PATH}" Payload
)

rm -rf "${STAGE_DIR}"
echo "Successfully created ${IPA_PATH}"
ls -lh "${IPA_PATH}"
