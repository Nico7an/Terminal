#!/usr/bin/env bash
# Regenerates app/src/main/jniLibs from the Termux packages of proot (GPL-2.0, https://github.com/termux/proot),
# talloc (LGPL-3.0) and libandroid-shmem (BSD-3-Clause). The binaries are committed: Termux drops old versions.
# Needs curl, ar, tar and patchelf.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=https://packages-cf.termux.dev/apt/termux-main
OUT=app/src/main/jniLibs
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

for pair in aarch64:arm64-v8a x86_64:x86_64; do
  arch=${pair%%:*}; abi=${pair##*:}
  curl -fsS "$REPO/dists/stable/main/binary-$arch/Packages.gz" | zcat > "$TMP/Packages"
  for p in proot libtalloc libandroid-shmem; do
    file=$(awk -v RS= -v p="$p" '$0 ~ "^Package: "p"\n"' "$TMP/Packages" | sed -n 's/^Filename: //p' | head -1)
    echo "$abi: $file"
    mkdir -p "$TMP/$arch/$p"
    (cd "$TMP/$arch/$p" && curl -fsS -o pkg.deb "$REPO/$file" && ar x pkg.deb && tar xf data.tar.*)
  done
  usr=data/data/com.termux/files/usr
  mkdir -p "$OUT/$abi"
  # Native libraries must be named lib*.so to be extracted (and executable) in nativeLibraryDir.
  cp "$TMP/$arch/proot/$usr/bin/proot" "$OUT/$abi/libproot.so"
  cp "$TMP/$arch/proot/$usr/libexec/proot/loader" "$OUT/$abi/libproot-loader.so"
  cp "$TMP/$arch/libtalloc/$usr/lib/libtalloc.so."*.*.* "$OUT/$abi/libtalloc.so"
  cp "$TMP/$arch/libandroid-shmem/$usr/lib/libandroid-shmem.so" "$OUT/$abi/libandroid-shmem.so"
  patchelf --set-soname libtalloc.so "$OUT/$abi/libtalloc.so"
  patchelf --replace-needed libtalloc.so.2 libtalloc.so "$OUT/$abi/libproot.so"
  for f in libproot.so libtalloc.so libandroid-shmem.so; do patchelf --remove-rpath "$OUT/$abi/$f"; done
done
ls -l "$OUT"/*
