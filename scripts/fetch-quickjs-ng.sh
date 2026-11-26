#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_DIR="${ROOT_DIR}/third_party/quickjs-ng"
PINNED_TAG="v0.18.0"
PINNED_SHA="d7215b0ad3fc2c562e8317fd9bca058fd4863804"

if [ -d "${TARGET_DIR}/.git" ]; then
    CURRENT_SHA="$(git -C "${TARGET_DIR}" rev-parse HEAD)"
    if [ "${CURRENT_SHA}" = "${PINNED_SHA}" ]; then
        echo "quickjs-ng ${PINNED_TAG} (${PINNED_SHA}) already present."
        exit 0
    fi
    rm -rf "${TARGET_DIR}"
fi

mkdir -p "$(dirname "${TARGET_DIR}")"
git clone --depth 1 --branch "${PINNED_TAG}" https://github.com/quickjs-ng/quickjs.git "${TARGET_DIR}"
ACTUAL_SHA="$(git -C "${TARGET_DIR}" rev-parse HEAD)"
if [ "${ACTUAL_SHA}" != "${PINNED_SHA}" ]; then
    echo "ERROR: Expected commit ${PINNED_SHA} for ${PINNED_TAG}, got ${ACTUAL_SHA}" >&2
    exit 1
fi
echo "Verified quickjs-ng ${PINNED_TAG} (${ACTUAL_SHA})"
