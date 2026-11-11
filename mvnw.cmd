@ECHO OFF
@REM ----------------------------------------------------------------------------
@REM Licensed to the Apache Software Foundation (ASF) under one
@REM or more contributor license agreements.  See the NOTICE file
@REM distributed with this work for additional information
@REM regarding copyright ownership.  The ASF licenses this file
@REM to you under the Apache License, Version 2.0 (the
@REM "License"); you may not use this file except in compliance
@REM with the License.  You may obtain a copy of the License at
@REM
@REM    http://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing,
@REM software distributed under the License is distributed on an
@REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@REM KIND, either express or implied.  See the License for the
@REM specific language governing permissions and limitations
@REM under the License.
@REM ----------------------------------------------------------------------------

SETLOCAL
SET "MAVEN_PROJECTBASEDIR=%~dp0"
IF "%MAVEN_PROJECTBASEDIR:~-1%"=="\" SET "MAVEN_PROJECTBASEDIR=%MAVEN_PROJECTBASEDIR:~0,-1%"

 powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference = 'Stop';" ^
  "$props = Get-Content (Join-Path '%MAVEN_PROJECTBASEDIR%' '.mvn/wrapper/maven-wrapper.properties');" ^
  "$url = ($props | Where-Object { $_ -match '^distributionUrl=' }) -replace '^distributionUrl=', '' -replace '\\', '';" ^
  "$expectedSha = ($props | Where-Object { $_ -match '^distributionSha256Sum=' }) -replace '^distributionSha256Sum=', '';" ^
  "$fileName = [System.IO.Path]::GetFileName($url);" ^
  "$distName = $fileName -replace '-bin\.zip$', '';" ^
  "$m2 = if ($env:MAVEN_USER_HOME) { $env:MAVEN_USER_HOME } else { Join-Path $HOME '.m2' };" ^
  "$targetDir = Join-Path $m2 (Join-Path 'wrapper\dists' $distName);" ^
  "$mvnCmd = Join-Path $targetDir (Join-Path $distName 'bin\mvn.cmd');" ^
  "if (-not (Test-Path $mvnCmd)) {" ^
  "  New-Item -ItemType Directory -Force -Path $targetDir | Out-Null;" ^
  "  $zipPath = Join-Path $targetDir $fileName;" ^
  "  Invoke-WebRequest -Uri $url -OutFile $zipPath;" ^
  "  if ($expectedSha) {" ^
  "    $actualSha = (Get-FileHash -Algorithm SHA256 $zipPath).Hash.ToLowerInvariant();" ^
  "    if ($actualSha -ne $expectedSha.Trim().ToLowerInvariant()) { Remove-Item $zipPath -Force; throw 'SHA-256 mismatch for ' + $url }" ^
  "  }" ^
  "  Expand-Archive -Path $zipPath -DestinationPath $targetDir -Force;" ^
  "  Remove-Item $zipPath -Force;" ^
  "}" ^
  "& $mvnCmd %*"
EXIT /B %ERRORLEVEL%
