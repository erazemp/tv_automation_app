@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stream_session.ps1" %*
