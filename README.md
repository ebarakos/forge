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
- Maven

## Building

```bash
mvn -U -B clean install -P windows-linux
```

## Running

### Desktop GUI
```bash
java -jar forge-gui-desktop/target/forge-gui-desktop-*-SNAPSHOT.jar
```

### CLI Simulation
Run AI vs AI matches from the command line:
```bash
java -cp forge-gui-desktop/target/forge-gui-desktop-*-SNAPSHOT.jar forge.view.SimulateMatch \
  -d deck1.dck deck2.dck -n 100
```

**Simulation flags:**
- `-d <deck1> <deck2>` - Deck files to use
- `-n <count>` - Number of games to simulate
- `-s` - Enable snapshot restore for faster games
- `-j <threads>` - Parallel execution threads
- `-q` - Quiet mode (suppress game logs)
- `-P1 <profile>` - AI profile for player 1 (Default, Enhanced, Ascended, etc.)
- `-P2 <profile>` - AI profile for player 2
- `-B <dir>` - Base directory for relative deck paths

## AI Profiles

Located in `forge-gui/res/ai/`:
- **Default** - Standard AI behavior
- **Enhanced** - Deeper simulation, transposition tables
- **Ascended** - Maximum depth with combo/synergy detection

## License

[GPL-3.0](LICENSE)

---

*Based on [Card-Forge/forge](https://github.com/Card-Forge/forge)*
