targetScope = 'resourceGroup'

param location string = 'westeurope'
param staticAppName string = 'nhl-analyzer-online'
param deploymentIdentityName string = 'nhlpredict-github'

resource website 'Microsoft.Web/staticSites@2024-04-01' = {
  name: staticAppName
  location: location
  sku: { name: 'Free', tier: 'Free' }
  properties: {
    provider: 'Custom'
    allowConfigFileUpdates: true
    publicNetworkAccess: 'Enabled'
    buildProperties: { skipGithubActionWorkflowGeneration: true }
  }
  tags: { project: 'nhlprediction', displayName: 'NHL Analyzer Online' }
}

resource identity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' existing = {
  name: deploymentIdentityName
}

// The pipeline retrieves the deployment token after OIDC login; it needs no other site permissions.
resource tokenReader 'Microsoft.Authorization/roleDefinitions@2022-04-01' = {
  name: guid(resourceGroup().id, 'nhl-analyzer-static-deployment-token')
  properties: {
    roleName: 'NHL Analyzer Static Website Deployment Token'
    description: 'Read this project Static Web App and its deployment token for CI/CD.'
    type: 'CustomRole'
    assignableScopes: [resourceGroup().id]
    permissions: [{
      actions: ['Microsoft.Web/staticSites/read', 'Microsoft.Web/staticSites/listsecrets/action']
      notActions: []
      dataActions: []
      notDataActions: []
    }]
  }
}

resource publishWebsite 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(website.id, identity.id, tokenReader.id)
  scope: website
  properties: {
    roleDefinitionId: tokenReader.id
    principalId: identity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

output websiteUrl string = 'https://${website.properties.defaultHostname}'
output websiteHostname string = website.properties.defaultHostname
output staticAppName string = website.name
