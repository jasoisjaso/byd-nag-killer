@echo off
rem Nag Killer installer for Windows: double-click this file.
rem It runs install-windows.ps1 from the same folder.
title Nag Killer installer
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-windows.ps1"
if errorlevel 1 pause
