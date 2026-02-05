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

## Synergy Detection (Ascended Profile)

The Ascended profile includes detection for:
- **Graveyard synergy** - Reanimator, dredge strategies
- **Sacrifice synergy** - Aristocrats patterns
- **+1/+1 counter synergy** - Counter-based strategies
- **Tribal synergy** - 15 creature types recognized
- **Mana doublers** - 17 cards including virtual doublers (Seedborn Muse, Wilderness Reclamation)
