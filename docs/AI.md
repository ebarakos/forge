# Forge AI

The AI uses heuristic-based decision making (not machine learning). It works best with aggro and midrange decks, is okay with control, and struggles with complex combos.

## AI Profiles

Located in `forge-gui/res/ai/`:

| Profile | Description |
|---------|-------------|
| **Default** | Standard behavior |
| **Cautious** | Conservative play style |
| **Reckless** | Aggressive play style |
| **Enhanced** | Deeper simulation (depth 6), transposition tables, loop detection |
| **Ascended** | Maximum depth (6), longer time limits (8s), combo/synergy detection |
| **AlwaysPass** | Testing profile that passes priority |

## CLI Simulation

Run AI vs AI matches from the command line:

```bash
java -cp forge-gui-desktop.jar forge.view.SimulateMatch \
  -d deck1.dck deck2.dck -n 100
```

### Flags

| Flag | Description |
|------|-------------|
| `-d <deck1> <deck2>` | Deck files to use |
| `-n <N>` | Number of games (default: 1) |
| `-m <M>` | Best of M matches (overrides -n) |
| `-f <format>` | Game format: constructed, Commander, Oathbreaker, etc. |
| `-t <type>` | Tournament: Bracket, RoundRobin, Swiss |
| `-p <N>` | Players per match in tournament mode |
| `-q` | Quiet mode (suppress game logs) |
| `-c <S>` | Clock limit in seconds (default: 120) |
| `-s` | Enable snapshot restore for faster games |
| `-j <N>` | Parallel execution with N threads |
| `-P1 <profile>` | AI profile for player 1 |
| `-P2 <profile>` | AI profile for player 2 |
| `-B <dir>` | Base directory for relative deck paths |

### Examples

Basic 100-game test:
```bash
java -cp forge.jar forge.view.SimulateMatch -d deck1.dck deck2.dck -n 100
```

Fast parallel simulation with Ascended AI:
```bash
java -cp forge.jar forge.view.SimulateMatch \
  -d deck1.dck deck2.dck -n 100 -s -j 8 -P1 Ascended -q
```

Swiss tournament:
```bash
java -cp forge.jar forge.view.SimulateMatch \
  -D /path/to/decks/ -m 3 -t Swiss -p 2
```

## LLM seats

A seat can be played by a language model instead of the heuristic AI, by giving
it an LLM profile (`-P 1:cerebras:some-model`). Every switch below is read the
same way: the `forge.llm.env.<VAR>` system property first, then the process
environment, then a `.env` file in the working directory or any parent. A `.env`
left over from an earlier run is therefore still in force.

### Which decisions the model actually makes

An LLM seat hands eleven decisions back to the heuristic AI unless told not to.
Each is on by default; `FORGE_LLM_UNMUZZLED=1` turns the whole set off at once,
and naming one variable explicitly wins over the aggregate in either direction.
Truthiness is the same everywhere: anything except `0` or `false` counts as on.

| Variable | On (the default) means |
|----------|------------------------|
| `FORGE_LLM_UNMUZZLED` | Aggregate: truthy gives every decision below to the model |
| `FORGE_LLM_TRUST_HEURISTIC_TOP` | Replace the model's first plan step with the heuristic's own pick whenever they differ |
| `FORGE_LLM_TOPK` | How many spell options the model is shown (`8`; `0` means no cap, and is the default when unmuzzled) |
| `FORGE_LLM_PRUNE_HOPELESS` | Hide options the heuristic calls fundamentally bad |
| `FORGE_LLM_SINGLE_OPTION_SHORTCUT` | With exactly one playable spell, let the heuristic decide and skip the call |
| `FORGE_LLM_PLAN_STEP_APPROVAL` | Re-ask the heuristic before each step of the model's own plan |
| `FORGE_LLM_EMPTY_PLAN_OVERRIDE` | When the model chose to hold, cast anyway if the heuristic is willing |
| `FORGE_LLM_HEURISTIC_LAND_DROPS` | The heuristic picks which land to play, and when |
| `FORGE_LLM_HEURISTIC_TARGETS` | The heuristic decides where each spell points |
| `FORGE_LLM_HARDCODED_PLAY_FIRST` | Always choose to play first, without asking |
| `FORGE_LLM_INSTANT_SPEED_GATE` | Outside the seat's own main phases the heuristic decides, unless there is a spell on the stack to answer or an attack to answer |
| `FORGE_LLM_COMBAT` | Who declares attackers and blockers: `heuristic` (default), `llm`, or `shadow` (model consulted, divergence logged, heuristic applied) |

Turning the gate off costs tokens as well as changing behaviour: every non-main
priority with a castable instant becomes an LLM call.

### Failure switches

| Variable | Purpose |
|----------|---------|
| `FORGE_LLM_STRICT` | Stop the run the first time an LLM seat falls back to the heuristic AI, instead of finishing and reporting a normal-looking result. Command-line runs exit `4`; anywhere else the abort throws so the host can report it |
| `FORGE_LLM_DEBUG` | Log prompts, responses and fallbacks to stderr |
| `FORGE_LLM_SHADOW` | Log heuristic-versus-model divergence per call without changing behaviour |

An LLM call that returns an answer nobody can parse counts as a fallback, the
same as a timeout or an HTTP error — otherwise a run in which the heuristic
quietly played every turn would report zero fallbacks.

## Decision trace and board dumps

| Variable | Purpose |
|----------|---------|
| `FORGE_AI_DECISION_LOG` | Write the heuristic AI's decisions to stderr: one `[AI DECISION]` line per priority saying what was considered, the verdict on each option and which internal check produced it, plus `[AI MULLIGAN]` and `[AI COMBAT]` records. Off, it costs one boolean test |
| `FORGE_AI_STATE_DUMP_DIR` | Directory for board dumps. Unset (the default) turns dumping off; needs the decision trace on |
| `FORGE_AI_STATE_DUMP_CARDS` | Which refusals are worth a board, as `Name\|description-substring\|min-mana-sources` entries joined by `;` |

A dumped board is written in the `GameState` text format and can be replayed
with the `attribute` subcommand. Each dump carries a `# sha256=` header covering
every non-comment line below it, because a dump cut short mid-write is a valid,
restorable board of a *different* position and agrees with itself at every
check. `attribute` refuses a file whose checksum is present and wrong, and
accepts one with no checksum at all — hand-written boards are legitimate inputs
— saying so on stderr.

A decision whose option list the AI time budget ended early carries a
`truncated` field, so a pass forced by the clock cannot be read as a judgement
about the board.

## Synergy Detection (Ascended Profile)

The Ascended profile includes detection for:
- **Graveyard synergy** - Reanimator, dredge strategies
- **Sacrifice synergy** - Aristocrats patterns
- **+1/+1 counter synergy** - Counter-based strategies
- **Tribal synergy** - 15 creature types recognized
- **Mana doublers** - 17 cards including virtual doublers (Seedborn Muse, Wilderness Reclamation)
