@echo off
setlocal
set "HERE=%~dp0"
if "%~1"=="" (
  echo .a5er ファイルをこの bat へドラッグ＆ドロップしてください（複数可）。
  pause
  exit /b 1
)
:loop
if "%~1"=="" goto end
python -X utf8 "%HERE%a5er_layout.py" "%~1"
shift
goto loop
:end
pause
