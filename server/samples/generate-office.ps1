# 可选：用已安装的 Microsoft Word 重新生成自编二进制测试样本。
$ErrorActionPreference = 'Stop'
$sampleRoot = $PSScriptRoot
$sampleDefinitions = Get-Content -LiteralPath (Join-Path $sampleRoot 'source.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$sampleWord = New-Object -ComObject Word.Application
$sampleWord.Visible = $false
$sampleWord.DisplayAlerts = 0
try {
    foreach ($sample in $sampleDefinitions) {
        if ($sample.file -notmatch '\.(pdf|doc|docx)$') { continue }
        $sampleDocument = $sampleWord.Documents.Add()
        try {
            $sampleDocument.Content.Text = $sample.text
            $sampleDocument.Content.Font.NameFarEast = 'Microsoft YaHei'
            $sampleDocument.Content.Font.Size = 11
            $sampleTarget = Join-Path (Join-Path $sampleRoot 'documents') $sample.file
            $sampleFormat = switch ([IO.Path]::GetExtension($sample.file)) { '.pdf' {17} '.doc' {0} '.docx' {16} }
            $sampleDocument.SaveAs2([string]$sampleTarget, [int]$sampleFormat)
        } finally {
            $sampleDocument.Close(0)
            [void][Runtime.InteropServices.Marshal]::ReleaseComObject($sampleDocument)
        }
    }
} finally {
    $sampleWord.Quit()
    [void][Runtime.InteropServices.Marshal]::ReleaseComObject($sampleWord)
}
