#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
sdk_dir="$project_dir/.matter-sdk/connectedhomeip"
read -r revision sdk_tag ndk_version < <(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(d["commit"],d["tag"],d["ndk"])' "$project_dir/scripts/matter-sdk.lock.json")
if [[ ! -d "$sdk_dir/.git" ]]; then
  git clone --depth 1 --branch "$sdk_tag" https://github.com/project-chip/connectedhomeip.git "$sdk_dir"
fi
[[ "$(git -C "$sdk_dir" rev-parse HEAD)" == "$revision" ]] || { echo 'Неверная версия Matter SDK'; exit 1; }
cd "$sdk_dir"
if git apply --check "$project_dir/patches/connectedhomeip-eluno.patch" 2>/dev/null; then
  git apply "$project_dir/patches/connectedhomeip-eluno.patch"
else
  git apply --reverse --check "$project_dir/patches/connectedhomeip-eluno.patch"
fi
python3 scripts/checkout_submodules.py --platform android --shallow --jobs 8
# SDK bootstrap expects unset shell variables in its own scripts.
set +u
source scripts/activate.sh
set -u
android_sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
android_ndk="$android_sdk/ndk/$ndk_version"
[[ -d "$android_ndk" ]] || { echo 'Установите Android NDK 28.2.13676358'; exit 1; }
gn gen out/eluno-arm64 --args="target_os=\"android\" target_cpu=\"arm64\" android_ndk_root=\"$android_ndk\" android_sdk_root=\"$android_sdk\" is_debug=false chip_config_network_layer_ble=true chip_build_tests=false chip_error_logging=false chip_progress_logging=false chip_detail_logging=false"
ninja -C out/eluno-arm64 -j 6 lib/jni/arm64-v8a/libCHIPController.so
mkdir -p "$project_dir/.matter-sdk/jniLibs/arm64-v8a" "$project_dir/.matter-sdk/assets/paa"
cp out/eluno-arm64/lib/jni/arm64-v8a/libCHIPController.so "$project_dir/.matter-sdk/jniLibs/arm64-v8a/"
ndk_host=darwin-x86_64
[[ "$(uname -s)" != Linux ]] || ndk_host=linux-x86_64
cp "$android_ndk/toolchains/llvm/prebuilt/$ndk_host/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$project_dir/.matter-sdk/jniLibs/arm64-v8a/"
cp credentials/production/paa-root-certs/*.der "$project_dir/.matter-sdk/assets/paa/"
printf '%s\n' "$revision" > "$project_dir/.matter-sdk/revision"
shasum -a 256 "$project_dir/.matter-sdk/jniLibs/arm64-v8a/"*.so > "$project_dir/.matter-sdk/native-sha256.txt"

shasum -a 256 "$project_dir/patches/connectedhomeip-eluno.patch" | cut -d ' ' -f 1 > "$project_dir/.matter-sdk/patch-sha256"
