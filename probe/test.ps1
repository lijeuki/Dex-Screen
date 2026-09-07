$ErrorActionPreference = 'Stop'
$out = Join-Path $PSScriptRoot 'build-tests'
& javac -d $out "$PSScriptRoot\app\src\main\java\local\dexprobe\Report.java" "$PSScriptRoot\tests\ReportTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
& java -cp $out ReportTest
if ($LASTEXITCODE -ne 0) { throw 'Report tests failed' }
