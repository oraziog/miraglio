@echo off
rem Lancia nuova_build_di_prova.sh da Git Bash, senza aprire un terminale a mano.
rem Accetta gli stessi argomenti: -n "testo della nota".
where bash >nul 2>nul
if errorlevel 1 (
  echo Serve Git Bash nel PATH ^(lo installa Git per Windows^).
  exit /b 1
)
bash "%~dp0nuova_build_di_prova.sh" %*
if errorlevel 1 (
  echo.
  echo La build si e' fermata con un errore.
  pause
)
