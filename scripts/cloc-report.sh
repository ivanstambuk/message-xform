#!/usr/bin/env bash
# scripts/cloc-report.sh — Codebase size report for /init workflow
# Requires: cloc (https://github.com/AlDanial/cloc)
#
# Usage: scripts/cloc-report.sh [project-root]
#        Defaults to current directory if no argument given.

set -euo pipefail

ROOT="${1:-.}"

# Colors
BOLD="\033[1m"
DIM="\033[2m"
CYAN="\033[36m"
GREEN="\033[32m"
YELLOW="\033[33m"
RESET="\033[0m"

if ! command -v cloc &>/dev/null; then
    echo "⚠️  cloc not installed. Install with: sudo apt-get install cloc"
    exit 1
fi

echo -e "${BOLD}📊 Codebase Size Report${RESET}"
echo "────────────────────────────────────────────"

# --- Production Code ---
prod_output=$(cloc "$ROOT/core/src/main/java" --quiet --csv 2>/dev/null | tail -1)
prod_files=$(echo "$prod_output" | cut -d, -f1)
prod_blank=$(echo "$prod_output" | cut -d, -f3)
prod_comment=$(echo "$prod_output" | cut -d, -f4)
prod_code=$(echo "$prod_output" | cut -d, -f5)

# --- Test Code ---
test_output=$(cloc "$ROOT/core/src/test/java" --quiet --csv 2>/dev/null | tail -1)
test_files=$(echo "$test_output" | cut -d, -f1)
test_blank=$(echo "$test_output" | cut -d, -f3)
test_comment=$(echo "$test_output" | cut -d, -f4)
test_code=$(echo "$test_output" | cut -d, -f5)

# --- Total ---
total_files=$((prod_files + test_files))
total_code=$((prod_code + test_code))
total_comment=$((prod_comment + test_comment))

# --- Test ratio ---
if [ "$total_code" -gt 0 ]; then
    test_ratio=$(echo "scale=1; $test_code * 100 / $total_code" | bc)
else
    test_ratio="0"
fi

# --- Table output ---
printf "\n"
printf "%-20s %8s %8s %8s %8s\n" "Component" "Files" "Code" "Comment" "Blank"
printf "%-20s %8s %8s %8s %8s\n" "────────────────────" "────────" "────────" "────────" "────────"
printf "%-20s ${GREEN}%8s %8s${RESET} %8s %8s\n" "Production (src)" "$prod_files" "$prod_code" "$prod_comment" "$prod_blank"
printf "%-20s ${CYAN}%8s %8s${RESET} %8s %8s\n" "Tests" "$test_files" "$test_code" "$test_comment" "$test_blank"
printf "%-20s %8s %8s %8s %8s\n" "────────────────────" "────────" "────────" "────────" "────────"
printf "${BOLD}%-20s %8s %8s %8s${RESET}\n" "Total" "$total_files" "$total_code" "$total_comment"
printf "\n"
printf "Test-to-total ratio: ${YELLOW}%s%%${RESET}  (test lines / total lines)\n" "$test_ratio"
printf "Language: Java\n"
echo "────────────────────────────────────────────"
