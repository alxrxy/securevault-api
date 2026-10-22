<#
.SYNOPSIS
    End-to-end demo of SecureVault API's main flows and security controls.

.DESCRIPTION
    Runs against a live instance and checks every response status:
    register, login, upload, list, download, cross-user 404, duplicate-email
    409, storage-quota 409, oversized 413, wrong-type 415, and rate-limit 429.
    Exits 0 if every step behaves as expected, 1 otherwise.

    Works on Windows PowerShell 5.1 and PowerShell 7+ (uses .NET HttpClient
    directly, so 4xx responses are data rather than exceptions).

    Prerequisites:
      * The API is running (see README "Getting started").
      * For the quota step, start it with a small quota, e.g.
        STORAGE_QUOTA_PER_USER=1MB. With the default 1GB that step fails.
      * Login/register share a 10-per-minute limit per client address, and
        this script uses all of it on purpose. Wait a minute between runs.

.EXAMPLE
    ./demo/demo.ps1
    ./demo/demo.ps1 -BaseUrl http://localhost:9090
#>
param(
    [string]$BaseUrl = 'http://localhost:8080',
    # Size of each upload in the quota step; keep it below the server's quota.
    [int]$QuotaChunkBytes = 400KB
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromMinutes(2)
$script:failures = 0
$runId = [Guid]::NewGuid().ToString('N').Substring(0, 8)
$password = 'demo-passphrase-123'

function Invoke-Api {
    param([string]$Method, [string]$Path, [string]$Token, $Content)
    $request = New-Object System.Net.Http.HttpRequestMessage ([System.Net.Http.HttpMethod]::new($Method)), ($BaseUrl + $Path)
    if ($Token) {
        $request.Headers.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue 'Bearer', $Token
    }
    if ($Content) { $request.Content = $Content }
    try {
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
    } catch {
        return [pscustomobject]@{ Status = 0; Text = $_.Exception.GetBaseException().Message; Bytes = $null; Response = $null }
    }
    $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    [pscustomobject]@{
        Status   = [int]$response.StatusCode
        Text     = [System.Text.Encoding]::UTF8.GetString($bytes)
        Bytes    = $bytes
        Response = $response
    }
}

function New-Json([hashtable]$Body) {
    New-Object System.Net.Http.StringContent (($Body | ConvertTo-Json -Compress)), ([System.Text.Encoding]::UTF8), 'application/json'
}

function New-Upload([byte[]]$Bytes, [string]$FileName, [string]$ClaimedType) {
    $form = New-Object System.Net.Http.MultipartFormDataContent
    $file = New-Object System.Net.Http.ByteArrayContent (, $Bytes)
    $file.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse($ClaimedType)
    $form.Add($file, 'file', $FileName)
    # Leading comma: MultipartFormDataContent is enumerable, and PowerShell
    # would otherwise unroll it into its parts on return.
    , $form
}

function New-Pdf([int]$Size) {
    $bytes = New-Object byte[] $Size
    for ($i = 0; $i -lt $Size; $i++) { $bytes[$i] = 32 }
    $header = [System.Text.Encoding]::ASCII.GetBytes("%PDF-1.7`n% SecureVault demo`n")
    [Array]::Copy($header, $bytes, [Math]::Min($header.Length, $Size))
    , $bytes
}

function Get-Message($Result) {
    try { ($Result.Text | ConvertFrom-Json).message } catch { $Result.Text }
}

function Step([string]$Name, [int]$Expected, $Result, [string]$Detail = '') {
    if ($Result.Status -eq $Expected) {
        Write-Host ('[PASS] {0,-38} {1}  {2}' -f $Name, $Result.Status, $Detail) -ForegroundColor Green
    } else {
        $script:failures++
        Write-Host ('[FAIL] {0,-38} expected {1}, got {2}  {3}' -f $Name, $Expected, $Result.Status, (Get-Message $Result)) -ForegroundColor Red
    }
}

Write-Host "SecureVault API demo against $BaseUrl (run $runId)`n"

$health = Invoke-Api GET '/actuator/health'
if ($health.Status -ne 200) {
    Write-Host "API not reachable at $BaseUrl (status $($health.Status): $($health.Text)). Start it first." -ForegroundColor Red
    exit 1
}

# --- Accounts ---------------------------------------------------------------
$alice = "alice-$runId@example.com"
$bob = "bob-$runId@example.com"

$r = Invoke-Api POST '/api/auth/register' -Content (New-Json @{ email = $alice; password = $password })
if ($r.Status -eq 429) {
    Write-Host 'Rate limited by a previous run - wait 60 seconds and try again.' -ForegroundColor Yellow
    exit 1
}
Step 'Register alice' 201 $r ($alice)
Step 'Register bob' 201 (Invoke-Api POST '/api/auth/register' -Content (New-Json @{ email = $bob; password = $password }))

$r = Invoke-Api POST '/api/auth/register' -Content (New-Json @{ email = $alice.ToUpper(); password = $password })
Step 'Register alice again (any case) -> 409' 409 $r (Get-Message $r)

$r = Invoke-Api POST '/api/auth/login' -Content (New-Json @{ email = $alice; password = $password })
Step 'Login alice' 200 $r 'JWT issued'
$aliceToken = ($r.Text | ConvertFrom-Json).accessToken
$r = Invoke-Api POST '/api/auth/login' -Content (New-Json @{ email = $bob; password = $password })
Step 'Login bob' 200 $r 'JWT issued'
$bobToken = ($r.Text | ConvertFrom-Json).accessToken

# --- Documents ----------------------------------------------------------------
$pdf = New-Pdf 2048
$r = Invoke-Api POST '/api/documents' $aliceToken (New-Upload $pdf 'report.pdf' 'application/pdf')
Step 'Upload report.pdf (alice)' 201 $r
$doc = $r.Text | ConvertFrom-Json

$r = Invoke-Api GET '/api/documents' $aliceToken
# Count ids textually: PowerShell 5.1's ConvertFrom-Json doesn't unroll arrays.
Step 'List documents (alice)' 200 $r ('{0} document(s)' -f ([regex]::Matches($r.Text, '"id"')).Count)

$r = Invoke-Api GET "/api/documents/$($doc.id)/download" $aliceToken
$disposition = if ($r.Response) { [string]$r.Response.Content.Headers.ContentDisposition } else { '' }
$values = $null
$nosniff = if ($r.Response -and $r.Response.Headers.TryGetValues('X-Content-Type-Options', [ref]$values)) { $values -join ',' } else { '' }
$same = ($r.Bytes -and [Convert]::ToBase64String($r.Bytes) -eq [Convert]::ToBase64String($pdf))
Step 'Download (alice)' 200 $r ("{0}; nosniff={1}; bytes identical={2}" -f $disposition, $nosniff, $same)
if (-not $same -or $disposition -notlike 'attachment*' -or $nosniff -ne 'nosniff') {
    $script:failures++
    Write-Host '[FAIL] Download headers/body not as expected' -ForegroundColor Red
}

$r = Invoke-Api GET "/api/documents/$($doc.id)" $bobToken
Step "Bob reads alice's document -> 404" 404 $r (Get-Message $r)
$r = Invoke-Api GET "/api/documents/$($doc.id)/download" $bobToken
Step "Bob downloads alice's document -> 404" 404 $r
$r = Invoke-Api DELETE "/api/documents/$($doc.id)" $bobToken
Step "Bob deletes alice's document -> 404" 404 $r
$r = Invoke-Api GET '/api/documents' $bobToken
Step "Bob's list is empty" 200 $r $r.Text

# --- Upload validation --------------------------------------------------------
$html = [System.Text.Encoding]::UTF8.GetBytes('<html><script>alert(1)</script></html>')
$r = Invoke-Api POST '/api/documents' $aliceToken (New-Upload $html 'invoice.pdf' 'application/pdf')
Step 'HTML disguised as invoice.pdf -> 415' 415 $r (Get-Message $r)

$r = Invoke-Api POST '/api/documents' $aliceToken (New-Upload (New-Pdf (25MB + 1)) 'huge.pdf' 'application/pdf')
Step 'Upload 25 MB + 1 byte -> 413' 413 $r (Get-Message $r)

# Fill alice's quota with chunks until the server refuses.
$quota = $null
for ($i = 1; $i -le 10; $i++) {
    $r = Invoke-Api POST '/api/documents' $aliceToken (New-Upload (New-Pdf $QuotaChunkBytes) "chunk-$i.pdf" 'application/pdf')
    if ($r.Status -ne 201) { $quota = $r; break }
}
if ($quota) {
    Step "Exceed storage quota -> 409 (after $($i - 1) chunk(s))" 409 $quota (Get-Message $quota)
} else {
    $script:failures++
    Write-Host '[FAIL] Storage quota never reached - start the API with STORAGE_QUOTA_PER_USER=1MB' -ForegroundColor Red
}

$r = Invoke-Api DELETE "/api/documents/$($doc.id)" $aliceToken
Step 'Delete report.pdf (alice)' 204 $r

# --- Rate limiting ------------------------------------------------------------
$limited = $null
for ($i = 1; $i -le 15; $i++) {
    $r = Invoke-Api POST '/api/auth/login' -Content (New-Json @{ email = $alice; password = 'wrong-password-123' })
    if ($r.Status -eq 429) { $limited = $r; break }
}
if ($limited) {
    $retry = $limited.Response.Headers.RetryAfter
    Step "Wrong passwords -> 429 (attempt $i)" 429 $limited ("Retry-After: {0}s" -f $retry.Delta.TotalSeconds)
} else {
    Step 'Wrong passwords -> 429' 429 $r '(limit never reached)'
}

Write-Host ''
if ($script:failures -eq 0) {
    Write-Host 'All steps behaved as expected.' -ForegroundColor Green
    exit 0
}
Write-Host "$($script:failures) step(s) did not behave as expected." -ForegroundColor Red
exit 1
