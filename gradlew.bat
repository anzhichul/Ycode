@rem Gradle startup script for Windows
@echo off
setlocal
set DIRNAME=%~dp0
if defined JAVA_HOME goto findJava
set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute
echo JAVA_HOME is not set and java.exe was not found.
exit /b 1
:findJava
set JAVA_EXE=%JAVA_HOME%\bin\java.exe
if exist "%JAVA_EXE%" goto execute
echo JAVA_HOME points to an invalid Java installation: %JAVA_HOME%
exit /b 1
:execute
"%JAVA_EXE%" %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=gradlew" -classpath "%DIRNAME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
endlocal
