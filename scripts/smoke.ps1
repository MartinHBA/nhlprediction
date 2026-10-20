param([string]$Day,[switch]$Repair)
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (!$Day) { $Day=[TimeZoneInfo]::ConvertTimeFromUtc([DateTime]::UtcNow,[TimeZoneInfo]::FindSystemTimeZoneById('Eastern Standard Time')).ToString('yyyy-MM-dd') }
$deployment=Get-Content .local/deployment.json -Raw | ConvertFrom-Json
& mvn -B -f backend/pom.xml "-Dmaven.repo.local=$((Get-Location).Path)/.local/m2" test-compile dependency:build-classpath "-Dmdep.outputFile=$((Get-Location).Path)/.local/classpath.txt" *> .local/smoke-build.log
if ($LASTEXITCODE -ne 0) { throw 'Smoke test compilation failed' }
$previousStorage=$env:AzureWebJobsStorage
$previousBase=$env:PUBLIC_BASE_URL
try {
    $env:AzureWebJobsStorage=(& az storage account show-connection-string --resource-group $deployment.resourceGroup --name $deployment.storageAccount --query connectionString -o tsv)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read deployment storage connection' }
    $env:PUBLIC_BASE_URL=$deployment.functionUrl
    $classpath="backend/target/test-classes;backend/target/classes;$(Get-Content .local/classpath.txt -Raw)"
    $smokeArgs=@($Day)
    if ($Repair) { $smokeArgs+='--repair' }
    & java -cp $classpath sk.nhl.LiveSmoke @smokeArgs
    if ($LASTEXITCODE -ne 0) { throw 'Live smoke verification failed' }
} finally { $env:AzureWebJobsStorage=$previousStorage; $env:PUBLIC_BASE_URL=$previousBase }
