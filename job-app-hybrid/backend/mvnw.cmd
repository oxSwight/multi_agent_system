@REM ----------------------------------------------------------------------------
@REM Maven Start Up Batch script
@REM ----------------------------------------------------------------------------
@echo off
set JAVA_HOME=C:\Program Files\Amazon Corretto\jdk21.0.3_9
set MAVEN_PROJECTBASEDIR=%~dp0
if not "%MAVEN_PROJECTBASEDIR:~-1%"=="\" set MAVEN_PROJECTBASEDIR=%MAVEN_PROJECTBASEDIR%\
for /f "usebackq tokens=1,2 delims==" %%a in ("%MAVEN_PROJECTBASEDIR%.mvn\wrapper\maven-wrapper.properties") do (
    if "%%a"=="distributionUrl" set DISTRIBUTION_URL=%%b
)
set MVN_CMD=%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.6\bin\mvn.cmd
if exist "%MVN_CMD%" goto runMaven
"%JAVA_HOME%\bin\java" -jar "%MAVEN_PROJECTBASEDIR%.mvn\wrapper\maven-wrapper.jar" "--distributionUrl=%DISTRIBUTION_URL%" "--baseDirectory=%MAVEN_PROJECTBASEDIR%"
:runMaven
"%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.6\bin\mvn.cmd" %*
