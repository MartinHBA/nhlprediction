param([string]$ResourceGroup='nhlprediction',[string]$StaticWebAppName='nhl-analyzer-online',[string]$Hostname='www.nhlanalyzer.online',[string]$FunctionAppName='nhlpredict-txocp2arkmldw')
$ErrorActionPreference='Stop'
if ($Hostname -notmatch '^[a-z0-9][a-z0-9.-]+\.[a-z]{2,}$') { throw 'Invalid hostname' }
$site=(& az staticwebapp show --resource-group $ResourceGroup --name $StaticWebAppName --output json | ConvertFrom-Json)
if ($LASTEXITCODE -ne 0) { throw 'Cannot read Static Web App' }
$cname=@(Resolve-DnsName -Name $Hostname -Type CNAME -ErrorAction Stop | Where-Object Type -eq CNAME | Select-Object -ExpandProperty NameHost)
if ($cname -notcontains $site.defaultHostname) { throw "Set CNAME $Hostname to $($site.defaultHostname) at your DNS provider first." }
& az staticwebapp hostname set --resource-group $ResourceGroup --name $StaticWebAppName --hostname $Hostname --validation-method cname-delegation --no-wait --output none --only-show-errors
if ($LASTEXITCODE -ne 0) { throw 'Domain binding failed' }
$domain=(& az staticwebapp hostname show --resource-group $ResourceGroup --name $StaticWebAppName --hostname $Hostname --output json | ConvertFrom-Json)
if ($LASTEXITCODE -ne 0) { throw 'Cannot read domain binding' }
if ($domain.status -eq 'Ready') {
    & az functionapp config appsettings set --resource-group $ResourceGroup --name $FunctionAppName --settings "WEB_URL=https://$Hostname" --query '[].name' --output none --only-show-errors
    if ($LASTEXITCODE -ne 0) { throw 'Public website URL update failed' }
    Write-Output "Domain ready: https://$Hostname"
} else {
    Write-Output "Domain status: $($domain.status). Wait for DNS validation and managed certificate, then run this script again."
}
