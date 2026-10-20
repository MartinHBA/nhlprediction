param([string]$FunctionAppName='nhl-local')
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
New-Item -ItemType Directory -Force .local | Out-Null
& mvn -B -f backend/pom.xml "-DfunctionAppName=$FunctionAppName" "-Dmaven.repo.local=$((Get-Location).Path)/.local/m2" clean verify
if ($LASTEXITCODE -ne 0) { throw 'Java verification failed.' }
$package=Join-Path (Get-Location) "backend/target/azure-functions/$FunctionAppName"
if (!(Test-Path -LiteralPath (Join-Path $package 'host.json'))) { throw 'Functions package is missing host.json.' }
$localSettings=Join-Path $package 'local.settings.json'
if (Test-Path -LiteralPath $localSettings) { Remove-Item -LiteralPath $localSettings }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=Join-Path (Get-Location) '.local/functions.zip'
if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive }
[IO.Compression.ZipFile]::CreateFromDirectory($package,$archive)
Write-Output "Package: $archive"
