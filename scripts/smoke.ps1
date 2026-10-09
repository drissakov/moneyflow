param([string]$BaseUrl = 'http://localhost:8080')

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')

function Invoke-Api {
    param([string]$Method, [string]$Path, $Body, [string]$Token = '', [string]$Key = '', [int]$Expected = 200)
    $headers = @{}
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    if ($Key) { $headers['Idempotency-Key'] = $Key }
    $params = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $headers; SkipHttpErrorCheck = $true }
    if ($null -ne $Body) { $params.Body = $Body | ConvertTo-Json -Compress; $params.ContentType = 'application/json' }
    $response = Invoke-WebRequest @params
    if ([int]$response.StatusCode -ne $Expected) { throw "$Method $Path expected $Expected, received $($response.StatusCode): $($response.Content)" }
    if ($response.Content) { return $response.Content | ConvertFrom-Json }
}

$suffix = [guid]::NewGuid().ToString('N')
$password = [guid]::NewGuid().ToString('N') + '!Aa7'
$first = $null
$second = $null
try {
    $null = Invoke-Api GET '/readyz' $null
    $first = Invoke-Api POST '/api/v1/auth/register' @{ email = "smoke-$suffix@moneyflow.test"; password = $password } -Expected 201
    $second = Invoke-Api POST '/api/v1/auth/register' @{ email = "other-$suffix@moneyflow.test"; password = $password } -Expected 201
    $account = Invoke-Api POST '/api/v1/accounts' @{ name = 'Smoke account'; currency = 'USD'; opening_balance_minor = '10000' } -Token $first.access_token -Expected 201
    $operationKey = [guid]::NewGuid().ToString()
    $expense = @{ account_id = $account.id; kind = 'expense'; amount_minor = '1299'; note = 'Live API verification'; occurred_at = [DateTime]::UtcNow.ToString('o') }
    $created = Invoke-Api POST '/api/v1/transactions' $expense -Token $first.access_token -Key $operationKey -Expected 201
    $replayed = Invoke-Api POST '/api/v1/transactions' $expense -Token $first.access_token -Key $operationKey -Expected 201
    if ($created.id -ne $replayed.id) { throw 'A retry created another transaction' }
    $accounts = Invoke-Api GET '/api/v1/accounts' $null -Token $first.access_token
    if ($accounts.accounts[0].balance_minor -ne '8701') { throw 'Incorrect exact balance' }
    $transactions = Invoke-Api GET "/api/v1/transactions?account_id=$($account.id)&limit=50" $null -Token $first.access_token
    if ($transactions.transactions.Count -ne 1) { throw 'Expected exactly one expense' }
    $null = Invoke-Api GET "/api/v1/transactions?account_id=$($account.id)" $null -Token $second.access_token -Expected 404
    $expense.amount_minor = '1300'
    $null = Invoke-Api POST '/api/v1/transactions' $expense -Token $first.access_token -Key $operationKey -Expected 409
    $null = Invoke-Api POST '/api/v1/auth/logout' $null -Token $first.access_token -Expected 204
    $null = Invoke-Api GET '/api/v1/me' $null -Token $first.access_token -Expected 401
    Write-Output 'PASS: live PostgreSQL API, exact balance, retry deduplication, ownership isolation, conflicting retry and logout.'
} finally {
    foreach ($session in @($first, $second)) {
        if ($session) { try { $null = Invoke-Api POST '/api/v1/auth/logout' $null -Token $session.access_token -Expected 204 } catch { } }
    }
}
