# Forge NN Player — ONNX Model Contract

This document specifies the contract between Forge (the MTG simulation engine) and
external neural network models used via the `--nn-hybrid` or `--nn-full` CLI flags.

## Overview

Forge encodes game state and available options into a flat tensor, passes it to an
ONNX model, and interprets the output as a policy over legal actions. The model is
called once per decision point during gameplay.

---

## 1. ONNX Model Input/Output

### Input Tensor

- **Name:** `"input"`
- **Shape:** `[batch, 1760]`
- **Dtype:** `float32`

Layout (1760 floats):

| Offset | Size | Description |
|--------|------|-------------|
| 0 | 664 | Game state encoding (see Section 2) |
| 664 | 8 | Decision type one-hot (see Section 4) |
| 672 | 1024 | Option features flattened: 64 options x 16 features (see Section 3) |
| 1696 | 64 | Action mask: 1.0 = legal, 0.0 = illegal |

### Output Tensors

**Policy** (required):
- **Name:** `"policy"`
- **Shape:** `[batch, 64]`
- **Dtype:** `float32`
- **Semantics:** Logits over the 64 option slots. Only slots where `mask[i] == 1.0` are considered. Forge selects `argmax` over legal slots.

**Value** (optional):
- **Name:** `"value"`
- **Shape:** `[batch, 1]`
- **Dtype:** `float32`
- **Semantics:** Position evaluation in [-1, 1]. Currently unused by Forge — available for future extensions.

---

## 2. Game State Encoding (664 floats)

### Global Features (offset 0, size 24)

| Index | Feature | Normalization |
|-------|---------|---------------|
| 0 | My life | / 20.0 |
| 1 | Opponent life | / 20.0 |
| 2 | My hand size | / 7.0 |
| 3 | Opponent hand size | / 7.0 |
| 4 | My graveyard size | / 20.0 |
| 5 | Opponent graveyard size | / 20.0 |
| 6 | My library size | / 60.0 |
| 7 | Opponent library size | / 60.0 |
| 8 | Turn number | / 20.0 (clamped to 1.0) |
| 9 | Is my turn | 0.0 or 1.0 |
| 10-22 | Current phase | One-hot encoding (13 values) |
| 23 | My available mana | untapped lands / 10.0 |

### Phase One-Hot (indices 10-22)

| Index | Phase |
|-------|-------|
| 10 | UNTAP |
| 11 | UPKEEP |
| 12 | DRAW |
| 13 | MAIN1 |
| 14 | COMBAT_BEGIN |
| 15 | COMBAT_DECLARE_ATTACKERS |
| 16 | COMBAT_DECLARE_BLOCKERS |
| 17 | COMBAT_FIRST_STRIKE_DAMAGE |
| 18 | COMBAT_DAMAGE |
| 19 | COMBAT_END |
| 20 | MAIN2 |
| 21 | END_OF_TURN |
| 22 | CLEANUP |

### My Battlefield (offset 24, size 256 = 16 slots x 16 features)

Cards are sorted by importance: creatures first (by CMC descending), then non-creatures (by CMC descending). Extra permanents beyond 16 are dropped. Empty slots are zeroed.

### Opponent Battlefield (offset 280, size 256)

Same layout as my battlefield.

### My Hand (offset 536, size 128 = 8 slots x 16 features)

Same sorting and encoding as battlefield slots.

### Per-Card Features (16 floats per slot)

| Index | Feature | Values |
|-------|---------|--------|
| 0 | Present | 1.0 if card exists, 0.0 if empty slot |
| 1 | CMC | / 10.0 |
| 2 | Power | / 20.0 (0 for non-creatures) |
| 3 | Toughness | / 20.0 (0 for non-creatures) |
| 4 | Is creature | 0.0 or 1.0 |
| 5 | Is land | 0.0 or 1.0 |
| 6 | Is instant or sorcery | 0.0 or 1.0 |
| 7 | Is enchantment | 0.0 or 1.0 |
| 8 | Is artifact | 0.0 or 1.0 |
| 9 | Color: White | 0.0 or 1.0 |
| 10 | Color: Blue | 0.0 or 1.0 |
| 11 | Color: Black | 0.0 or 1.0 |
| 12 | Color: Red | 0.0 or 1.0 |
| 13 | Color: Green | 0.0 or 1.0 |
| 14 | Is tapped | 0.0 or 1.0 |
| 15 | Has summoning sickness | 0.0 or 1.0 |

---

## 3. Option Encoding

Options are encoded as `float[numOptions][16]` using the same per-card features as the game state encoder.

### Card-Based Options

Each option is encoded using its card features (same 16 floats as Section 2).

### SpellAbility Options

Encoded using the host card's features (`sa.getHostCard()`).

### Boolean Options

Always 2 options:
- Option 0 (yes/true): `[1, 0, 0, ..., 0]`
- Option 1 (no/false): `[0, 1, 0, ..., 0]`

### Number Range Options

For range `[min, max]`, produces `(max - min + 1)` options:
- Option i: `[(i - min) / (max - min), 0, ..., 0]`
- If `min == max`: single option with first feature = 1.0

### GameEntity Options

If the entity is a Card, uses card encoding. Otherwise, only sets `present = 1.0`.

---

## 4. Decision Types

One-hot encoded at input offset 664 (8 values):

| Index | Type | Description |
|-------|------|-------------|
| 0 | SPELL_SELECTION | Choose a spell/ability to play |
| 1 | MULLIGAN | Keep or mulligan hand |
| 2 | ATTACK | Declare attackers (per-creature binary) |
| 3 | BLOCK | Declare blockers |
| 4 | CARD_CHOICE | Pick card(s) from list (sacrifice, discard, target) |
| 5 | BOOLEAN | Yes/no decisions |
| 6 | NUMBER | Choose a number |
| 7 | GENERIC | Fallback for other decisions |

---

## 5. Training Data Format (JSONL)

When `--nn-export DIR` is used, Forge writes one `.jsonl` file per game:
`game_{UUID}_{timestamp}.jsonl`

### Decision Line

```json
{"type":"decision","turn":5,"phase":"MAIN1","decisionType":"SPELL_SELECTION","state":[...],"options":[[...],[...]],"numOptions":3,"chosenIndex":1}
```

### Outcome Line (last line)

```json
{"type":"outcome","result":1.0,"turns":12,"reason":"WinCondition"}
```

- `result`: 1.0 = win, 0.0 = loss (from the NN player's perspective)
- `reason`: Game end condition string

### Validation

Use the included Python script:

```bash
python3 forge-ai/src/test/resources/nn/validate_training_data.py /path/to/export/dir
```

---

## 6. CLI Usage

```bash
# Random bridge, hybrid mode (NN controls mulligan/attack/block/targeting)
./run.sh --nn-hybrid --nn-random -d burn.dck -d faeries.dck -n 100 -q

# Random bridge, full mode (NN controls ALL decisions)
./run.sh --nn-full --nn-random -d burn.dck -d faeries.dck -n 100 -q

# ONNX model, hybrid mode with training data export
./run.sh --nn-hybrid --nn-model model.onnx --nn-export /tmp/training_data \
    -d burn.dck -d faeries.dck -n 100 -q -j 8

# Debug mode (verbose NN decision logging to stderr)
java -Dforge.nn.debug=1 -jar forge.jar --nn-full --nn-random \
    -d burn.dck -d faeries.dck -n 10 -q
```

### Flags

| Flag | Description |
|------|-------------|
| `--nn-hybrid` | NN controls 6 critical decisions, heuristic handles rest |
| `--nn-full` | NN controls all ~80+ decisions |
| `--nn-model FILE` | Path to ONNX model file |
| `--nn-random` | Use random action selection (for testing/data generation) |
| `--nn-export DIR` | Directory for training data JSONL output |

---

## 7. Integration Notes

### Thread Safety
- `OnnxBridge` uses a single `OrtSession` shared across game threads (thread-safe per ONNX Runtime docs)
- `RandomBridge` uses `ThreadLocalRandom` (thread-safe)
- `TrainingDataWriter` is per-game-thread with synchronized write methods

### Performance
- Random bridge: ~20-30% overhead vs heuristic AI
- ONNX bridge: depends on model size, expect 1-10ms per inference
- Training data export: buffered I/O, ~10% additional overhead

### Memory
- ONNX Runtime adds ~60MB to dependencies
- Each game thread: ~1KB for state/option arrays

### Model Hot-Reload
Supported via `OnnxBridge.reloadModel(path)` (synchronized, swaps session atomically).

### Hybrid vs Full Mode

**Hybrid mode** overrides 6 methods:
1. `mulliganKeepHand` — keep/mulligan decision
2. `tuckCardsViaMulligan` — London mulligan card selection
3. `declareAttackers` — per-creature attack decisions
4. `declareBlockers` — blocker assignments
5. `chooseSingleEntityForEffect` — targeting
6. `chooseSpellAbilityToPlay` — (currently delegates to heuristic, TODO for future)

All other decisions use the heuristic AI.

**Full mode** overrides ~80+ decision methods, routing everything through the NN bridge.
Only informational/reveal methods are left to the heuristic.
