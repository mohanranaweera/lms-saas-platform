@echo off
if "%LMS_MCP_POSTGRES_DSN%"=="" (
    echo LMS_MCP_POSTGRES_DSN is not configured. 1>&2
    exit /b 1
)

npx.cmd -y @bytebase/dbhub@latest --transport stdio --dsn "%LMS_MCP_POSTGRES_DSN%"