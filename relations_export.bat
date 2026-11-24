@echo off
setlocal
set "HERE=%~dp0"
if "%~1"=="" (
  echo .a5er ファイルをこの bat へドラッグ＆ドロップすると、リレーションの CSV を書き出します。
  pause
  exit /b 1
)
:loop
if "%~1"=="" goto end
python -X utf8 "%HERE%a5er_csv.py" export-relations "%~1"
shift
goto loop
:end
pause
