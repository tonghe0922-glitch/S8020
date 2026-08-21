@echo off
setlocal EnableExtensions
chcp 65001 >nul
set "MAVEN_VERSION=3.9.11"
set "DIST_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip"
if defined MAVEN_USER_HOME (
  set "WRAPPER_HOME=%MAVEN_USER_HOME%\wrapper\dists\apache-maven-%MAVEN_VERSION%"
) else (
  set "WRAPPER_HOME=%USERPROFILE%\.m2\wrapper\dists\apache-maven-%MAVEN_VERSION%"
)
set "MAVEN_CMD=%WRAPPER_HOME%\apache-maven-%MAVEN_VERSION%\bin\mvn.cmd"
if not exist "%MAVEN_CMD%" (
  if not exist "%WRAPPER_HOME%" mkdir "%WRAPPER_HOME%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $zip=Join-Path -Path '%WRAPPER_HOME%' -ChildPath 'maven.zip'; Invoke-WebRequest -Uri '%DIST_URL%' -OutFile $zip; Expand-Archive -LiteralPath $zip -DestinationPath '%WRAPPER_HOME%' -Force; Remove-Item -LiteralPath $zip -Force"
  if errorlevel 1 exit /b 1
)
call "%MAVEN_CMD%" %*
exit /b %ERRORLEVEL%
