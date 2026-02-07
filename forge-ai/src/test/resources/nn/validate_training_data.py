#!/usr/bin/env python3
"""Validate training data JSONL structure."""
import json, sys, glob


def validate_file(path):
    try:
        with open(path) as f:
            lines = f.readlines()

        if len(lines) < 2:
            return f"ERROR: {path} has < 2 lines (need at least 1 decision + outcome)"

        # Check all but last line are decisions
        for i, line in enumerate(lines[:-1]):
            obj = json.loads(line)
            if obj["type"] != "decision":
                return f"ERROR: line {i} is not decision"
            if len(obj["state"]) != 664:
                return f"ERROR: state size {len(obj['state'])} != 664"
            if obj["chosenIndex"] >= obj["numOptions"]:
                return f"ERROR: chosenIndex {obj['chosenIndex']} >= numOptions {obj['numOptions']}"
            if obj["numOptions"] < 1:
                return f"ERROR: numOptions must be >= 1"

        # Last line must be outcome
        outcome = json.loads(lines[-1])
        if outcome["type"] != "outcome":
            return f"ERROR: last line is not outcome"
        if outcome["result"] not in [0.0, 1.0]:
            return f"ERROR: result must be 0.0 or 1.0, got {outcome['result']}"

        return f"OK ({len(lines)-1} decisions)"
    except Exception as e:
        return f"ERROR: {e}"


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: validate_training_data.py <dir>")
        sys.exit(1)

    files = glob.glob(sys.argv[1] + "/*.jsonl")
    if not files:
        print(f"No .jsonl files found in {sys.argv[1]}")
        sys.exit(1)

    errors = 0
    for f in files:
        result = validate_file(f)
        print(f"{f}: {result}")
        if "ERROR" in result:
            errors += 1

    print(f"\n{len(files)} files checked, {errors} errors")
    sys.exit(0 if errors == 0 else 1)
