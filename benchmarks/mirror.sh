#!/usr/bin/env bash
# benchmarks/mirror.sh — run a mirror match (same profile + same deck, both sides)
# and report seat-1 win rate with Wilson 95% CI plus fallback rate for LLM profiles.
#
# Usage:
#   benchmarks/mirror.sh <profile> <deck> [-n N] [-j J] [-B BASE_DIR]
#
# Examples:
#   benchmarks/mirror.sh heuristic Burn.dck
#   benchmarks/mirror.sh "cerebras:llama3.1-8b" Burn.dck -n 50 -j 4
#
set -euo pipefail

if [[ $# -lt 2 ]]; then
    sed -n '2,12p' "$0"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RESULTS_DIR="$SCRIPT_DIR/results"
mkdir -p "$RESULTS_DIR"

PROFILE="$1"; shift
DECK="$1"; shift

N=200
J=8
BASE_DIR="user/decks"

while [[ $# -gt 0 ]]; do
    case "$1" in
        -n) N="$2"; shift 2 ;;
        -j) J="$2"; shift 2 ;;
        -B) BASE_DIR="$2"; shift 2 ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

# heuristic = no -P override → uses Default profile both sides
SIM_ARGS=( -d "$DECK" -d "$DECK" -B "$BASE_DIR" -n "$N" -j "$J" -q --json )
if [[ "$PROFILE" != "heuristic" ]]; then
    SIM_ARGS=( -P "1:$PROFILE" -P "2:$PROFILE" "${SIM_ARGS[@]}" )
fi

# Slug profile + deck for the result filename
slug() {
    echo "$1" | tr ':/ @' '____' | tr -dc 'a-zA-Z0-9_.-'
}
PROFILE_SLUG="$(slug "$PROFILE")"
DECK_SLUG="$(slug "$(basename "$DECK" .dck)")"
TS="$(date +%Y%m%d-%H%M%S)"
OUT_FILE="$RESULTS_DIR/${DECK_SLUG}-${PROFILE_SLUG}-${TS}.json"

echo "==> mirror: profile=$PROFILE  deck=$DECK  n=$N  j=$J" >&2
echo "==> output: $OUT_FILE" >&2

cd "$REPO_ROOT"
# run.sh prints "==> Running:" to stdout before the JAR's JSON output;
# strip that and any other non-JSON preamble lines.
./run.sh --run-only "${SIM_ARGS[@]}" 2>/dev/null \
    | sed -n '/^{/,$p' > "$OUT_FILE"

python3 - "$OUT_FILE" "$PROFILE" "$DECK" <<'PY'
import json, sys, math

path, profile, deck = sys.argv[1], sys.argv[2], sys.argv[3]
with open(path) as f:
    data = json.load(f)

s = data["summary"]
players = s["players"]
games = s["totalGames"]
draws = s["draws"]
decisive = max(games - draws, 0)

p1 = next((p for p in players if p["playerIndex"] == 0), {})
p2 = next((p for p in players if p["playerIndex"] == 1), {})

p1_wins = p1.get("wins", 0)
p2_wins = p2.get("wins", 0)
ci = (p1.get("winRateCiLower", 0.0), p1.get("winRateCiUpper", 0.0))

# Mirror-fairness check: in a true mirror match the seat-1 win rate
# (over decisive games) should be roughly 50%. Anything outside the
# Wilson CI of 50% is a first-player bias signal worth surfacing.
def wilson(k, n, z=1.96):
    if n == 0:
        return (0.0, 0.0)
    p = k / n
    denom = 1 + z * z / n
    centre = (p + z * z / (2 * n)) / denom
    rad = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denom
    return ((centre - rad) * 100, (centre + rad) * 100)

if decisive > 0:
    seat1_rate = p1_wins / decisive * 100
    lo, hi = wilson(p1_wins, decisive)
else:
    seat1_rate, lo, hi = 0.0, 0.0, 0.0

llm = p1.get("llmUsage") or p2.get("llmUsage")
fallback_pct = None
if llm and llm.get("totalCalls", 0) > 0:
    total_calls = (p1.get("llmUsage") or {}).get("totalCalls", 0) + \
                  (p2.get("llmUsage") or {}).get("totalCalls", 0)
    total_fb = (p1.get("llmUsage") or {}).get("totalFallbacks", 0) + \
               (p2.get("llmUsage") or {}).get("totalFallbacks", 0)
    if total_calls > 0:
        fallback_pct = 100.0 * total_fb / total_calls

print(f"\n=== MIRROR: profile={profile}  deck={deck}  n={games} ===")
print(f"  Games:    {games}  (decisive {decisive}, draws {draws})")
print(f"  Seat 1:   {p1_wins} wins ({seat1_rate:5.1f}%)  CI95 [{lo:5.1f}%, {hi:5.1f}%]")
print(f"  Seat 2:   {p2_wins} wins")
fairness_ok = lo <= 50.0 <= hi
if decisive >= 20:
    if fairness_ok:
        print(f"  Fairness: OK (50% inside CI)")
    else:
        print(f"  Fairness: SKEWED (CI excludes 50% — first-player bias for this deck)")
else:
    print(f"  Fairness: undersample (need >= 20 decisive games)")
if fallback_pct is not None:
    flag = "" if fallback_pct < 25 else "  [HIGH — LLM falling back to heuristic]"
    print(f"  LLM fallback rate: {fallback_pct:.1f}%{flag}")
print(f"  Avg game time: {s.get('averageGameTimeMs', 0):.0f}ms")
PY
