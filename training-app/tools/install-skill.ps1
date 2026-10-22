$ErrorActionPreference = 'Stop'
# Install only in this project; never access user-level or global skill directories.
$project = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$source = (Resolve-Path (Join-Path $PSScriptRoot '..\skills\training-assistant')).Path
$parent = Join-Path $project '.omp\skills'
$destination = Join-Path $parent 'training-assistant'
New-Item -ItemType Directory -Path $parent -Force | Out-Null
if (Test-Path -LiteralPath $destination) {
    $existing = Get-Item -LiteralPath $destination
    if ($existing.LinkType -ne 'Junction' -or $existing.Target -notcontains $source) {
        throw "Project skill path already exists and points elsewhere: $destination"
    }
} else {
    New-Item -ItemType Junction -Path $destination -Target $source | Out-Null
}
Write-Output "Project skill installed: $destination"
