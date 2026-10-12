@echo off
set DIR=%~dp0
echo Starting DigUp for Windows...
start "" javaw -jar "%DIR%target\digup-windows-1.0.0.jar"
