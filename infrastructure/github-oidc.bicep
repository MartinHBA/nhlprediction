targetScope = 'resourceGroup'

param location string = resourceGroup().location
param identityName string = 'nhlpredict-github'
param repository string = 'MartinHBA/nhlprediction'
param repositoryOwnerId string = '49184077'
param repositoryId string = '1426535972'
param branch string = 'main'
param functionAppName string = 'nhlpredict-txocp2arkmldw'
param staticAppName string = 'nhl-analyzer-online'

resource app 'Microsoft.Web/sites@2023-12-01' existing = {
  name: functionAppName
}

resource identity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: identityName
  location: location
}
resource github 'Microsoft.ManagedIdentity/userAssignedIdentities/federatedIdentityCredentials@2023-01-31' = {
  parent: identity
  name: 'github-main'
  properties: {
    issuer: 'https://token.actions.githubusercontent.com'
    // GitHub emits immutable owner/repository IDs in this repository's OIDC subject.
    subject: 'repo:${split(repository, '/')[0]}@${repositoryOwnerId}/${split(repository, '/')[1]}@${repositoryId}:ref:refs/heads/${branch}'
    audiences: ['api://AzureADTokenExchange']
  }
}

var websiteContributor = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', 'de139f84-1756-47ae-9be6-808fbbe84772')
resource deployFunctions 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(app.id, identity.id, websiteContributor)
  scope: app
  properties: {
    roleDefinitionId: websiteContributor
    principalId: identity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}
module website 'static-web.bicep' = {
  name: 'nhl-static-web'
  params: {
    staticAppName: staticAppName
    location: location
    deploymentIdentityName: identityName
  }
  dependsOn: [identity]
}

output clientId string = identity.properties.clientId
output tenantId string = subscription().tenantId
output subscriptionId string = subscription().subscriptionId
output identityResourceId string = identity.id
output websiteUrl string = website.outputs.websiteUrl
