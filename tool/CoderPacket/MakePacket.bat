@echo off
title L1JTW 簡易打包工具
cls

REM --------------------------------------------------------------------------------------------
REM 請設定以下的資料庫設定值,並使用打包工具將資料庫先行打包完成.
REM --------------------------------------------------------------------------------------------
REM Language=tw：選擇對應語系en/cn/tw 
REM Selected=1:(全打包) 2:(僅含 src & DB & tool & data)
REM 7zDir= 7z.exe 絕對路徑
REM --------------------------------------------------------------------------------------------
set Language=tw
set Selected=2
set Dir7z="E:\Program Files\7-Zip\7z.exe"
goto Base

Rem --------------------------------------------------------------------------------------------
Rem -   基本執行
Rem --------------------------------------------------------------------------------------------
:Base
copy .\workplace\xer.bat .\workplace\var.bat
..\subversion\svnversion.exe>>.\workplace\var.bat
call .\workplace\var.bat
del .\workplace\var.bat
cls

goto %Language%


Rem --------------------------------------------------------------------------------------------
Rem -  主系統選單
Rem --------------------------------------------------------------------------------------------
:Start
echo %Language_Line11%
echo %Language_Line12%
echo.
echo %Language_choose1%
echo %Language_choose2%
echo %Language_choose9%
echo.
set /p Selected="%Language_Action%"
goto Select%Selected%


Rem --------------------------------------------------------------------------------------------
Rem -   語言模組
Rem --------------------------------------------------------------------------------------------
:tw
set Language_Line11= 版本編號: %Var%
set Language_Line12= 解壓縮軟體7z 目錄: %Dir7z%
set Language_choose1= 》1. 完整包       [L1J-TW_ver.%Var%_FP.7z]
set Language_choose2= 》2. Src+DB標準包 [L1J-TW_ver.%Var%_NP.7z]
set Language_choose9= 》9. 離開
set Language_Action=選擇：
goto Start

:cn
set Language_Line11= 版本�C�A: %Var%
set Language_Line12= 解���D�動^7z 目��: %Dir7z%
set Language_choose1= 》1. 完整包       [L1J-TW_ver.%Var%_FP.7z]
set Language_choose2= 》2. �嵾膆]       [L1J-TW_ver.%Var%_NP.7z]
set Language_choose9= 》9. 离�{
set Language_Action=�u�寣G
goto Start

:en
set Language_Line11= Version is: %Var%
set Language_Line12= 7z install: %Dir7z%
set Language_choose1= 》1. FullPack     [L1J-TW_ver.%Var%_FP.7z]
set Language_choose2= 》2. normalPack   [L1J-TW_ver.%Var%_NP.7z]
set Language_choose9= 》9. Exit
set Language_Action=Actions：
goto Start

Rem --------------------------------------------------------------------------------------------
REM -   動作定義
Rem --------------------------------------------------------------------------------------------
:Select1
title Building File about L1J-TW_ver.%Var%_FP.7z
@%Dir7z% a -tzip ..\..\..\L1J-TW_ver.%Var%_FP.7z ..\..\* -r -x@Fullpack\Exclusion.lst -mx=9
goto exit

:Select2
title Building File about  L1J-TW_ver.%Var%_NP.7z
@%Dir7z% a -tzip ..\..\..\L1J-TW_ver.%Var%_NP.7z -r @normalpack\Pack.lst -x@normalpack\Exclusion.lst -mx=9
goto exit

:Select9
cls
exit
