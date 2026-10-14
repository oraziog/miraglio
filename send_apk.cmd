@echo off
rem Lancia send_apk.ps1 senza blocchi di ExecutionPolicy (doppio-click).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0send_apk.ps1" %*