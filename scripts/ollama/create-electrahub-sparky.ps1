param(
    [string]$ModelName = "electrahub-sparky",
    [string]$Modelfile = "$PSScriptRoot\..\..\ollama\Modelfile"
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command ollama -ErrorAction SilentlyContinue)) {
    throw "Ollama CLI was not found. Install Ollama first, then rerun this script."
}

ollama pull llama3.1:8b
ollama create $ModelName -f $Modelfile
ollama show $ModelName
