@echo off
rem Run the instrumented suite on Firebase Test Lab (orchestrator + clearPackageData=true).
rem   testlab\run_test_lab.bat               rebuild both APKs, then run on testlab\devices.txt
rem   testlab\run_test_lab.bat --skip-build  run the APKs already in app\build\outputs
rem   testlab\run_test_lab.bat --models      list the devices the project can use
rem   testlab\run_test_lab.bat --targets "class com.example.smartuse_frontend.e2e.PermissionsTest"   one class (repeatable)
python "%~dp0run_test_lab.py" %*
