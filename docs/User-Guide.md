# User Guide

## System Requirements

**Java 17** or later is required. Download from [https://jdk.java.net/](https://jdk.java.net/)

Most setup problems are due to Java not being configured properly. Verify with:
```bash
java --version
```

The default Java heap space may not be sufficient. Launch scripts pass `-Xmx1024m` for extra memory.

If you plan to download all card images, ensure several gigabytes of free drive space.

## Install and Run

> **Warning:** Do NOT overwrite an existing installation. Always unpack/install to a new folder.

### Windows
1. Unpack the `.tar.bz2` file with 7-zip, WinRAR, etc.
2. Unpack the resulting `.tar` file into its own folder
3. Run `forge.exe`

### Linux/Mac
1. Unpack the `.tar.bz2` file
2. Run the `.sh` script (Linux) or `.command` file (Mac)
   - May need to make the script executable first
   - macOS needs both JRE and JDK installed

## User Data Locations

| OS | User Directory | Cache Directory |
|----|----------------|-----------------|
| Windows | `%APPDATA%/Forge/` | `%LOCALAPPDATA%/Forge/Cache/` |
| macOS | `$HOME/Library/Application Support/Forge/` | `$HOME/Library/Caches/Forge/` |
| Linux | `$HOME/.forge/` | `$HOME/.cache/forge/` |

Card pictures are stored in `<cacheDir>/pics/cards/` by default. See `forge.profile.properties.example` to customize paths.

## Gameplay Tips

### Targeting
- Click on a player's avatar to target them
- Arrows show targeting relationships (red = opponent, blue = ally)

### Card Zooming
- Mouse-wheel forward over any card to zoom
- Mouse-wheel back, click, or ESC to close
- For flip/double-sided cards, use CTRL or wheel forward to see alternate side

### Auto-Pay Mana
Press Enter/Spacebar or click Auto to automatically pay mana costs using the AI's logic.

### Auto-Yield
Right-click optional abilities on the stack to auto-accept or auto-decline them.

### Shift Key Shortcuts
- Shift + hover on flip/morph cards to see alternate state
- Shift + click mana pool icons to use maximum of that color
- Shift + click a stacked land to tap all in stack
- Shift + click a stacked creature to attack with all in stack

### Full Control
Right-click your avatar to disable auto-helpers and get full control over game actions.

### Macros
- Shift+R to define a repeatable click sequence
- @ (Shift+2) to execute the macro

## Troubleshooting

### Layout Problems
Reset layouts via Game Settings -> Preferences -> Troubleshooting.

Match and deck editor layouts are saved in `userDir/preferences/` as `match.xml` and `editor.xml`.

## Reporting Bugs

1. Note what was in play for both players and what you were doing
2. Save any crash reports
3. Check the Oracle rulings - Forge may be correct
4. Search the [issue tracker](https://github.com/Card-Forge/forge/issues) for duplicates
5. Submit a new issue with reproduction steps

## Accessibility

Check [Skins](Skins.md) for alternative color themes if the default is difficult to read.
