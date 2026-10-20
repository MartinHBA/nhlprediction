param([string]$ResourceGroup='nhlprediction',[string]$Location='westeurope')
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
& az group create --name $ResourceGroup --location $Location --tags project=nhlprediction aiBilling=chatgpt-subscription --output none
if ($LASTEXITCODE -ne 0) { throw 'Resource group creation failed' }
$existing=& az deployment group show --resource-group $ResourceGroup --name nhl-infrastructure --query properties.provisioningState -o tsv 2>$null
if ($existing -ne 'Succeeded') {
    & az deployment group create --resource-group $ResourceGroup --name nhl-infrastructure --template-file infrastructure/main.bicep --parameters "location=$Location" --output none
    if ($LASTEXITCODE -ne 0) { throw 'Infrastructure deployment failed' }
}
$app=& az deployment group show --resource-group $ResourceGroup --name nhl-infrastructure --query properties.outputs.functionAppName.value -o tsv
if ($LASTEXITCODE -ne 0) { throw 'Cannot read Function App name' }
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/package-backend.ps1 -FunctionAppName $app
if ($LASTEXITCODE -ne 0) { throw 'Packaging failed' }
& az functionapp deployment source config-zip --resource-group $ResourceGroup --name $app --src .local/functions.zip --output none
if ($LASTEXITCODE -ne 0) { throw 'Function deployment failed' }
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/configure-azure.ps1 -ResourceGroup $ResourceGroup -UploadFrontend
if ($LASTEXITCODE -ne 0) { throw 'Authentication or frontend deployment failed' }
