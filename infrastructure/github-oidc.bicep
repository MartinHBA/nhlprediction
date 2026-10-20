targetScope = 'resourceGroup'

param location string = resourceGroup().location
param identityName string = 'nhlpredict-github'
param repository string = 'MartinHBA/nhlprediction'
param repositoryOwnerId string = '49184077'
param repositoryId string = '1426535972'
param branch string = 'main'
param functionAppName string = 'nhlpredict-txocp2arkmldw'
param storageAccountName string = 'nhlpredicttxocp2arkmldw'

resource app 'Microsoft.Web/sites@2023-12-01' existing = {
  name: functionAppName
}
resource storage 'Microsoft.Storage/storageAccounts@2023-05-01' existing = {
  name: storageAccountName
}
resource blobs 'Microsoft.Storage/storageAccounts/blobServices@2023-05-01' existing = {
  parent: storage
  name: 'default'
}
resource website 'Microsoft.Storage/storageAccounts/blobServices/containers@2023-05-01' existing = {
  parent: blobs
  name: '$web'
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
var blobContributor = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', 'ba92f5b4-2d11-453d-a403-e96b0029c9fe')
resource publishWebsite 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(website.id, identity.id, blobContributor)
  scope: website
  properties: {
    roleDefinitionId: blobContributor
    principalId: identity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

output clientId string = identity.properties.clientId
output tenantId string = subscription().tenantId
output subscriptionId string = subscription().subscriptionId
output identityResourceId string = identity.id
