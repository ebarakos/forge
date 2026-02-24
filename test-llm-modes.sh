#!/bin/bash
# Test LLM mode matchups: Heavy vs Heuristic, Light vs Heuristic, Heavy vs Light
# All on Cerebras (free tier, fast). Requires CEREBRAS_API_KEY in .env or env.
#
# Usage: ./test-llm-modes.sh [N_GAMES]

set -euo pipefail

N=${1:-10}
DECK1="Burn.dck"
DECK2="Dimir Faeries.dck"
MODEL="cerebras:llama3.1-8b"

# Helper: run simulation and extract only JSON from stdout (run.sh prefixes non-JSON lines)
run_sim() {
    ./run.sh "$@" 2>/dev/null | sed -n '/^{/,$p'
}

echo "========================================="
echo "LLM Mode Matchup Tests (${N} games each)"
echo "Provider: Cerebras  Model: llama3.1-8b"
echo "Decks: ${DECK1} vs ${DECK2}"
echo "========================================="
echo ""

echo ">>> [1/3] LLM-Heavy (Cerebras) vs Heuristic"
run_sim -P "1:${MODEL}@heavy" -d "$DECK1" -d "$DECK2" -n "$N" -q --json > heavy-vs-heuristic.json
echo "    Done → heavy-vs-heuristic.json"

echo ">>> [2/3] LLM-Light (Cerebras) vs Heuristic"
run_sim -P "1:${MODEL}@light" -d "$DECK1" -d "$DECK2" -n "$N" -q --json > light-vs-heuristic.json
echo "    Done → light-vs-heuristic.json"

echo ">>> [3/3] LLM-Heavy vs LLM-Light (both Cerebras)"
run_sim -P "1:${MODEL}@heavy" -P "2:${MODEL}@light" -d "$DECK1" -d "$DECK2" -n "$N" -q --json > heavy-vs-light.json
echo "    Done → heavy-vs-light.json"

echo ""
echo "========================================="
echo "RESULTS"
echo "========================================="

for f in heavy-vs-heuristic.json light-vs-heuristic.json heavy-vs-light.json; do
    echo ""
    echo "--- ${f} ---"
    python3 -c "
import json, sys
d = json.load(open('$f'))
s = d['summary']
print(f\"  Games: {s['totalGames']}  Draws: {s['draws']}  Time: {s['totalTimeMs']/1000:.1f}s\")
for p in s['players']:
    llm = p.get('llmUsage')
    if llm:
        mode = llm.get('mode', '?')
        calls = llm.get('totalCalls', 0)
        fb = llm.get('totalFallbacks', 0)
        tokens = llm.get('totalInputTokens', 0) + llm.get('totalOutputTokens', 0)
        latency = llm.get('totalLatencyMs', 0) / 1000
        cost = llm.get('estimatedCost', 0)
        cg = calls / max(s['totalGames'], 1)
        print(f\"  P{p['playerIndex']+1} {p['name']}: {p['winRate']:.1f}% wins ({p['wins']}/{s['totalGames']}) CI[{p.get('winRateCiLower',0):.1f}-{p.get('winRateCiUpper',0):.1f}] | LLM {mode}: {calls} calls ({cg:.0f}/game), {fb} fallbacks, {tokens} tokens, {latency:.1f}s, \${cost:.4f}\")
    else:
        print(f\"  P{p['playerIndex']+1} {p['name']}: {p['winRate']:.1f}% wins ({p['wins']}/{s['totalGames']}) CI[{p.get('winRateCiLower',0):.1f}-{p.get('winRateCiUpper',0):.1f}] | Heuristic\")
"
done

echo ""
echo "========================================="
echo "Done. JSON files: heavy-vs-heuristic.json, light-vs-heuristic.json, heavy-vs-light.json"
