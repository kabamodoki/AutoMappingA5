@echo off
setlocal
set "HERE=%~dp0"
set "A5ER="
set "CSV="
:loop
if "%~1"=="" goto run
if /i "%~x1"==".a5er" set "A5ER=%~1"
if /i "%~x1"==".csv" set "CSV=%~1"
shift
goto loop
:run
if not defined A5ER goto usage
if not defined CSV goto usage
python -X utf8 "%HERE%a5er_csv.py" import-relations "%A5ER%" "%CSV%"
pause
exit /b
:usage
echo .a5er ファイルと .csv ファイルを2つ一緒にこの bat へドラッグ＆ドロップしてください。
pause
exit /b 1
