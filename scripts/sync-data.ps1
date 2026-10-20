param([string]$Day,[string]$PrefillFile,[switch]$Finalize)
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (!$Day) { $Day=[TimeZoneInfo]::ConvertTimeFromUtc([DateTime]::UtcNow,[TimeZoneInfo]::FindSystemTimeZoneById('Eastern Standard Time')).ToString('yyyy-MM-dd') }
$deployment=Get-Content .local/deployment.json -Raw | ConvertFrom-Json
& mvn -B -f backend/pom.xml "-Dmaven.repo.local=$((Get-Location).Path)/.local/m2" compile dependency:build-classpath "-Dmdep.outputFile=$((Get-Location).Path)/.local/classpath.txt" *> .local/sync-build.log
if ($LASTEXITCODE -ne 0) { throw 'Data collector compilation failed' }
$previousStorage=$env:AzureWebJobsStorage
try {
    $env:AzureWebJobsStorage=(& az storage account show-connection-string --resource-group $deployment.resourceGroup --name $deployment.storageAccount --query connectionString -o tsv)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read deployment storage connection' }
    $classpath="backend/target/classes;$(Get-Content .local/classpath.txt -Raw)"
    $syncArgs=@($Day, $(if($PrefillFile){(Resolve-Path -LiteralPath $PrefillFile).Path}else{'-'}))
    if ($Finalize) { $syncArgs+='--finalize' }
    & java -cp $classpath sk.nhl.DataSync @syncArgs
    if ($LASTEXITCODE -ne 0) { throw 'Native data import failed' }
} finally { $env:AzureWebJobsStorage=$previousStorage }
