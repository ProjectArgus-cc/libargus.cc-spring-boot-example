#!/bin/sh
# ----------------------------------------------------------------------------
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
# ----------------------------------------------------------------------------

set -euf
[ "${MVNW_VERBOSE-}" != true ] || set -x

native_path() { printf %s\\n "$1"; }
case "$(uname)" in
CYGWIN* | MINGW*)
  if command -v cygpath >/dev/null 2>&1; then
    native_path() { cygpath -m "$1"; }
  fi
  ;;
esac

set -- "$@"

wrapper_dir="${0%/*}"
if [ "$wrapper_dir" = "$0" ]; then
  wrapper_dir=.
fi
wrapper_dir="$(cd "$wrapper_dir" && pwd)"

wrapper_props="$wrapper_dir/.mvn/wrapper/maven-wrapper.properties"
distributionUrl=""
distributionSha256Sum=""
if [ -f "$wrapper_props" ]; then
  while IFS="=" read -r key value; do
    case "$key" in
    distributionUrl) distributionUrl=$(printf "%s" "$value" | tr -d "\r") ;;
    distributionSha256Sum) distributionSha256Sum=$(printf "%s" "$value" | tr -d "\r") ;;
    esac
  done <"$wrapper_props"
fi

if [ -z "$distributionUrl" ]; then
  echo "Error: distributionUrl is not defined in $wrapper_props" >&2
  exit 1
fi

dist_filename="${distributionUrl##*/}"
dist_name="${dist_filename%-bin.*}"

m2_base="${MAVEN_USER_HOME:-$HOME/.m2}"
install_base="$m2_base/wrapper/dists/$dist_name"
url_hash=$(printf "%s" "$distributionUrl" | sha256sum 2>/dev/null | cut -c1-16 || printf "default")
target_dir="$install_base/$url_hash"
mvn_bin="$target_dir/$dist_name/bin/mvn"

if [ ! -x "$mvn_bin" ]; then
  mkdir -p "$target_dir"
  tmp_zip="$target_dir/$dist_filename.part.$$"
  trap 'rm -f "$tmp_zip"' EXIT INT TERM
  if [ "${MVNW_VERBOSE-}" = true ]; then
    echo "Downloading Maven from $distributionUrl ..." >&2
  fi
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$tmp_zip" "$distributionUrl"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$tmp_zip" "$distributionUrl"
  else
    echo "Error: Neither curl nor wget is available to download Maven." >&2
    exit 1
  fi
  if [ -n "$distributionSha256Sum" ]; then
    actual_sha=$(sha256sum "$tmp_zip" | awk '{print $1}')
    if [ "$actual_sha" != "$distributionSha256Sum" ]; then
      echo "Error: SHA-256 mismatch for $distributionUrl" >&2
      exit 1
    fi
  fi
  unzip -q -o "$tmp_zip" -d "$target_dir"
  rm -f "$tmp_zip"
  chmod +x "$mvn_bin"
fi

export MAVEN_PROJECTBASEDIR="${MAVEN_BASEDIR:-$wrapper_dir}"
exec "$mvn_bin" "$@"
