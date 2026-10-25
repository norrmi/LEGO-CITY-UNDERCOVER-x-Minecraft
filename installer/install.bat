@echo off
rem LEGO City Undercover x Minecraft - Windows installer.
rem Usage: install.bat "<LEGO City Undercover folder>" "<Minecraft instance folder>"
rem Without arguments it asks for both folders.
setlocal
cd /d "%~dp0"

where python >nul 2>nul
if errorlevel 1 (
  echo Python 3.10 or newer is needed: https://www.python.org/downloads/  ^(tick "Add python.exe to PATH"^)
  pause
  exit /b 1
)

set "GAME=%~1"
set "INSTANCE=%~2"
if "%GAME%"=="" set /p "GAME=LEGO City Undercover folder (e.g. C:\Program Files (x86)\Steam\steamapps\common\LEGO City Undercover): "
if "%INSTANCE%"=="" set /p "INSTANCE=Minecraft 1.18.2 Fabric instance folder (the one with mods and saves): "

echo Installing the converter's Python packages...
python -m pip install --disable-pip-version-check -q -r requirements.txt
if errorlevel 1 (
  echo pip failed - see above.
  pause
  exit /b 1
)

python install.py --game "%GAME%" --instance "%INSTANCE%"
pause
