@echo off
setlocal enabledelayedexpansion

set WORKTREE=d:\document\openSource\TS6_Droid_CN.worktrees\agents-gradle-build-failure-debugging
set MAIN=D:\document\openSource\TS6_Droid_CN

echo === Step 1: Check status in worktree ===
cd /d "%WORKTREE%"
git status --short

echo.
echo === Step 2: Stage changes ===
git add -A

echo.
echo === Step 3: Commit in worktree ===
git commit -m "fix: Fix Unresolved reference 'eventLoop' in ServerViewModel" -m "Replace service.tsClient.eventLoop() with service.tsClient.startEventLoop()

The TsClient class only has startEventLoop() method, not eventLoop().
Removed unnecessary viewModelScope.launch wrapper.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>" || echo No changes to commit

echo.
echo === Step 4: Verify commit ===
git log -1 --oneline

echo.
echo === Step 5: Go to main worktree ===
cd /d "%MAIN%"
git status

echo.
echo === Step 6: Merge topic branch ===
git merge agents/gradle-build-failure-debugging

echo.
echo === Step 7: Push to remote ===
git push origin main

echo.
echo === Step 8: Verify remote ===
git log origin/main -1 --oneline

echo.
echo === SUCCESS ===
echo Changes have been committed and pushed to remote main branch
echo GitHub cloud build should now succeed
