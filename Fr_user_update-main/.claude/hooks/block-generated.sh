#!/usr/bin/env bash
# PreToolUse guard: refuses Edit/Write against generated files.
# Paths mirror CLAUDE.md's "NEVER hand-edit generated files" hard rule.
set -euo pipefail

input="$(cat)"

file_path="$(printf '%s' "$input" | python -c "
import json, sys
try:
    data = json.load(sys.stdin)
except Exception:
    print('')
else:
    print(data.get('tool_input', {}).get('file_path', '') or '')
")"

if [ -z "$file_path" ]; then
  exit 0
fi

norm="${file_path//\\//}"

patterns=(
  '(^|/)\.dart_tool/'
  '(^|/)\.fvm/'
  '(^|/)mobile/build/'
  '(^|/)android/app/build/'
  '(^|/)ios/Flutter/Generated\.xcconfig$'
  '(^|/)ios/Runner/GeneratedPluginRegistrant\.[hm]$'
  '(^|/)pubspec\.lock$'
  '(^|/)backend/target/'
  '(^|/)\.mvn/wrapper/maven-wrapper\.jar$'
  '(^|/)backoffice/dist/'
  '(^|/)backoffice/node_modules/'
  '(^|/)backoffice/coverage/'
  '(^|/)backoffice/package-lock\.json$'
)

for pat in "${patterns[@]}"; do
  if [[ "$norm" =~ $pat ]]; then
    echo "BLOCKED: '$file_path' is a generated file (CLAUDE.md: NEVER hand-edit generated files). Edit the source and re-run the tool instead." >&2
    exit 2
  fi
done

exit 0
