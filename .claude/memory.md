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

## [2026-02-05] Fix Sim Mode Deck Path Resolution
- **Type**: Bugfix
- **Description**: Fixed `deckFromCommandLineParameter()` in `SimulateMatch.java` to properly handle absolute paths. Previously, the method always prepended `baseDir` to deck names, breaking absolute paths (e.g., `-d /full/path/deck.dck` became `/decks/constructed//full/path/deck.dck`). Changes:
  1. Check if path is absolute using `File.isAbsolute()` - if so, use directly without modification
  2. Added new `-B` flag to specify custom base directory for relative deck paths
  3. Updated `simulateTournament()` to accept and pass through custom base dir
  4. Improved error message to show full resolved path when deck not found
- **Why**: Users couldn't load decks from arbitrary locations using absolute paths. The bug was introduced in commit 73787fb126 (2016) when subfolder support was added. New `-B` flag allows per-session deck folder configuration without modifying profile properties.

## [2026-02-05] Clean Up Documentation for Minimal Branch
- **Type**: Refactor
- **Description**: Removed and updated markdown files to reflect the stripped-down minimal branch (desktop-only, no mobile/Android/iOS):
  - **Deleted**: `docs/Adventure/` (20 files), Android/iOS dev docs, Network play docs, Steam Deck guide, Docker setup, and other irrelevant docs
  - **Rewrote**: `README.md` (focused on CLI simulation), `CONTRIBUTING.md` (5 modules only), `docs/Home.md`, `docs/_sidebar.md`
  - **Updated**: `docs/User-Guide.md`, `docs/Frequently-Asked-Questions.md` (removed Quest/Adventure references), `docs/AI.md` (added new CLI flags and AI profiles), `.github/ISSUE_TEMPLATE/bug_report.md` (removed smartphone section)
- **Why**: Documentation referenced modules and features (Android, iOS, Adventure mode, Network play) that don't exist in this minimal branch. Cleaned docs now accurately reflect the included modules: forge-core, forge-game, forge-gui, forge-gui-desktop, forge-ai.

## [2026-02-06] Migrate CLI to Picocli + Fix Critical Bugs
- **Type**: Feature / Bugfix
- **Description**: Migrated CLI from manual arg parsing to picocli framework. Added new `forge.cli` package with `ForgeCli` (root command), `SimCommand`, `ParseCommand`, `ServerCommand`, `ExitCode`, and `SimulationResult` JSON model. Added `--json` flag for structured output and `--version`/`--help` via picocli. Fixed two critical bugs found during review:
  1. **JSON output corruption**: `simulateSingleMatchWithResult` printed plain-text game results to stdout even in `--json` mode, corrupting JSON for downstream parsers (e.g. `jq`). Fixed by routing per-game status to stderr in JSON mode and suppressing `System.out` during game execution when `--json` is active.
  2. **Fragile winner detection**: Winner index was determined via `contains("(1)")` string matching, which broke with deck names containing "(1)" and only worked for 2-player games. Replaced with `determineWinnerIndex()` that compares against `Match.getPlayers()` registered player references.
- **Why**: Manual arg parsing was error-prone and didn't support `--help`/`--version`. The `--json` flag enables scripted analysis of simulation results. Winner detection needed to be robust for multi-player and arbitrary deck names.
