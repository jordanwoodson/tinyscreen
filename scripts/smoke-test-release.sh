#!/usr/bin/env bash
# Smoke-tests a minified release APK on a connected device or emulator. It installs the APK,
# launches the app, opens an embed, and fails if the app died or logcat shows a crash, an ANR or
# a typical R8 symptom (a class, method, field or serializer that shrinking removed or renamed).
#
#   scripts/smoke-test-release.sh -s SERIAL [options] [APK]
#
#   APK              the APK to test (default: app/build/outputs/apk/release/app-release.apk,
#                    built by ./gradlew :app:assembleRelease)
#   -s SERIAL        adb serial of the device or emulator (see adb devices -l); required
#   -w SECONDS       how long to watch the app after launching it and after each link (default 20)
#   --open URL       a link to open in the app once it's up; repeatable. The default opens a
#                    YouTube video so the resolver and player run. --open none skips this.
#   --ignore REGEX   ignore logcat lines matching REGEX (extended grep); repeatable
#   --replace        if the installed app is signed with another key (e.g. the CI release on a
#                    phone and a locally built APK), uninstall it first. This DELETES ITS DATA.
#   -o DIR           where to save logcat and a screenshot (default: app/build/smoke-test)
#
# Exit status: 0 passed, 1 the app failed, 2 the test couldn't run.
# A line that also shows up when testing the debug APK (app/build/outputs/apk/debug/) isn't
# caused by R8.
set -euo pipefail

cd "$(dirname "$0")/.."

usage() { sed -n '2,/^set -euo/{/^set -euo/d;s/^# \{0,1\}//;p}' "$0"; exit 2; }
die() { echo "smoke test: $*" >&2; exit 2; }

serial=""
wait_seconds=20
apk=""
out_dir="app/build/smoke-test"
replace=false
opens=()
ignores=()
default_open="https://www.destiny.gg/bigscreen#youtube/jNQXAC9IVRw"

while [ $# -gt 0 ]; do
  case "$1" in
    -s) serial=${2:?-s needs a serial}; shift 2 ;;
    -w) wait_seconds=${2:?-w needs seconds}; shift 2 ;;
    -o) out_dir=${2:?-o needs a directory}; shift 2 ;;
    --open) opens+=("${2:?--open needs a URL}"); shift 2 ;;
    --ignore) ignores+=("${2:?--ignore needs a regex}"); shift 2 ;;
    --replace) replace=true; shift ;;
    -h|--help) usage ;;
    -*) echo "unknown option $1" >&2; usage ;;
    *) [ -z "$apk" ] || usage; apk=$1; shift ;;
  esac
done
[ -n "$serial" ] || { echo "-s SERIAL is required" >&2; usage; }
[[ $wait_seconds =~ ^[0-9]+$ ]] || die "-w needs a whole number of seconds"
apk=${apk:-app/build/outputs/apk/release/app-release.apk}
[ -f "$apk" ] || die "no APK at $apk (build it with ./gradlew :app:assembleRelease)"
[ ${#opens[@]} -gt 0 ] || opens=("$default_open")
[ "${opens[*]}" != none ] || opens=()
for url in ${opens[@]+"${opens[@]}"}; do
  [[ $url != *"'"* ]] || die "links can't contain single quotes: $url"
done

# Android SDK tools: PATH first, then ANDROID_HOME / ANDROID_SDK_ROOT / local.properties
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$sdk" ] && [ -f local.properties ]; then
  sdk=$(sed -n 's/^sdk\.dir=//p' local.properties | tail -n 1)
fi
adb=$(command -v adb || true)
[ -n "$adb" ] || adb="$sdk/platform-tools/adb"
[ -x "$adb" ] || die "adb not found; put platform-tools on PATH or set ANDROID_HOME"
aapt2=""
if [ -n "$sdk" ] && [ -d "$sdk/build-tools" ]; then
  aapt2=$(find "$sdk/build-tools" -mindepth 2 -maxdepth 2 -name aapt2 | sort -V | tail -n 1)
fi

dev() { "$adb" -s "$serial" "$@"; }
dev_shell() { dev shell "$@" | tr -d '\r'; }

[ "$(dev get-state 2>/dev/null || true)" = device ] || die "device $serial isn't connected (adb devices -l)"
[ "$(dev_shell getprop sys.boot_completed)" = 1 ] || die "device $serial hasn't finished booting"

pkg=io.github.jordanwoodson.tinyscreen
if [ -n "$aapt2" ]; then
  badging=$("$aapt2" dump badging "$apk")
  pkg=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<< "$badging")
  if grep -q '^application-debuggable' <<< "$badging"; then
    echo "note: $apk is debuggable, so this isn't testing a minified release build"
  fi
fi
pkg_re=${pkg//./\\.}

echo "Installing $apk ($pkg) on $serial"
if ! install_log=$(dev install -r "$apk" 2>&1); then
  if $replace && grep -qE 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|INSTALL_FAILED_VERSION_DOWNGRADE' <<< "$install_log"; then
    echo "Uninstalling the installed $pkg (--replace)"
    dev uninstall "$pkg" > /dev/null
    install_log=$(dev install -r "$apk" 2>&1) || { echo "$install_log" >&2; die "install failed"; }
  else
    echo "$install_log" >&2
    if grep -qE 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|INSTALL_FAILED_VERSION_DOWNGRADE' <<< "$install_log"; then
      die "an incompatible $pkg is installed; rerun with --replace to uninstall it (deletes its data)"
    fi
    die "install failed"
  fi
fi

component=$(dev_shell cmd package resolve-activity --brief -a android.intent.action.MAIN \
  -c android.intent.category.LAUNCHER "$pkg" | tail -n 1)
[[ $component == */* ]] || die "couldn't find $pkg's launcher activity"

mkdir -p "$out_dir"
log_file="$out_dir/logcat.txt"
shot_file="$out_dir/screen.png"

failures=()
pids=()

# Watches the app for $1 seconds; records a failure and returns 1 if its process goes away
watch_app() {
  local i pid
  for ((i = 0; i < $1; i++)); do
    sleep 1
    pid=$(dev_shell pidof "$pkg" || true)
    if [ -z "$pid" ]; then
      failures+=("the app's process died")
      return 1
    fi
    if [ ${#pids[@]} -gt 0 ] && [[ " ${pids[*]} " != *" $pid "* ]]; then
      failures+=("the app's process restarted (pid ${pids[*]} -> $pid)")
      pids+=("$pid")
      return 1
    fi
    [ ${#pids[@]} -gt 0 ] || pids+=("$pid")
  done
}

dev_shell am force-stop "$pkg"
# adb shell joins its arguments into one command line, so anything with spaces is quoted inside
start=$(dev_shell "date '+%m-%d %H:%M:%S.000'")

echo "Launching $component, watching for ${wait_seconds}s"
launch=$(dev_shell am start -W -n "$component")
if ! grep -q 'Status: ok' <<< "$launch"; then
  echo "$launch"
  failures+=("am start didn't report Status: ok")
fi
alive=true
watch_app "$wait_seconds" || alive=false

for url in ${opens[@]+"${opens[@]}"}; do
  $alive || break
  echo "Opening $url, watching for ${wait_seconds}s"
  dev_shell "am start -W -a android.intent.action.VIEW -d '$url' -n $component" > /dev/null
  watch_app "$wait_seconds" || alive=false
done

dev exec-out screencap -p > "$shot_file" 2> /dev/null || rm -f "$shot_file"
dev_shell "logcat -d -v threadtime -b main -b system -b crash -T '$start'" > "$log_file" 2>&1 || true

# Also catch a process that crashed before the first check, from the system's own log lines
while read -r pid; do
  [[ " ${pids[*]-} " == *" $pid "* ]] || pids+=("$pid")
done < <(sed -nE -e "s/.*Start proc ([0-9]+):$pkg_re\/.*/\1/p" \
  -e "s/.*Process: $pkg_re, PID: ([0-9]+).*/\1/p" "$log_file")

filter_ignored() {
  local line_re
  if [ ${#ignores[@]} -eq 0 ]; then
    cat
  else
    line_re=$(IFS='|'; echo "${ignores[*]}")
    grep -Ev "$line_re" || true
  fi
}

# Lines logged by the app's process (threadtime: date time pid tid level tag: message)
app_lines=""
if [ ${#pids[@]} -gt 0 ]; then
  app_lines=$(awk -v pids=" ${pids[*]} " 'index(pids, " " $3 " ") > 0' "$log_file" | filter_ignored)
fi

crash=$(grep -E "FATAL EXCEPTION|Process: $pkg_re, PID" "$log_file" | filter_ignored || true)
if grep -q "Process: $pkg_re, PID" <<< "$crash"; then
  failures+=("the app crashed (FATAL EXCEPTION)")
fi
anr=$(grep -E "ANR in $pkg_re( |$)" "$log_file" | filter_ignored || true)
[ -z "$anr" ] || failures+=("the app stopped responding (ANR)")

r8_re='ClassNotFoundException|NoClassDefFoundError|NoSuchMethodError|NoSuchMethodException'
r8_re+='|NoSuchFieldError|NoSuchFieldException|AbstractMethodError|IncompatibleClassChangeError'
r8_re+='|VerifyError|ExceptionInInitializerError|Serializer for class .* is not found'
r8_re+='|SerializationException|MissingFieldException'
r8=$(grep -E "$r8_re" <<< "$app_lines" || true)
[ -z "$r8" ] || failures+=("logcat shows exceptions R8 typically causes")

if [ ${#failures[@]} -eq 0 ]; then
  echo "PASS: $pkg ran for $(( wait_seconds * (1 + ${#opens[@]}) ))s without crashing; logcat in $log_file"
  exit 0
fi

echo
echo "FAIL:"
printf '  - %s\n' "${failures[@]}"
show() { # title, lines
  [ -n "$2" ] || return 0
  echo
  echo "$1:"
  head -n 60 <<< "$2"
}
show "Crash" "$(grep -E ' AndroidRuntime *:' <<< "$app_lines" || true)"
show "ANR" "$anr"
show "R8 suspects (app process)" "$r8"
show "App errors" "$(awk '$5 == "E"' <<< "$app_lines")"
echo
echo "Full logcat: $log_file"
[ ! -f "$shot_file" ] || echo "Screenshot: $shot_file"
exit 1
