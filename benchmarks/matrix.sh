#!/usr/bin/env bash
# benchmarks/matrix.sh — run a matchup matrix across {profileA, profileB} × decks.
# Reports profileA's win rate vs profileB on each deck pair with Wilson 95% CI.
#
# Usage:
#   benchmarks/matrix.sh <profileA> <profileB> [-n N] [-j J] [-B BASE_DIR] [-d deck1.dck -d deck2.dck ...]
#
# Examples:
#   # Heuristic vs LLM Cerebras across 4 deck matchups, 100 games each
#   benchmarks/matrix.sh heuristic "cerebras:qwen-3-235b-a22b-instruct-2507" -n 100
#
#   # Custom deck pairings
#   benchmarks/matrix.sh "cerebras:qwen-3-235b-a22b-instruct-2507" heuristic \
#       -d "Burn.dck" -d "Dimir Faeries.dck"
#
set -euo pipefail

if [[ $# -lt 2 ]]; then
    sed -n '2,15p' "$0"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RESULTS_DIR="$SCRIPT_DIR/results"
mkdir -p "$RESULTS_DIR"

PROFILE_A="$1"; shift
PROFILE_B="$1"; shift

N=100
J=4
BASE_DIR="user/decks"
DECKS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        -n) N="$2"; shift 2 ;;
        -j) J="$2"; shift 2 ;;
        -B) BASE_DIR="$2"; shift 2 ;;
        -d) DECKS+=("$2"); shift 2 ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

# Default deck list when -d not provided
if [[ ${#DECKS[@]} -eq 0 ]]; then
    DECKS=("Burn.dck" "Dimir Faeries.dck")
fi

if [[ ${#DECKS[@]} -lt 2 ]]; then
    echo "Need at least 2 decks for a matchup" >&2
    exit 1
fi

slug() {
    echo "$1" | tr ':/ @' '____' | tr -dc 'a-zA-Z0-9_.-'
}

A_SLUG="$(slug "$PROFILE_A")"
B_SLUG="$(slug "$PROFILE_B")"
TS="$(date +%Y%m%d-%H%M%S)"

run_pair() {
    local deck_a="$1" deck_b="$2"
    local out="$RESULTS_DIR/matrix-${A_SLUG}-vs-${B_SLUG}-$(slug "$(basename "$deck_a" .dck)")-$(slug "$(basename "$deck_b" .dck)")-${TS}.json"
    local args=( -d "$deck_a" -d "$deck_b" -B "$BASE_DIR" -n "$N" -j "$J" -q --json )
    if [[ "$PROFILE_A" != "heuristic" ]]; then args=( -P "1:$PROFILE_A" "${args[@]}" ); fi
    if [[ "$PROFILE_B" != "heuristic" ]]; then args=( -P "2:$PROFILE_B" "${args[@]}" ); fi
    cd "$REPO_ROOT"
    ./run.sh --run-only "${args[@]}" 2>/dev/null | sed -n '/^{/,$p' > "$out"
    echo "$out"
}

declare -a OUTPUTS

echo "==> matrix: A=$PROFILE_A  B=$PROFILE_B  n=$N per matchup  j=$J"
for ((i = 0; i < ${#DECKS[@]}; i++)); do
    for ((j = 0; j < ${#DECKS[@]}; j++)); do
        if [[ $i -eq $j ]]; then continue; fi
        deck_a="${DECKS[$i]}"
        deck_b="${DECKS[$j]}"
        echo "  ($PROFILE_A on $deck_a)  vs  ($PROFILE_B on $deck_b)..."
        OUTPUTS+=( "$(run_pair "$deck_a" "$deck_b")" )
    done
done

python3 - "$PROFILE_A" "$PROFILE_B" "${OUTPUTS[@]}" <<'PY'
import json, sys

profile_a, profile_b = sys.argv[1], sys.argv[2]
files = sys.argv[3:]

print(f"\n=== MATRIX: A={profile_a}  B={profile_b} ===")
print(f"  {'Deck A':<24} {'Deck B':<24} {'A wins':>8} {'rate':>7}  {'CI95':<18}")
print(f"  {'-' * 24:<24} {'-' * 24:<24} {'-' * 8:>8} {'-' * 7:>7}  {'-' * 18:<18}")

a_total_wins = 0
total_games = 0
n_significant_better = 0
n_significant_worse = 0

for path in files:
    with open(path) as f:
        data = json.load(f)
    cfg = data["config"]; s = data["summary"]
    deck_a = cfg["decks"][0]
    deck_b = cfg["decks"][1]
    p1 = next((p for p in s["players"] if p["playerIndex"] == 0), {})
    a_wins = p1.get("wins", 0)
    n = s["totalGames"]
    rate = (a_wins / n * 100) if n > 0 else 0.0
    lo = p1.get("winRateCiLower", 0.0)
    hi = p1.get("winRateCiUpper", 0.0)
    a_total_wins += a_wins
    total_games += n
    if lo > 50:
        n_significant_better += 1
    if hi < 50:
        n_significant_worse += 1
    print(f"  {deck_a[:24]:<24} {deck_b[:24]:<24} {a_wins:>4}/{n:<3} {rate:6.1f}%  [{lo:5.1f}%, {hi:5.1f}%]")

print()
overall_rate = (a_total_wins / total_games * 100) if total_games > 0 else 0.0
print(f"  Aggregate A wins: {a_total_wins}/{total_games} = {overall_rate:.1f}%")
print(f"  Significantly better (CI>50%): {n_significant_better}/{len(files)} matchups")
print(f"  Significantly worse  (CI<50%): {n_significant_worse}/{len(files)} matchups")
PY
