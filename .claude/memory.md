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

## Build & Run

- **Build and run sim mode**: `./run.sh`
- **Run with arguments**: `./run.sh -d deck1.dck deck2.dck -n 100 -j 8`
- **Run GUI**: `./run.sh --gui`
- **Force clean build**: `./run.sh --clean`
- **Build without running**: `./run.sh --build-only`
- **Run without building**: `./run.sh --run-only`
- Uses Maven Wrapper (`./mvnw`) — no need for `~/maven-3.9.6/bin/mvn`
- Smart change detection: skips build if no `.java` or `pom.xml` files changed
- Incremental builds by default (no `clean`), parallel module builds (`-T 1C`)
- Fat JAR: `forge-gui-desktop/target/forge-gui-desktop-*-jar-with-dependencies.jar`

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

## [2026-02-06] Add Smart Build Script and Maven Wrapper
- **Type**: Feature
- **Description**: Created `run.sh` at project root — a smart build-and-run script that replaces the manual `~/maven-3.9.6/bin/mvn clean install -DskipTests && java ... sim` workflow. Features: (1) change detection via `.last_build_timestamp` marker — skips build entirely when no `.java` or `pom.xml` files changed, (2) incremental builds by default (no `clean`), (3) parallel module builds (`-T 1C`), (4) flags: `--clean`, `--build-only`, `--run-only`, `--gui`. Installed Maven Wrapper (`./mvnw`) embedding Maven 3.9.6 in the project. Updated `.gitignore`, `.claudeignore`, `README.md`, and `CONTRIBUTING.md`.
- **Why**: Full `clean install` took ~90s and was triggered accidentally via up-arrow+Enter even when nothing changed. Non-standard Maven location (`~/maven-3.9.6/`) confused AI agents. Now `./run.sh` skips builds instantly when unchanged, incremental builds take ~20s, and `./mvnw` works on any machine with Java 17.

## [2026-02-06] Phase 1 Performance Quick Wins
- **Type**: Feature
- **Description**: Enabled existing but disabled infrastructure in `forge-ai` for simulation speed gains: (1) Snapshot restore enabled by default in sim mode, (2) Transposition table enabled for Default AI profile, (3) Fixed MoveOrderer thread safety with ThreadLocal, (4) Reuse GameStateEvaluator instances instead of recreating per call, (5) Pre-sized collections in GameCopier/MoveOrderer/SpellAbilityPicker.
- **Why**: 2-5x speedup for AI-vs-AI simulations with no quality regression. These were all existing features that were disabled or had bugs preventing safe use.

## [2026-02-06] Phase 2.1 Improved Mulligan Logic (Deck-Curve-Aware)
- **Type**: Feature
- **Description**: Rewrote mulligan hand scoring and card selection to be deck-aware. Changes across 3 files:
  1. `AiDeckStatistics.java`: Added `deckSize` field and `idealLandsInHand(handSize)` method — computes ideal land count proportional to deck's land ratio (burn deck with 20/60 lands → 2 lands in 7-card hand; control with 26/60 → 3).
  2. `ComputerUtil.scoreHand()`: Replaced hardcoded `handSize/2` ideal land count with deck-proportional calculation. Added color matching (rejects hands where >50% of spells have unmet color requirements). Added turn 1-3 castability scoring.
  3. `PlayerControllerAi.tuckCardsViaMulligan()`: Uses deck-proportional land count. Prefers bottoming color-redundant lands and color-mismatched spells before falling back to generic scry/worst-card logic.
- **Why**: Old mulligan used `handSize/2` as ideal land count regardless of deck archetype. No color analysis meant AI kept 3 Mountains + all Blue spells. No castability analysis beyond simple CMC check. These are the biggest source of game-to-game randomness in simulation results.

## [2026-02-06] Fix GUI Deck Loading + Consolidate Deck Files
- **Type**: Bugfix
- **Description**: Fixed GUI showing 0 constructed decks. Root cause: `GuiDesktop.getAssetsDir()` was changed from `"../forge-gui/"` to `"forge-gui/"` in commit `db522468cf` (strip non-desktop modules). This moved where `forge.profile.properties` is expected (`PROFILE_FILE = ASSETS_DIR + "forge.profile.properties"`). The profile at the project root was no longer found, so deck paths fell back to `~/.forge/decks/constructed/` (empty). Fix: copied `forge.profile.properties` to `forge-gui/forge.profile.properties`. Also consolidated all deck files into single canonical location `user/decks/constructed/`, removed duplicates from `~/.forge/decks/` and `user/decks/` (parent). Fixed 4-copy rule violations: Burn.dck (6 Searing Blaze → replaced SB copies with Flame Rift), Spy Combo.dck (7 Faerie Macabre → replaced SB copies with Weather the Storm).
- **Why**: The `getAssetsDir()` change was necessary for running from project root via `run.sh`, but had the side effect of breaking profile file discovery. Deck duplication across 3 locations caused confusion. Note: `DeckStorage.adjustFileLocation()` moves files to grandparent dir if filename doesn't match deck `Name=` field — filenames must match deck names exactly.

## [2026-02-06] Phase 2.2 Alpha-Beta Pruning + Deeper Search
- **Type**: Feature
- **Description**: Implemented two pruning strategies for the simulation search tree (which is all-MAX, no alternating min/max): (1) **Futility pruning** in `GameSimulator.java` — skips recursion when a move's base score is `FUTILITY_MARGIN` (default 300) below the current best at that depth. (2) **Soft beta cutoff** in `SpellAbilityPicker.java` — at depth >= 2, stops evaluating more candidates once a score beats the parent level's best (proves branch is competitive). Alpha tracking per depth level via `alphaStack` in `SimulationController.java`, managed through existing push/pop. New `AiProps`: `ALPHA_BETA_PRUNING` (bool), `FUTILITY_MARGIN` (int). Created **Simulation.ai** profile: depth=8, time=15s, all pruning enabled. Updated Enhanced.ai with pruning too.
- **Why**: Needed to enable deeper search (depth 3→8) without exponential blowup. Standard alpha-beta doesn't apply to all-MAX trees, so used futility pruning + soft cutoffs instead. Result: depth-8 with pruning runs **faster** than unpruned depth-3 (63s vs 72s for 10 games).

## [2026-02-07] Phase 2.3 Context-Aware Creature Evaluation
- **Type**: Feature
- **Description**: Creature evaluation in `GameStateEvaluator.java` now considers board state instead of flat P/T + keyword scores. Added `evaluateCreatureInContext()` to inner `SimulationCreatureEvaluator` class with three context multipliers: (1) **Evasion relevance** — counts how many opponent creatures can actually block this one via `canPotentiallyBlock()` (checks flying/horsemanship/shadow/fear/intimidate); bonus when few/no blockers exist. (2) **Board density** — creatures worth more on sparse boards (+25 with ≤2 total creatures). (3) **Threat sizing** — bonus when creature kills or survives all opposing creatures; deathtouch bonus vs big threats. Creature eval no longer cached (depends on board state); non-creature cards still cached.
- **Why**: A 2/2 flyer previously scored identically whether opponent had 0 or 20 flying blockers. Context-aware evaluation fixes obviously wrong board assessments and helps the simulation AI make better play decisions.

## [2026-02-07] Phase 2.4 Combat Block Prediction for Attack Decisions
- **Type**: Feature
- **Description**: Added `refineAttacksWithBlockPrediction()` to `AiAttackController.java`. After all attack decisions are made (aggression 1-4, non-simAI), creates a test `Combat` object with declared attackers and uses `AiBlockController(opponent, true)` to predict opponent's block assignments. Removes attackers headed for unfavorable trades: dies without killing a comparably-valued blocker, no trample excess, no combat triggers/lifelink. Skips creatures with first strike, double strike, indestructible, or undying/persist (too complex to predict simply).
- **Why**: Attack decisions previously evaluated each creature independently via `SpellAbilityFactors`, not accounting for how the opponent would distribute blockers across all attackers. This caused the AI to send creatures into bad multi-creature block scenarios.

## [2026-02-07] Phase 2.5 Enhanced Evaluation (Clock/Tempo/Card Quality) — IN PROGRESS
- **Type**: Feature
- **Description**: Added three strategic dimensions to `getScoreForGameStateImpl()` in `GameStateEvaluator.java`: (1) **Clock calculation** — tracks evasive damage (flying/horsemanship/unblockable) per side during battlefield iteration, calculates turns-to-kill, awards ±15 pts per turn of clock advantage (capped ±80). (2) **Tempo score** — compares untapped mana producers between players, ±3 pts per mana source advantage. (3) **Card quality weighting** — hand cards scored by castability: castable spells worth 6 pts, non-castable 3 pts, lands 3 pts (replacing flat 5-per-card). Code written but **not yet compiled/tested**.
- **Why**: Old evaluation only counted life totals and board material. No awareness of racing (who kills first via evasion), mana advantage, or whether cards in hand can actually be cast. These are critical for aggro vs control matchup accuracy.
