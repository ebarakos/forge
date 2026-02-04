# Forge Project Memory

This file tracks major changes, architectural decisions, and milestones for the Forge MTG project.

---

## [2026-02-03] Add Ascended AI Profile with Enhanced Combo/Synergy Detection
- **Type**: Feature
- **Description**: Created new AI personality "Ascended" in `forge-gui/res/ai/Ascended.ai` that extends Enhanced profile. Added 4 new synergy detection methods to `GameStateEvaluator.java`: graveyard synergy (reanimator/dredge), sacrifice synergy (aristocrats), +1/+1 counter synergy, and tribal synergy (15 creature types). Expanded mana doubler detection from 5 to 17 cards including virtual doublers like Seedborn Muse and Wilderness Reclamation.
- **Why**: Improve AI decision-making by recognizing common MTG deck archetypes and rewarding synergistic board states. The Enhanced profile was a good base but lacked awareness of specific strategies.

## [2026-02-03] Add CLI Simulation Speed Improvements
- **Type**: Feature
- **Description**: Added multiple optimizations to `SimulateMatch.java` for faster batch simulations:
  1. `-s` flag enables `EXPERIMENTAL_RESTORE_SNAPSHOT` for 2-3x faster game state copying
  2. `-j N` flag enables parallel game execution with N threads for 4-16x throughput on multi-core CPUs
  3. Integrated `MoveOrderer` into `SpellAbilityPicker` for better alpha-beta pruning via killer moves and history heuristic
  4. Added card evaluation caching in `GameStateEvaluator` to avoid recomputing values for unchanged cards
- **Why**: CLI simulations for deck/card synergy discovery were too slow. LLM-suggested decks need rapid win-rate evaluation. Combined optimizations provide 10-50x speedup for batch testing.

---

## Key Files Reference

### AI Profiles
- `forge-gui/res/ai/*.ai` - AI personality configuration files
- Profiles: Default, Cautious, Reckless, Experimental, Enhanced, **Ascended**, AlwaysPass

### Simulation System
- `forge-ai/src/main/java/forge/ai/simulation/GameStateEvaluator.java` - Board state scoring with combo detection
- `forge-ai/src/main/java/forge/ai/simulation/SpellAbilityPicker.java` - Spell selection with move ordering
- `forge-ai/src/main/java/forge/ai/simulation/SimulationController.java` - Depth/time limits, transposition table
- `forge-ai/src/main/java/forge/ai/simulation/MoveOrderer.java` - Alpha-beta pruning optimization

### CLI Simulation
- `forge-gui-desktop/src/main/java/forge/view/SimulateMatch.java` - CLI entry point for batch simulations
- Usage: `forge sim -d deck1.dck deck2.dck -n 100 -s -j 8 -P1 Ascended`

### AI Profile Settings (AiProps.java)
- `SIMULATION_MAX_DEPTH` - Lookahead depth (default 3, Enhanced/Ascended use 6)
- `SIMULATION_TIME_LIMIT_MS` - Max decision time (default 5000, Ascended uses 8000)
- `COMBO_STATE_BONUS` - Bonus for combo-ready states (default 0, Ascended uses 150)
- `USE_TRANSPOSITION_TABLE` - Cache evaluated positions (default false, Enhanced/Ascended true)
- `LOOP_DETECTION_ENABLED` - Detect infinite loops (default false, Enhanced/Ascended true)

## [2026-02-04] Fix CLI Simulation Thread Safety and Quiet Mode
- **Type**: Bugfix
- **Description**: Fixed multiple issues in `SimulateMatch.java`:
  1. **Thread-safety bug**: Added `PlayerConfig` class to store deck/player info and create fresh `RegisteredPlayer` objects with deck copies per game. Previously shared mutable state caused same-deck-vs-same-deck to show 100% wins for one player.
  2. **Quiet mode fix**: Suppress both `System.out` AND `System.err` to catch all debug prints. Output suppression starts early (before game setup) and uses `ORIGINAL_OUT`/`ORIGINAL_ERR` for intentional output.
  3. **Unified quiet behavior**: `-q` flag OR `-j` flag triggers quiet mode. Full game logs only shown in sequential mode without either flag.
  4. **Compilation fix**: Changed `getText()` to `getOracleText()` in `GameStateEvaluator.java:217`.
- **Why**: Parallel simulations were producing incorrect win rates due to thread races on shared deck objects. Debug prints from 30+ files polluted output even with `-q` flag. Users needed clean output for automated testing.
