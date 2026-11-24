@echo off
setlocal EnableDelayedExpansion
set "HERE=%~dp0"
set "A5ER="
set "CSVS="
:loop
if "%~1"=="" goto run
if /i "%~x1"==".a5er" set "A5ER=%~1"
if /i "%~x1"==".csv" set CSVS=!CSVS! "%~1"
shift
goto loop
:run
if not defined A5ER goto usage
python -X utf8 "%HERE%a5er_csv.py" all "%A5ER%" !CSVS!
pause
exit /b
:usage
echo .a5er ファイル1つと、論理名・リレーションの CSV（1～2個）を一緒にこの bat へドラッグ＆ドロップしてください。
echo 論理名の反映 → リレーションの追加 → 整列 をまとめて行います。
pause
exit /b 1
