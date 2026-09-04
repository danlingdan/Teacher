; Migrate pre-release current-user NSIS installations before the per-machine 3.0 install.
; Stable JavaFX/WiX installations are handled by Tauri's built-in WiX migration.
!macro SQLTEACHER_REMOVE_CURRENT_USER productName
  ReadRegStr $R7 HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${productName}" "DisplayName"
  ReadRegStr $R8 HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${productName}" "Publisher"
  ReadRegStr $R9 HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${productName}" "UninstallString"

  ${If} $R7 == "${productName}"
  ${AndIf} $R9 != ""
    ${If} $R8 == "sqlteacher"
    ${OrIf} $R8 == "SQLTeacher Project"
      DetailPrint "Removing the previous current-user SQLTeacher installation..."
      ClearErrors
      ExecWait '$R9 /S' $R6
      ${If} ${Errors}
      ${OrIf} $R6 <> 0
        MessageBox MB_ICONSTOP|MB_OK "The previous SQLTeacher installation could not be removed. Setup will stop to avoid leaving two versions installed."
        Abort
      ${EndIf}

      ReadRegStr $R7 HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${productName}" "UninstallString"
      ${If} $R7 != ""
        MessageBox MB_ICONSTOP|MB_OK "The previous SQLTeacher installation is still registered. Setup will stop to protect your user data."
        Abort
      ${EndIf}
    ${EndIf}
  ${EndIf}
!macroend

!macro NSIS_HOOK_PREINSTALL
  !insertmacro SQLTEACHER_REMOVE_CURRENT_USER "SQLTeacher 3 Alpha"
  !insertmacro SQLTEACHER_REMOVE_CURRENT_USER "SQLTeacher 3 Beta"
  !insertmacro SQLTEACHER_REMOVE_CURRENT_USER "SQLTeacher"
!macroend
