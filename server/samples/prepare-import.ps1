param(
    [Parameter(Mandatory)][string]$KnowledgeBaseId,
    [Parameter(Mandatory)][string]$ProductId,
    [Parameter(Mandatory)][string]$PolicyId,
    [Parameter(Mandatory)][string]$FaqId,
    [Parameter(Mandatory)][string]$RulesId,
    [Parameter(Mandatory)][string]$OperationsId
)
$ErrorActionPreference = 'Stop'
$importBody = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'evaluation/baseline.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$importBody.knowledgeBaseId = $KnowledgeBaseId
$importBody.documentMapping.product = $ProductId
$importBody.documentMapping.policy = $PolicyId
$importBody.documentMapping.faq = $FaqId
$importBody.documentMapping.rules = $RulesId
$importBody.documentMapping.operations = $OperationsId
$importOutput = Join-Path $PSScriptRoot '../target/baseline-import.json'
New-Item -ItemType Directory -Force -Path (Split-Path $importOutput) | Out-Null
$importBody | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $importOutput -Encoding UTF8
Write-Output ([IO.Path]::GetFullPath($importOutput))
