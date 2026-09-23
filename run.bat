@echo off
REM 1. Create a virtual environment named "venv"
python -m venv venv

REM 2. Activate the virtual environment
call .\venv\Scripts\activate

REM 3. Install your required packages
pip install -r requirements.txt

REM 4. Run your python script
python karaoke_app.py

pause