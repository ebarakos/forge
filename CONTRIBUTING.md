# Contributing to Forge

## Requirements

- Java JDK 17 or later
- Maven
- Git
- Java IDE (IntelliJ recommended)

## Quick Setup

1. Fork and clone the repository
2. Build with Maven:
   ```bash
   mvn -U -B clean install -P windows-linux
   ```

## Project Structure

This minimal branch contains:

- **forge-ai** - AI opponent logic and simulation
- **forge-core** - Core game engine and rules
- **forge-game** - Game session management
- **forge-gui** - UI components and card scripting resources (`res/` path)
- **forge-gui-desktop** - Java Swing desktop client

## Card Scripting

Card scripting resources are in `forge-gui/res/`. See the [Card Scripting API](docs/Card-scripting-API/Card-scripting-API.md) documentation.

## IntelliJ Setup

See [IntelliJ Setup Guide](docs/Development/IntelliJ-setup/IntelliJ-setup.md).
