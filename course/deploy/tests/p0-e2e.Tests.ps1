$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$schema = Get-Content (Join-Path $root 'course/deploy/schema.sql') -Raw
[xml]$model = Get-Content (Join-Path $root 'docs/course-architecture/medical-cloud-course.pdm') -Raw
$ns = New-Object Xml.XmlNamespaceManager($model.NameTable)
$ns.AddNamespace('o','object'); $ns.AddNamespace('a','attribute'); $ns.AddNamespace('c','collection')
function Value($node, $path) { $item = $node.SelectSingleNode($path, $ns); if ($item) { $item.InnerText } }
Describe 'P0 schema and editable PowerDesigner model contract' {
    $tables = [regex]::Matches($schema, '(?ms)CREATE TABLE IF NOT EXISTS (\w+)\s*\((.*?)\r?\n\);')
    It 'has exactly the schema tables and foreign keys' {
        $model.SelectNodes('//o:Table[@Id]', $ns).Count | Should Be 8
        $model.SelectNodes('//o:Reference[@Id]', $ns).Count | Should Be 14
    }
    foreach ($table in $tables) {
        $code = $table.Groups[1].Value
        $body = $table.Groups[2].Value
        It "preserves $code column types, nullability, primary key and index column order" {
            $node = $model.SelectSingleNode("//o:Table[@Id][a:Code='$code']", $ns)
            $node | Should Not BeNullOrEmpty
            $columns = [regex]::Matches($body, '(?m)^\s*(\w+)\s+(VARCHAR\(\d+\)|DECIMAL\(\d+,\d+\)|DATETIME(?:\(6\))?|DATE|TIME|INT)([^\r\n]*)')
            $node.SelectNodes('c:Columns/o:Column', $ns).Count | Should Be $columns.Count
            foreach ($column in $columns) {
                $name = $column.Groups[1].Value
                $actual = $node.SelectSingleNode("c:Columns/o:Column[a:Code='$name']", $ns)
                (Value $actual 'a:DataType') | Should Be $column.Groups[2].Value.ToLowerInvariant()
                ((Value $actual 'a:Column.Mandatory') -eq '1') | Should Be ($column.Groups[3].Value -match 'NOT NULL|PRIMARY KEY')
                if ($column.Groups[3].Value -match 'PRIMARY KEY') {
                    $keyRef = $node.SelectSingleNode('c:PrimaryKey/o:Key', $ns).Ref
                    $key = $node.SelectSingleNode("c:Keys/o:Key[@Id='$keyRef']", $ns)
                    $key.SelectSingleNode('c:Key.Columns/o:Column', $ns).Ref | Should Be $actual.Id
                }
            }
            foreach ($index in [regex]::Matches($body, '(?:CONSTRAINT (\w+) UNIQUE|INDEX (\w+))\s*\(([^)]+)\)')) {
                $name = $index.Groups[1].Value + $index.Groups[2].Value
                $unique = $index.Groups[1].Success
                if ($unique) {
                    $key = $node.SelectSingleNode("c:Keys/o:Key[a:Code='$name']", $ns)
                    $key | Should Not BeNullOrEmpty
                    $refs = @($key.SelectNodes('c:Key.Columns/o:Column', $ns))
                } else {
                    $key = $node.SelectSingleNode("c:Indexes/o:Index[a:Code='$name']", $ns)
                    $key | Should Not BeNullOrEmpty
                    $refs = @($key.SelectNodes('c:IndexColumns/o:IndexColumn/c:Column/o:Column', $ns))
                    (Value $key 'a:Unique') | Should Not Be '1'
                }
                $names = @($refs | ForEach-Object { Value ($node.SelectSingleNode("c:Columns/o:Column[@Id='$($_.Ref)']", $ns)) 'a:Code' })
                ($names -join ',') | Should Be ($index.Groups[3].Value -replace '\s','')
            }
        }
    }
}
Describe 'P0 isolated demonstration contract' {
    It 'rejects a malformed resume fixture before making HTTP requests' {
        $fixture = Join-Path $TestDrive 'bad.json'
        '{"runId":"not-a-uuid","apiBase":"http://127.0.0.1:1/paas/api"}' | Set-Content $fixture
        { & (Join-Path $root 'course/deploy/scripts/e2e-demo.ps1') -ApiBase 'http://127.0.0.1:1/paas/api' -FixturePath $fixture } | Should Throw 'Invalid fixture'
    }
    foreach ($case in @(
        @{Name='an impossible calendar date'; Field='date'; Value='2026-02-30'},
        @{Name='an impossible slot time'; Field='startTime'; Value='09:65:00'},
        @{Name='a string instead of a slot checkpoint'; Field='slotPrepared'; Value='false'},
        @{Name='a string instead of a cancellation checkpoint'; Field='cancelDone'; Value='false'},
        @{Name='a completed cancellation without an appointment ID'; Field='cancelDone'; Value=$true}
    )) {
        It "rejects $($case.Name) before making HTTP requests" {
            $fixture = Join-Path $TestDrive 'invalid-checkpoint.json'
            $data = [ordered]@{
                version=1; runId='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
                apiBase='http://127.0.0.1:1/paas/api'; username='p0_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';
                password='P0@bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'; date='2030-05-21'; startTime='09:00:00';
                slotPrepared=$false; slotId=''; canceledAppointmentId=''; cancelDone=$false; appointmentId=''
            }
            $data[$case.Field] = $case.Value
            $data | ConvertTo-Json | Set-Content -LiteralPath $fixture -Encoding UTF8
            { & (Join-Path $root 'course/deploy/scripts/e2e-demo.ps1') -ApiBase $data.apiBase -FixturePath $fixture } | Should Throw 'Invalid fixture'
        }
    }
    It 'registers, publishes, filters, cancels, rebooks, completes and pays in real containers' -Skip:(-not $env:COURSE_P0_API_BASE) {
        $smoke = & (Join-Path $root 'course/deploy/scripts/smoke-test.ps1') -PortalUrl $env:COURSE_P0_PORTAL_URL -CatalogUrl "$env:COURSE_P0_API_BASE/catalog/slots?tenantId=11111111-1111-1111-1111-111111111111"
        ($smoke | Where-Object Component -eq 'Tomcat registration').Status | Should Be 200
        $fixture = Join-Path $TestDrive 'live.json'
        $demo = Join-Path $root 'course/deploy/scripts/e2e-demo.ps1'
        $first = & $demo -ApiBase $env:COURSE_P0_API_BASE -FixturePath $fixture
        $first.Result | Should Be 'PASS'
        $first.CanceledAppointmentId | Should Not Be $first.AppointmentId
        $first.Payment | Should Be 'PAID'
        $first.PaidAt | Should Not BeNullOrEmpty
        $first.CompletedAt | Should Not BeNullOrEmpty
        $first.DoctorRecord | Should Be $first.RecordId
        $first.CrossTenantDoctor | Should Be 403
        $first.HospitalOrder | Should Be $first.OrderId
        $again = & $demo -ApiBase $env:COURSE_P0_API_BASE -FixturePath $fixture
        $again.AppointmentId | Should Be $first.AppointmentId
        $again.OrderId | Should Be $first.OrderId
        $again.RecordId | Should Be $first.RecordId
        $again.PaidAt | Should Be $first.PaidAt
    }
}
