@echo off
cd /d D:\document\openSource\TS6_Droid_CN

echo === Checking current branch ===
git branch

echo.
echo === Staging changes ===
git add app/src/main/kotlin/dev/tsdroid/viewmodel/ServerViewModel.kt

echo.
echo === Committing to main ===
git commit -m "fix: Fix Unresolved reference 'eventLoop' in ServerViewModel" -m "Replace service.tsClient.eventLoop() with service.tsClient.startEventLoop()

The TsClient class only has startEventLoop() method, not eventLoop().
Removed unnecessary viewModelScope.launch wrapper.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"

echo.
echo === Last commit ===
git log -1 --oneline

echo.
echo === Pushing to remote main ===
git push origin main

echo.
echo === SUCCESS ===
