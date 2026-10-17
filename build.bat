@echo off
cd /d "%~dp0"

rem ============================================================
rem  aiplayer build script  (double-click to run)
rem  All text is ASCII on purpose: non-ASCII text in a .bat file
rem  gets garbled by cmd.exe and turns into bogus commands.
rem ============================================================

rem ---- toolchain: change these two paths if needed ----
set "JAVA_HOME=I:\zulu25.30.17-ca-jdk25.0.1-win_x64"
set "GRADLE_USER_HOME=F:\gradle-home"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo ============================================================
echo  Building aiplayer
echo  JAVA_HOME        = %JAVA_HOME%
echo  GRADLE_USER_HOME = %GRADLE_USER_HOME%
echo ============================================================
echo.

if not exist "%JAVA_HOME%\bin\javac.exe" echo [ERROR] JDK not found: %JAVA_HOME%
if not exist "%JAVA_HOME%\bin\javac.exe" echo         Fix JAVA_HOME at the top of this file.
if not exist "%JAVA_HOME%\bin\javac.exe" pause
if not exist "%JAVA_HOME%\bin\javac.exe" exit /b 1

call gradlew.bat build --console=plain
echo.
echo ---- gradlew exit code = %errorlevel% ----

if errorlevel 1 echo [FAILED] Build failed. Read the error output above.
if errorlevel 1 pause
if errorlevel 1 exit /b 1

echo ============================================================
echo  BUILD OK, output:
echo ============================================================
dir /b "build\libs\*.jar"
echo.
echo  Folder: %~dp0build\libs
echo  Copy that jar into .minecraft\mods
echo  (needs Fabric Loader + Fabric API)
echo.
pause