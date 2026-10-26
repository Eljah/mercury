#!/usr/bin/env sh
set -eu
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
binary=$(mktemp /tmp/mercury-codec-test.XXXXXX)
trap 'rm -f "$binary"' EXIT HUP INT TERM
"${CXX:-g++}" -std=c++11 -Wall -Wextra -Werror -pedantic \
  "$script_dir/test_codec.cpp" \
  "$script_dir/../MercuryLan/mercury_codec.cpp" -o "$binary"
"$binary"
