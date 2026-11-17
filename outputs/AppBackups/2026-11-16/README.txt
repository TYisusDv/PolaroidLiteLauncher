Respaldo previo a desinstalar apps de la TV Polaroid.

Paquetes guardados:
- com.android.browser: Navegador (APK de sistema MBrowser3)
- com.android.chrome: Chrome (APK instalado en /data/app)
- com.cvte.tv.appstore: App List / Lista de aplicaciones

Los archivos .apk contienen los instaladores; no incluyen datos privados, sesiones ni contraseñas de cada app.
Los hashes SHA-256 están en SHA256SUMS.txt.

Restaurar los APK de sistema Navegador o App List para el usuario 0:
  adb shell cmd package install-existing --user 0 com.android.browser
  adb shell cmd package install-existing --user 0 com.cvte.tv.appstore

Reinstalar Chrome desde esta carpeta:
  adb install -r com.android.chrome.apk
