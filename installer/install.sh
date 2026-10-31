#!/usr/bin/env bash
# LEGO City Undercover x Minecraft - installer for Linux (Arch and others).
#
#   ./installer/install.sh                       the installer window (finds the game and your instances)
#   ./installer/install.sh --game "<LEGO City Undercover folder>" --instance "<Minecraft instance folder>" --extras
#                                                no window: any instance folder you name (the one with mods, saves)
#
# Needs Python 3.10+ with Tk for the window. Arch: sudo pacman -S python tk
# The converter's packages go into a virtual environment next to this script (Arch's Python does not allow a
# system-wide pip install), so nothing else on the system changes.
set -euo pipefail
cd "$(dirname "$0")"

PY=${PYTHON:-python3}
if ! command -v "$PY" >/dev/null 2>&1; then
    echo "Python 3 is needed. Arch: sudo pacman -S python tk" >&2
    exit 1
fi

VENV=.venv
if [ ! -x "$VENV/bin/python" ]; then
    echo "Setting up the converter's Python packages in installer/$VENV ..."
    "$PY" -m venv "$VENV"
fi
"$VENV/bin/python" -m pip install --disable-pip-version-check -q -r requirements.txt

if [ $# -gt 0 ]; then
    exec "$VENV/bin/python" install.py "$@"
fi
if ! "$VENV/bin/python" -c "import tkinter" >/dev/null 2>&1; then
    echo "No Tk for the installer window. Arch: sudo pacman -S tk" >&2
    echo "Or without a window: $0 --game \"<game folder>\" --instance \"<instance folder>\" --extras" >&2
    exit 1
fi
exec "$VENV/bin/python" gui.py
