@echo off
rem Full compatibility run: the whole suite on API 33, 34, 35 and 36 (testlab\devices-compat.txt).
rem Uses four device runs of the daily Test Lab quota. Extra arguments pass through, e.g. --skip-build.
python "%~dp0run_test_lab.py" --devices "%~dp0devices-compat.txt" %*
