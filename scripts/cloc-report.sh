#!/usr/bin/env bash
# scripts/cloc-report.sh — Codebase size report for /init workflow
# Requires: cloc (https://github.com/AlDanial/cloc)
#
# Usage: scripts/cloc-report.sh [project-root]
#        Defaults to current directory if no argument given.
#
# Discovers source directories via `git ls-files` so that .gitignore'd
# content (build/, binaries/, etc.) is automatically excluded.
# Scans all Gradle modules dynamically.

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

# --- Build file lists from git (respects .gitignore) ---
prod_file_list=$(mktemp)
test_file_list=$(mktemp)
trap 'rm -f "$prod_file_list" "$test_file_list"' EXIT

(cd "$ROOT" && git ls-files -- '*/src/main/java/*.java') > "$prod_file_list"
(cd "$ROOT" && git ls-files -- '*/src/test/java/*.java') > "$test_file_list"

prod_count=$(wc -l < "$prod_file_list")
test_count=$(wc -l < "$test_file_list")

if [ "$prod_count" -eq 0 ] && [ "$test_count" -eq 0 ]; then
    echo "⚠️  No Java source files found in git-tracked src/ directories"
    exit 1
fi

# --- Production Code ---
if [ "$prod_count" -gt 0 ]; then
    prod_output=$(cd "$ROOT" && cloc --list-file="$prod_file_list" --quiet --csv 2>/dev/null | tail -1)
    prod_files=$(echo "$prod_output" | cut -d, -f1)
    prod_blank=$(echo "$prod_output" | cut -d, -f3)
    prod_comment=$(echo "$prod_output" | cut -d, -f4)
    prod_code=$(echo "$prod_output" | cut -d, -f5)
else
    prod_files=0; prod_blank=0; prod_comment=0; prod_code=0
fi

# --- Test Code ---
if [ "$test_count" -gt 0 ]; then
    test_output=$(cd "$ROOT" && cloc --list-file="$test_file_list" --quiet --csv 2>/dev/null | tail -1)
    test_files=$(echo "$test_output" | cut -d, -f1)
    test_blank=$(echo "$test_output" | cut -d, -f3)
    test_comment=$(echo "$test_output" | cut -d, -f4)
    test_code=$(echo "$test_output" | cut -d, -f5)
else
    test_files=0; test_blank=0; test_comment=0; test_code=0
fi

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

# --- Per-module breakdown ---
printf "\n${DIM}Modules scanned:${RESET}\n"

# Discover unique module roots from the file lists
while IFS= read -r module; do
    mod_list=$(mktemp)
    grep "^${module}/src/main/java/" "$prod_file_list" > "$mod_list" 2>/dev/null || true
    mod_count=$(wc -l < "$mod_list")
    if [ "$mod_count" -gt 0 ]; then
        mod_out=$(cd "$ROOT" && cloc --list-file="$mod_list" --quiet --csv 2>/dev/null | tail -1)
        mod_code=$(echo "$mod_out" | cut -d, -f5)
        mod_files=$(echo "$mod_out" | cut -d, -f1)
        printf "  ${GREEN}%-30s${RESET} %5s files  %6s code lines\n" "$module" "$mod_files" "$mod_code"
    fi
    rm -f "$mod_list"
done < <(sed 's|/src/main/java/.*||' "$prod_file_list" | sort -u)

while IFS= read -r module; do
    mod_list=$(mktemp)
    grep "^${module}/src/test/java/" "$test_file_list" > "$mod_list" 2>/dev/null || true
    mod_count=$(wc -l < "$mod_list")
    if [ "$mod_count" -gt 0 ]; then
        mod_out=$(cd "$ROOT" && cloc --list-file="$mod_list" --quiet --csv 2>/dev/null | tail -1)
        mod_code=$(echo "$mod_out" | cut -d, -f5)
        mod_files=$(echo "$mod_out" | cut -d, -f1)
        printf "  ${CYAN}%-30s${RESET} %5s files  %6s code lines\n" "${module} (test)" "$mod_files" "$mod_code"
    fi
    rm -f "$mod_list"
done < <(sed 's|/src/test/java/.*||' "$test_file_list" | sort -u)

printf "\nLanguage: Java\n"
echo "────────────────────────────────────────────"
