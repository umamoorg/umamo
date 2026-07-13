#!/bin/bash
# Blocks any Claude Code tool call that touches /mnt

input=$(cat)

# Pull out the fields that might contain a path
file_path=$(echo "$input" | jq -r '.tool_input.file_path // empty')
command=$(echo "$input" | jq -r '.tool_input.command // empty')
notebook_path=$(echo "$input" | jq -r '.tool_input.notebook_path // empty')

# Check each for /mnt (with or without leading slash variants, and case just in case)
for candidate in "$file_path" "$command" "$notebook_path"; do
  if echo "$candidate" | grep -qE '(^|[[:space:]/])/mnt(/|$| )'; then
    echo "Blocked: this tool call references /mnt, which is off-limits in this project." >&2
    exit 2
  fi
done

exit 0