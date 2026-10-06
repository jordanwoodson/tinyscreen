#!/usr/bin/env bash
# Works out the next release version from the git tags. CI runs it with --write before every
# release build (.github/workflows/release.yml); run it without arguments to see what the next
# release would be.
#
#   version name  MAJOR.MINOR.N   N counts up from the newest vMAJOR.MINOR.* tag
#   version code  MAJOR * 1_000_000 + MINOR * 1_000 + N    (1.0.5 -> 1000005, 1.1.0 -> 1001000)
#
# Android refuses an update whose version code is lower than the installed one. Deriving the code
# from the version name keeps it rising across 1.0.x -> 1.1.x and doesn't depend on git history,
# which can be rewritten. Releases up to 1.0.4 used the bare patch number (1-4) as their code;
# every code from this scheme is higher. The script refuses to produce a code that isn't higher
# than the newest tag's, so a mistake fails the build instead of publishing an APK the phone
# can't install. Don't delete release tags: the next release would reuse that number.
set -euo pipefail

# The release line. To start 1.1.x, change this to 1.1 (the first release is then 1.1.0).
VERSION_LINE=${VERSION_LINE:-1.0}

write=false
case "${1:-}" in
  --write) write=true ;;
  "") ;;
  *) echo "usage: $0 [--write]" >&2; exit 2 ;;
esac

cd "$(dirname "$0")/.."

if ! [[ $VERSION_LINE =~ ^([0-9]+)\.([0-9]+)$ ]]; then
  echo "VERSION_LINE must look like 1.0, got '$VERSION_LINE'" >&2
  exit 1
fi
major=$((10#${BASH_REMATCH[1]}))
minor=$((10#${BASH_REMATCH[2]}))

code_of() { # MAJOR.MINOR.PATCH -> version code
  local IFS=.
  # shellcheck disable=SC2086 # split on dots on purpose
  set -- $1
  echo $((10#$1 * 1000000 + 10#$2 * 1000 + 10#$3))
}

tags=$(git tag --list 'v*' | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' || true)
newest_tag=$(sort -V <<< "$tags" | tail -n 1)

last_patch=$(sed -nE "s/^v$major\.$minor\.([0-9]+)$/\1/p" <<< "$tags" | sort -n | tail -n 1)
if [ -n "$last_patch" ]; then
  patch=$((10#$last_patch + 1))
else
  patch=0
fi

if [ "$minor" -gt 999 ] || [ "$patch" -gt 999 ]; then
  echo "::error::$major.$minor.$patch doesn't fit the version code scheme; start a new release line" >&2
  exit 1
fi

version="$major.$minor.$patch"
code=$(code_of "$version")
if [ "$code" -gt 2100000000 ]; then
  echo "::error::version code $code is above Android's limit" >&2
  exit 1
fi
if [ -n "$newest_tag" ]; then
  newest_code=$(code_of "${newest_tag#v}")
  if [ "$code" -le "$newest_code" ]; then
    echo "::error::$version (code $code) isn't newer than $newest_tag (code $newest_code); raise VERSION_LINE in $0" >&2
    exit 1
  fi
fi

echo "Next release: $version (version code $code) after ${newest_tag:-nothing}"

if $write; then
  properties=app/gradle.properties
  sed -i "s/^version_name=.*/version_name=$version/; s/^version_code=.*/version_code=$code/" "$properties"
  if ! grep -qx "version_name=$version" "$properties" || ! grep -qx "version_code=$code" "$properties"; then
    echo "::error::couldn't set the version in $properties" >&2
    exit 1
  fi
  if [ -n "${GITHUB_ENV:-}" ]; then
    {
      echo "VERSION=$version"
      echo "VERSION_CODE=$code"
      echo "LAST_TAG=$newest_tag"
    } >> "$GITHUB_ENV"
  fi
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    {
      echo "version=$version"
      echo "version_code=$code"
    } >> "$GITHUB_OUTPUT"
  fi
fi
