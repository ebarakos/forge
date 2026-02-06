# Forge: MTG Rules Engine (Minimal Branch)

A stripped-down version of [Forge](https://github.com/Card-Forge/forge) containing only the desktop GUI and AI simulation tools.

## What's Included

- **forge-core** - Core game engine and rules
- **forge-game** - Game session management
- **forge-gui** - UI components and card scripting resources
- **forge-gui-desktop** - Java Swing desktop client
- **forge-ai** - AI opponent logic and simulation

## Requirements

- Java 17 or later
- Maven is bundled via the Maven Wrapper (`./mvnw`) — no separate install needed

## Quick Start

```bash
./run.sh                    # smart build + run sim mode
./run.sh --gui              # smart build + run GUI
./run.sh --clean            # force clean rebuild + run sim
./run.sh --build-only       # build without running
./run.sh --run-only         # run existing JAR without building
```

The `run.sh` script automatically detects source changes and skips the build when nothing has changed. When a build is needed, it uses incremental compilation (no `clean` unless `--clean` is passed) with parallel module builds.

Any extra arguments are passed through to the application:
```bash
./run.sh -d deck1.dck -d deck2.dck -n 100 -j 8 -q
```

## Manual Building

```bash
./mvnw install -DskipTests -T 1C         # incremental build (fast)
./mvnw clean install -DskipTests -T 1C   # clean build (from scratch)
```

## CLI Simulation

Run AI vs AI matches from the command line:
```bash
./run.sh -d deck1.dck -d deck2.dck -n 100
```

**Simulation flags:**
- `-d <deck>` - Deck file or name (repeat for each player)
- `-n <count>` - Number of games to simulate
- `-s` - Enable snapshot restore for faster games
- `-j <threads>` - Parallel execution threads
- `-q` - Quiet mode (suppress game logs)
- `--json` - Output results as JSON
- `-P1 <profile>` - AI profile for player 1 (Default, Enhanced, Ascended, etc.)
- `-P2 <profile>` - AI profile for player 2
- `-B <dir>` - Base directory for relative deck paths

### Desktop GUI
```bash
./run.sh --gui
```

## AI Profiles

Located in `forge-gui/res/ai/`:
- **Default** - Standard AI behavior
- **Enhanced** - Deeper simulation, transposition tables
- **Ascended** - Maximum depth with combo/synergy detection

## License

[GPL-3.0](LICENSE)

---

*Based on [Card-Forge/forge](https://github.com/Card-Forge/forge)*
