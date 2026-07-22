param(
    [string]$ModelName = "electrahub-sparky:4b",
    [string]$BaseModel = "qwen3:4b-instruct",
    [string]$Modelfile = "$PSScriptRoot\..\..\ollama\Modelfile"
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command ollama -ErrorAction SilentlyContinue)) {
    throw "Ollama CLI was not found. Install Ollama first, then rerun this script."
}

if (-not (Test-Path -LiteralPath $Modelfile)) {
    throw "Sparky Modelfile was not found: $Modelfile"
}

ollama pull $BaseModel
ollama create $ModelName -f $Modelfile
ollama show $ModelName
