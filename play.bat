@echo off
chcp 65001 >nul
rem Консольный прототип боя. Нужна Java 21+ (https://adoptium.net).
call gradlew.bat -q :battle-core:installDist || exit /b 1
set JAVA_OPTS=-Dstdout.encoding=UTF-8 -Dstdin.encoding=UTF-8
call battle-core\build\install\battle-core\bin\battle-core.bat %*
