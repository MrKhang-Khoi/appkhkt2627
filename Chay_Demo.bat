@echo off
title CVA-SmartGuardian Demo Runner
echo Dang khoi dong may chu Demo CVA-SmartGuardian...
start "" "http://localhost:8100/src/parent-dashboard/index.html"
node server.js
pause
