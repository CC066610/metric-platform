@echo off
rem Start the host metric collector.
rem Put a shortcut to this file in shell:startup to start collecting at boot.
setlocal
cd /d "%~dp0"

if "%PLATFORM_URL%"=="" set PLATFORM_URL=http://127.0.0.1:8080

where python >nul 2>&1
if errorlevel 1 (
  echo Python was not found on PATH.
  exit /b 1
)

python -c "import psutil" >nul 2>&1
if errorlevel 1 (
  echo psutil is missing, installing it now...
  python -m pip install psutil
  if errorlevel 1 (
    echo psutil installation failed.
    exit /b 1
  )
)

echo Starting collector against %PLATFORM_URL%
python agent.py --url "%PLATFORM_URL%" %*
endlocal
