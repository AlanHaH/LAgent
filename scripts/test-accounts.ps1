param(
    [ValidateSet('menu', 'create', 'list', 'delete', 'reset')]
    [string]$Action = 'menu',
    [ValidateRange(1, 200)]
    [int]$Count = 5,
    [ValidateSet('STUDENT', 'ADMIN')]
    [string]$Role = 'STUDENT',
    [ValidatePattern('^[a-z][a-z0-9_]{2,20}$')]
    [string]$Prefix = 'test_user',
    [string]$Batch = '',
    [string]$Password = '',
    [switch]$All,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot '.env'
$composePath = Join-Path $projectRoot 'docker-compose.yml'
$composeLocalPath = Join-Path $projectRoot 'docker-compose.local.yml'
$hashTool = Join-Path $projectRoot 'frontend\e2e\tools\hash-password.mjs'

function Read-EnvValue([string]$Key) {
    if (-not (Test-Path $envPath)) {
        throw ".env was not found: $envPath"
    }
    $line = Get-Content $envPath | Where-Object { $_ -match "^$([regex]::Escape($Key))=" } | Select-Object -First 1
    if (-not $line) {
        throw "$Key is not configured in .env."
    }
    return $line.Substring($line.IndexOf('=') + 1).Trim()
}

function Escape-Sql([string]$Value) {
    return $Value.Replace('\', '\\').Replace("'", "''")
}

function Get-ComposeArguments {
    $arguments = @('compose', '-f', $composePath)
    if (Test-Path $composeLocalPath) {
        $arguments += @('-f', $composeLocalPath)
    }
    return $arguments
}

function Invoke-Database([string]$Sql, [switch]$TableOutput) {
    $rootPassword = Read-EnvValue 'MYSQL_ROOT_PASSWORD'
    $composeArguments = Get-ComposeArguments
    $mysqlArguments = @(
        $composeArguments
        'exec', '-T', '-e', "MYSQL_PWD=$rootPassword", 'mysql',
        'mysql', '-uroot', '--default-character-set=utf8mb4', 'adaptive_learning'
    )
    if ($TableOutput) {
        $mysqlArguments += '--table'
    } else {
        $mysqlArguments += @('--batch', '--raw', '--skip-column-names')
    }

    $output = $Sql | & docker @mysqlArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Database command failed with exit code $LASTEXITCODE."
    }
    return $output
}

function Initialize-Registry {
    Invoke-Database @'
CREATE TABLE IF NOT EXISTS dev_test_account_registry (
  user_id BIGINT NOT NULL PRIMARY KEY,
  batch_key VARCHAR(64) NOT NULL,
  username VARCHAR(50) NOT NULL,
  email VARCHAR(160) NOT NULL,
  role_code VARCHAR(50) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_dev_test_registry_username (username),
  KEY idx_dev_test_registry_batch (batch_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
'@ | Out-Null
}

function Convert-SecureStringToText([Security.SecureString]$SecureValue) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureValue)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Resolve-Password([string]$ProvidedPassword) {
    if ([string]::IsNullOrEmpty($ProvidedPassword)) {
        Write-Host 'Enter a shared password for this batch (8-128 characters).' -ForegroundColor Cyan
        Write-Host 'The input is hidden. Do not leave it blank.' -ForegroundColor DarkGray
        $secure = Read-Host 'Test account password' -AsSecureString
        $firstPassword = Convert-SecureStringToText $secure
        $confirmationSecure = Read-Host 'Confirm test account password' -AsSecureString
        $confirmedPassword = Convert-SecureStringToText $confirmationSecure
        if ($firstPassword -cne $confirmedPassword) {
            throw 'The two password entries do not match. No account was changed.'
        }
        $ProvidedPassword = $firstPassword
    }
    if ($ProvidedPassword.Length -lt 8 -or $ProvidedPassword.Length -gt 128) {
        throw 'Password length must be between 8 and 128 characters.'
    }
    return $ProvidedPassword
}

function Get-PasswordHash([string]$PlainPassword) {
    if (-not (Test-Path $hashTool)) {
        throw "Password hash tool was not found: $hashTool"
    }
    $nodeModules = Join-Path $projectRoot 'frontend\node_modules\bcryptjs'
    if (-not (Test-Path $nodeModules)) {
        throw 'bcryptjs is not installed. Run npm install in the frontend directory.'
    }

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = 'node'
    $startInfo.Arguments = "`"$hashTool`""
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $passwordBytes = [Text.Encoding]::UTF8.GetBytes($PlainPassword)
    $startInfo.EnvironmentVariables['TEST_ACCOUNT_PASSWORD_BASE64'] =
        [Convert]::ToBase64String($passwordBytes)
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    [void]$process.Start()
    $hash = $process.StandardOutput.ReadToEnd().Trim()
    $errorText = $process.StandardError.ReadToEnd().Trim()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($hash)) {
        throw "Could not create the password hash. $errorText"
    }
    return $hash
}

function Show-TestAccounts {
    Initialize-Registry
    Invoke-Database @'
SELECT
  r.batch_key AS batch_name,
  u.username,
  u.email,
  r.role_code AS role_name,
  u.status,
  u.last_login_at,
  r.created_at
FROM dev_test_account_registry r
LEFT JOIN sys_user u ON u.id = r.user_id
ORDER BY r.created_at DESC, r.username;
'@ -TableOutput
}

function New-TestAccounts {
    if ($Count -lt 1 -or $Count -gt 200) {
        throw 'Count must be between 1 and 200.'
    }
    if ($Prefix -notmatch '^[a-z][a-z0-9_]{2,20}$') {
        throw 'Prefix must use 3-21 lowercase letters, digits, or underscores, and start with a letter.'
    }
    if (-not [string]::IsNullOrWhiteSpace($Batch) -and $Batch -notmatch '^[A-Za-z0-9_.-]{1,64}$') {
        throw 'Batch may contain only letters, digits, underscore, dot, and hyphen.'
    }
    if ($Role -notin @('STUDENT', 'ADMIN')) {
        throw 'Role must be STUDENT or ADMIN.'
    }
    Initialize-Registry
    $plainPassword = Resolve-Password $Password
    $passwordHash = Get-PasswordHash $plainPassword
    $batchName = if ([string]::IsNullOrWhiteSpace($Batch)) {
        Get-Date -Format 'yyyyMMdd-HHmmss'
    } else {
        $Batch
    }
    $runTag = (Get-Date -Format 'MMddHHmmss') + (Get-Random -Minimum 10 -Maximum 99)
    $values = New-Object System.Collections.Generic.List[string]
    $registryValues = New-Object System.Collections.Generic.List[string]
    $escapedHash = Escape-Sql $passwordHash
    $escapedBatch = Escape-Sql $batchName

    for ($index = 1; $index -le $Count; $index++) {
        $username = '{0}_{1}_{2:d3}' -f $Prefix, $runTag, $index
        if ($username.Length -gt 50) {
            throw "Generated username is longer than 50 characters: $username"
        }
        $email = "$username@test.local"
        $idOffset = $index
        $values.Add("(@base_id + $idOffset, UUID(), '$(Escape-Sql $username)', '$(Escape-Sql $email)', CURRENT_TIMESTAMP(6), '$escapedHash', 'ACTIVE', 'Asia/Shanghai', 0, NULL, NULL, CURRENT_TIMESTAMP(6), 0, CURRENT_TIMESTAMP(6), 0, 0, NULL)")
        $registryValues.Add("(@base_id + $idOffset, '$escapedBatch', '$(Escape-Sql $username)', '$(Escape-Sql $email)', '$(Escape-Sql $Role)', CURRENT_TIMESTAMP(6))")
    }

    $sql = @"
START TRANSACTION;
SET @role_id = (SELECT id FROM sys_role WHERE code = '$(Escape-Sql $Role)' AND status = 'ACTIVE' LIMIT 1);
SET @base_id = FLOOR(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000) * 1000000;
INSERT INTO sys_user (
  id, public_id, username, email, email_verified_at, password_hash, status, timezone,
  login_failed_count, locked_until, last_login_at, created_at, created_by,
  updated_at, updated_by, version, deleted_at
) VALUES
$($values -join ",`n");
INSERT INTO sys_user_role (user_id, role_id)
SELECT id, @role_id FROM sys_user
WHERE id > @base_id AND id <= @base_id + $Count;
INSERT INTO dev_test_account_registry (
  user_id, batch_key, username, email, role_code, created_at
) VALUES
$($registryValues -join ",`n");
COMMIT;
SELECT username, email, '$(Escape-Sql $Role)' AS role_name, '$escapedBatch' AS batch_name
FROM sys_user
WHERE id > @base_id AND id <= @base_id + $Count
ORDER BY username;
"@
    $result = Invoke-Database $sql -TableOutput
    Write-Host ''
    Write-Host "Created $Count test account(s)." -ForegroundColor Green
    Write-Host "Batch: $batchName" -ForegroundColor Cyan
    Write-Host "Delete later with:" -ForegroundColor Yellow
    Write-Host ".\scripts\test-accounts.ps1 -Action delete -Batch `"$batchName`"" -ForegroundColor Yellow
    Write-Host ''
    $result
}

function Get-CleanupSql([string]$BatchFilter, [bool]$DeleteAll) {
    $whereClause = if ($DeleteAll) {
        "r.email LIKE '%@test.local'"
    } else {
        "r.batch_key = '$(Escape-Sql $BatchFilter)' AND r.email LIKE '%@test.local'"
    }

    return @"
CREATE TEMPORARY TABLE tmp_test_users (id BIGINT NOT NULL PRIMARY KEY);
INSERT INTO tmp_test_users (id)
SELECT r.user_id
FROM dev_test_account_registry r
JOIN sys_user u ON u.id = r.user_id
WHERE $whereClause
  AND u.email = r.email
  AND u.username = r.username
  AND u.email LIKE '%@test.local';

START TRANSACTION;

DELETE atc FROM agent_tool_call atc JOIN model_run mr ON mr.id=atc.model_run_id JOIN tmp_test_users t ON t.id=mr.user_id;

DELETE qf FROM qa_feedback qf JOIN tmp_test_users t ON t.id=qf.user_id;
DELETE qc FROM qa_citation qc JOIN qa_message qm ON qm.id=qc.message_id JOIN qa_session qs ON qs.id=qm.session_id JOIN tmp_test_users t ON t.id=qs.user_id;
DELETE qm FROM qa_message qm JOIN qa_session qs ON qs.id=qm.session_id JOIN tmp_test_users t ON t.id=qs.user_id;
DELETE qs FROM qa_session qs JOIN tmp_test_users t ON t.id=qs.user_id;

DELETE tm FROM tutoring_message tm JOIN tutoring_session ts ON ts.id=tm.session_id JOIN tmp_test_users t ON t.id=ts.user_id;
DELETE ts FROM tutoring_session ts JOIN tmp_test_users t ON t.id=ts.user_id;

DELETE ssp FROM study_session_pause ssp JOIN study_session ss ON ss.id=ssp.session_id JOIN tmp_test_users t ON t.id=ss.user_id;
DELETE ss FROM study_session ss JOIN tmp_test_users t ON t.id=ss.user_id;
DELETE snv FROM study_note_version snv JOIN study_note sn ON sn.id=snv.note_id JOIN tmp_test_users t ON t.id=sn.user_id;
DELETE sn FROM study_note sn JOIN tmp_test_users t ON t.id=sn.user_id;

DELETE tks FROM task_knowledge_source tks JOIN learning_task lt ON lt.id=tks.task_id JOIN tmp_test_users t ON t.id=lt.user_id;
DELETE tkp FROM task_knowledge_point tkp JOIN learning_task lt ON lt.id=tkp.task_id JOIN tmp_test_users t ON t.id=lt.user_id;
DELETE td FROM task_dependency td JOIN learning_task lt ON lt.id=td.predecessor_task_id JOIN tmp_test_users t ON t.id=lt.user_id;
DELETE td FROM task_dependency td JOIN learning_task lt ON lt.id=td.successor_task_id JOIN tmp_test_users t ON t.id=lt.user_id;
DELETE tsh FROM task_status_history tsh JOIN learning_task lt ON lt.id=tsh.task_id JOIN tmp_test_users t ON t.id=lt.user_id;
DELETE tsch FROM task_schedule_history tsch JOIN learning_task lt ON lt.id=tsch.task_id JOIN tmp_test_users t ON t.id=lt.user_id;
DELETE tcs FROM task_completion_summary tcs JOIN tmp_test_users t ON t.id=tcs.user_id;
DELETE lba FROM learning_block_attempt lba JOIN tmp_test_users t ON t.id=lba.user_id;
DELETE lb FROM learning_block lb JOIN tmp_test_users t ON t.id=lb.user_id;
DELETE lt FROM learning_task lt JOIN tmp_test_users t ON t.id=lt.user_id;

DELETE pc FROM plan_confirmation pc JOIN tmp_test_users t ON t.id=pc.user_id;
DELETE pci FROM plan_change_item pci JOIN plan_version pv ON pv.id=pci.plan_version_id JOIN learning_plan lp ON lp.id=pv.plan_id JOIN tmp_test_users t ON t.id=lp.user_id;
DELETE ps FROM plan_stage ps JOIN plan_version pv ON pv.id=ps.plan_version_id JOIN learning_plan lp ON lp.id=pv.plan_id JOIN tmp_test_users t ON t.id=lp.user_id;
DELETE pvr FROM plan_validation_result pvr JOIN plan_version pv ON pv.id=pvr.plan_version_id JOIN learning_plan lp ON lp.id=pv.plan_id JOIN tmp_test_users t ON t.id=lp.user_id;
DELETE pp FROM plan_publication pp JOIN learning_plan lp ON lp.id=pp.plan_id JOIN tmp_test_users t ON t.id=lp.user_id;
DELETE pj FROM planning_job pj JOIN tmp_test_users t ON t.id=pj.user_id;
DELETE pv FROM plan_version pv JOIN learning_plan lp ON lp.id=pv.plan_id JOIN tmp_test_users t ON t.id=lp.user_id;
DELETE lp FROM learning_plan lp JOIN tmp_test_users t ON t.id=lp.user_id;

DELETE gp FROM goal_project gp JOIN learning_goal lg ON lg.id=gp.goal_id JOIN tmp_test_users t ON t.id=lg.user_id;
DELETE gp FROM goal_project gp JOIN learning_project lp ON lp.id=gp.project_id JOIN tmp_test_users t ON t.id=lp.user_id;
DELETE m FROM milestone m JOIN learning_project lp ON lp.id=m.project_id JOIN tmp_test_users t ON t.id=lp.user_id;
DELETE orq FROM optimization_request orq JOIN tmp_test_users t ON t.id=orq.user_id;
DELETE lg FROM learning_goal lg JOIN tmp_test_users t ON t.id=lg.user_id;
DELETE lp FROM learning_project lp JOIN tmp_test_users t ON t.id=lp.user_id;

DELETE aa FROM assessment_appeal aa JOIN tmp_test_users t ON t.id=aa.user_id;
DELETE gr FROM grading_record gr JOIN attempt_answer ans ON ans.id=gr.answer_id JOIN assessment_attempt att ON att.id=ans.attempt_id JOIN tmp_test_users t ON t.id=att.user_id;
DELETE aa FROM assessment_appeal aa JOIN attempt_answer ans ON ans.id=aa.answer_id JOIN assessment_attempt att ON att.id=ans.attempt_id JOIN tmp_test_users t ON t.id=att.user_id;
DELETE ans FROM attempt_answer ans JOIN assessment_attempt att ON att.id=ans.attempt_id JOIN tmp_test_users t ON t.id=att.user_id;
DELETE att FROM assessment_attempt att JOIN tmp_test_users t ON t.id=att.user_id;
DELETE aq FROM assessment_question aq JOIN assessment a ON a.id=aq.assessment_id JOIN tmp_test_users t ON t.id=a.owner_user_id;
DELETE a FROM assessment a JOIN tmp_test_users t ON t.id=a.owner_user_id;
DELETE qkp FROM question_knowledge_point qkp JOIN question_version qv ON qv.id=qkp.question_version_id JOIN question q ON q.id=qv.question_id JOIN tmp_test_users t ON t.id=q.owner_user_id;
DELETE qv FROM question_version qv JOIN question q ON q.id=qv.question_id JOIN tmp_test_users t ON t.id=q.owner_user_id;
DELETE q FROM question q JOIN tmp_test_users t ON t.id=q.owner_user_id;

DELETE ddt FROM document_deletion_token ddt JOIN tmp_test_users t ON t.id=ddt.user_id;
DELETE dj FROM document_job dj JOIN document_version dv ON dv.id=dj.document_version_id JOIN knowledge_document kd ON kd.id=dv.document_id JOIN tmp_test_users t ON t.id=kd.owner_user_id;
DELETE kc FROM knowledge_chunk kc JOIN document_version dv ON dv.id=kc.document_version_id JOIN knowledge_document kd ON kd.id=dv.document_id JOIN tmp_test_users t ON t.id=kd.owner_user_id;
DELETE dv FROM document_version dv JOIN knowledge_document kd ON kd.id=dv.document_id JOIN tmp_test_users t ON t.id=kd.owner_user_id;
DELETE kd FROM knowledge_document kd JOIN tmp_test_users t ON t.id=kd.owner_user_id;
DELETE rc FROM resource_category rc JOIN knowledge_space ks ON ks.id=rc.space_id JOIN tmp_test_users t ON t.id=ks.user_id;
DELETE ks FROM knowledge_space ks JOIN tmp_test_users t ON t.id=ks.user_id;
DELETE so FROM stored_object so JOIN tmp_test_users t ON t.id=so.owner_user_id;

DELETE grd FROM goal_recommendation_batch grd JOIN tmp_test_users t ON t.id=grd.user_id;
DELETE pgj FROM profile_generation_job pgj JOIN tmp_test_users t ON t.id=pgj.user_id;
DELETE updir FROM user_profile_direction updir JOIN user_profile up ON up.id=updir.profile_id JOIN tmp_test_users t ON t.id=up.user_id;
DELETE pv FROM profile_version pv JOIN user_profile up ON up.id=pv.profile_id JOIN tmp_test_users t ON t.id=up.user_id;
DELETE up FROM user_profile up JOIN tmp_test_users t ON t.id=up.user_id;
DELETE pim FROM profile_interview_message pim JOIN tmp_test_users t ON t.id=pim.user_id;
DELETE pis FROM profile_interview_session pis JOIN tmp_test_users t ON t.id=pis.user_id;

DELETE ar FROM availability_rule ar JOIN tmp_test_users t ON t.id=ar.user_id;
DELETE ae FROM availability_exception ae JOIN tmp_test_users t ON t.id=ae.user_id;
DELETE lp FROM learning_preference lp JOIN tmp_test_users t ON t.id=lp.user_id;
DELETE np FROM notification_preference np JOIN tmp_test_users t ON t.id=np.user_id;
DELETE n FROM notification n JOIN tmp_test_users t ON t.id=n.user_id;
DELETE dss FROM daily_study_stat dss JOIN tmp_test_users t ON t.id=dss.user_id;
DELETE km FROM knowledge_mastery km JOIN tmp_test_users t ON t.id=km.user_id;
DELETE me FROM mastery_evidence me JOIN tmp_test_users t ON t.id=me.user_id;
DELETE ms FROM mastery_snapshot ms JOIN tmp_test_users t ON t.id=ms.user_id;
DELETE sa FROM self_assessment sa JOIN tmp_test_users t ON t.id=sa.user_id;
DELETE wq FROM wrong_question wq JOIN tmp_test_users t ON t.id=wq.user_id;
DELETE sr FROM study_report sr JOIN tmp_test_users t ON t.id=sr.user_id;
DELETE ir FROM idempotency_record ir JOIN tmp_test_users t ON t.id=ir.user_id;
DELETE mr FROM model_run mr JOIN tmp_test_users t ON t.id=mr.user_id;
DELETE al FROM audit_log al JOIN tmp_test_users t ON t.id=al.operator_id;

DELETE rt FROM refresh_token rt JOIN tmp_test_users t ON t.id=rt.user_id;
DELETE ur FROM sys_user_role ur JOIN tmp_test_users t ON t.id=ur.user_id;
DELETE u FROM sys_user u JOIN tmp_test_users t ON t.id=u.id;
DELETE r FROM dev_test_account_registry r JOIN tmp_test_users t ON t.id=r.user_id;

COMMIT;
SELECT ROW_COUNT() AS registry_rows_removed;
"@
}

function Remove-TestAccounts {
    Initialize-Registry
    if (-not $All -and [string]::IsNullOrWhiteSpace($Batch)) {
        Show-TestAccounts
        $script:Batch = Read-Host 'Batch name to delete'
        if ([string]::IsNullOrWhiteSpace($Batch)) {
            throw 'A batch name is required unless -All is used.'
        }
    }
    if (-not $All -and $Batch -notmatch '^[A-Za-z0-9_.-]{1,64}$') {
        throw 'Batch may contain only letters, digits, underscore, dot, and hyphen.'
    }

    $label = if ($All) { 'ALL registered test account batches' } else { "batch '$Batch'" }
    if (-not $Force) {
        $confirmation = Read-Host "Delete $label and all related test data? Type DELETE"
        if ($confirmation -cne 'DELETE') {
            Write-Host 'Canceled.' -ForegroundColor Yellow
            return
        }
    }

    $countWhere = if ($All) {
        "email LIKE '%@test.local'"
    } else {
        "batch_key = '$(Escape-Sql $Batch)' AND email LIKE '%@test.local'"
    }
    $targetCount = [int](Invoke-Database "SELECT COUNT(*) FROM dev_test_account_registry WHERE $countWhere;")
    if ($targetCount -eq 0) {
        Write-Host "No registered test accounts matched $label." -ForegroundColor Yellow
        return
    }

    Invoke-Database (Get-CleanupSql $Batch $All.IsPresent) | Out-Null
    Write-Host "Deleted $targetCount test account(s) from $label." -ForegroundColor Green
}

function Reset-TestAccountPasswords {
    Initialize-Registry
    if ([string]::IsNullOrWhiteSpace($Batch)) {
        Show-TestAccounts
        $script:Batch = Read-Host 'Batch name whose password should be reset'
    }
    if ([string]::IsNullOrWhiteSpace($Batch) -or $Batch -notmatch '^[A-Za-z0-9_.-]{1,64}$') {
        throw 'A valid batch name is required.'
    }

    $plainPassword = Resolve-Password $Password
    $passwordHash = Escape-Sql (Get-PasswordHash $plainPassword)
    $escapedBatch = Escape-Sql $Batch
    $sql = @"
START TRANSACTION;
UPDATE sys_user u
JOIN dev_test_account_registry r ON r.user_id=u.id
SET u.password_hash='$passwordHash',
    u.login_failed_count=0,
    u.locked_until=NULL,
    u.updated_at=CURRENT_TIMESTAMP(6),
    u.updated_by=0,
    u.version=u.version+1
WHERE r.batch_key='$escapedBatch'
  AND r.email LIKE '%@test.local'
  AND u.email=r.email
  AND u.username=r.username
  AND u.email LIKE '%@test.local';
SET @affected = ROW_COUNT();
UPDATE refresh_token rt
JOIN dev_test_account_registry r ON r.user_id=rt.user_id
SET rt.revoked_at=COALESCE(rt.revoked_at,CURRENT_TIMESTAMP(6))
WHERE r.batch_key='$escapedBatch';
COMMIT;
SELECT @affected;
"@
    $affected = [int](Invoke-Database $sql)
    if ($affected -eq 0) {
        throw "No registered test account was found in batch '$Batch'."
    }
    Write-Host "Reset the password for $affected account(s) in batch '$Batch'." -ForegroundColor Green
    Write-Host 'Existing login sessions for this batch were revoked.' -ForegroundColor Yellow
}

function Show-Menu {
    while ($true) {
        Write-Host ''
        Write-Host 'Local Test Account Manager' -ForegroundColor Cyan
        Write-Host '1. Create a batch'
        Write-Host '2. List test accounts'
        Write-Host '3. Delete a batch'
        Write-Host '4. Delete all test batches'
        Write-Host '5. Reset a batch password'
        Write-Host '0. Exit'
        $choice = Read-Host 'Select'
        switch ($choice) {
            '1' {
                $inputCount = Read-Host "Count (default $Count)"
                if ($inputCount) { $script:Count = [int]$inputCount }
                $inputPrefix = Read-Host "Username prefix (default $Prefix)"
                if ($inputPrefix) { $script:Prefix = $inputPrefix }
                $inputBatch = Read-Host 'Batch name (blank = generated automatically)'
                $script:Batch = $inputBatch
                $inputRole = Read-Host "Role STUDENT or ADMIN (default $Role)"
                if ($inputRole) { $script:Role = $inputRole.ToUpperInvariant() }
                $script:Password = ''
                New-TestAccounts
            }
            '2' { Show-TestAccounts }
            '3' {
                $script:Batch = ''
                $script:All = $false
                Remove-TestAccounts
            }
            '4' {
                $script:All = $true
                Remove-TestAccounts
                $script:All = $false
            }
            '5' {
                $script:Batch = ''
                $script:Password = ''
                Reset-TestAccountPasswords
            }
            '0' { return }
            default { Write-Host 'Unknown menu option.' -ForegroundColor Yellow }
        }
    }
}

try {
    switch ($Action) {
        'menu' { Show-Menu }
        'create' { New-TestAccounts }
        'list' { Show-TestAccounts }
        'delete' { Remove-TestAccounts }
        'reset' { Reset-TestAccountPasswords }
    }
} catch {
    $logDirectory = Join-Path $projectRoot 'logs'
    if (-not (Test-Path $logDirectory)) {
        New-Item -ItemType Directory -Path $logDirectory | Out-Null
    }
    $logPath = Join-Path $logDirectory 'test-accounts-last-error.log'
    $details = @(
        "Time: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
        "Action: $Action"
        "Message: $($_.Exception.Message)"
        "Position: $($_.InvocationInfo.PositionMessage)"
        "Stack: $($_.ScriptStackTrace)"
    ) -join [Environment]::NewLine
    Set-Content -LiteralPath $logPath -Value $details -Encoding UTF8
    Write-Host ''
    Write-Host "Test account operation failed: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "Diagnostic log: $logPath" -ForegroundColor Yellow
    exit 1
}
