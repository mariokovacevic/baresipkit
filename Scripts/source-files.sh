#!/usr/bin/env bash
set -euo pipefail

c_sources() {
  git ls-files 'Core/*.c' 'Core/*.h' 'Core/*.m' \
    'Apple/Sources/BaresipKitBridge/*.c' 'Apple/Sources/BaresipKitBridge/*.h' \
    'Core/Tests/*.c' 'Core/Tests/*.h' 'Core/Tests/Fuzz/*.c' 'Tools/*.c' \
    'Android/*.c' \
    ':!Core/third_party'
}

swift_sources() {
  git ls-files '*.swift'
}
