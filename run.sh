#!/usr/bin/env bash
#
# Launches the approval engine on Java 8 without touching your shell config.
#
# Your ~/.zshrc exports JAVA_HOME to a JDK 8 path that no longer exists, which breaks
# `mvn` globally. Rather than edit that, we resolve a real JDK 8 here and export it for
# this process only.
#
set -euo pipefail
cd "$(dirname "$0")"

resolve_java8() {
  # Preferred: ask macOS for a 1.8 JVM.
  if [[ -x /usr/libexec/java_home ]]; then
    if home="$(/usr/libexec/java_home -v 1.8 2>/dev/null)"; then
      echo "$home"; return 0
    fi
  fi
  # Fallback: scan the usual install roots.
  for dir in "$HOME/Library/Java/JavaVirtualMachines"/* /Library/Java/JavaVirtualMachines/*; do
    [[ -x "$dir/Contents/Home/bin/javac" ]] || continue
    if "$dir/Contents/Home/bin/java" -version 2>&1 | grep -q '"1\.8'; then
      echo "$dir/Contents/Home"; return 0
    fi
  done
  return 1
}

if ! JAVA_HOME="$(resolve_java8)"; then
  echo "error: no JDK 8 found. Install one (e.g. Amazon Corretto 8) and re-run." >&2
  exit 1
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

echo "JAVA_HOME → $JAVA_HOME"
java -version
echo

MVN="./mvnw"
[[ -x "$MVN" ]] || MVN="mvn"

exec "$MVN" spring-boot:run "$@"
