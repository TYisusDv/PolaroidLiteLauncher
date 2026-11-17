# Polaroid Lite Home

Launcher nativo y ligero para televisores Android. Usa vistas propias de Android y navegación con control remoto. Incluye un control local para iPhone Safari, protegido por PIN, y un servicio opcional de accesibilidad para usarlo en otras apps. No usa WebView ni contacta servicios externos.

Versión 3.8: el tab Apps separa “Mis apps” y “Sistema”. Descargas, Multimedia y TV Master quedan en Sistema y no aparecen en Inicio. El switch de Control global activa o desactiva directamente el servicio de Accesibilidad después de conceder una vez `WRITE_SECURE_SETTINGS` por ADB. Descargas se agrega como acceso directo porque este firmware no anuncia su pantalla en las categorías normales del launcher.

## Arquitectura

La app separa la interfaz (`HomeActivity`) del acceso al sistema (`data`). `PackageManagerAppsRepository` obtiene apps instaladas; `AndroidTvInputRepository` consulta las entradas que expone el firmware. Así se pueden reemplazar fuentes sin mezclar consultas de sistema con la construcción de vistas. Sigue la guía oficial de Android: capas UI y datos con repositorios, y deja fuera una capa de dominio porque el launcher aún no tiene lógica compartida que la justifique. No se añadieron módulos ni dependencias que eleven el consumo en esta TV de 1 GB. Se usan vistas nativas compatibles con API 24.

## Compatibilidad probada

| Dato | Equipo probado |
|---|---|
| Marca | Polaroid |
| Android | Android 7.0 Nougat, API 24 |
| Plataforma | MediaTek MT5863 / CVTE |
| Placa | TP.MT5510I.PB801 |
| Firmware reportado | `20180926_104833` |

Prueba en equipo real: Lite Home 3.3 se compiló, instaló y abrió por ADB por primera vez en esta TV. Las versiones 3.4–3.6 se compilaron e instalaron; en 3.5 se comprobó que la web del control responde en HTTP 200 tras abrir el launcher. Los APK de Navegador, Chrome y App List antes de desinstalarlos están guardados en `outputs/AppBackups/2026-11-16/`; los hashes y comandos de restauración están junto a ellos. El servicio global se mantiene disponible al salir del launcher cuando Accesibilidad está activada; Android puede detener cualquier app si se fuerza su cierre o se desactiva ese servicio.

Para habilitar el control directo del switch una sola vez después de instalar la 3.8, conecta la TV por ADB y ejecuta `adb shell pm grant tv.polaroid.litehome android.permission.WRITE_SECURE_SETTINGS`. Si la TV cambia de IP, usa primero `adb connect IP_DE_LA_TV:5555`.

## Funciones

- Muestra las apps que exponen una actividad de inicio Android TV o Android.
- Abre apps con OK y permite navegar con las flechas del control.
- Mantiene el launcher original instalado; no desactiva ni elimina paquetes del sistema.
- Inicio con navegación superior, portada, apps instaladas, espacio vacío para “Continúa viendo” y accesos a entradas de TV.
- Pantallas Apps y Fuentes; Apps oculta paquetes del sistema salvo Descargas y Multimedia. Fuentes presenta HDMI 1–3, AV y TV en horizontal.
- Animaciones nativas breves en cambios de pantalla y foco del control.
- Control desde iPhone Safari: con la TV y el iPhone en la misma Wi-Fi, abre en Safari la dirección y escribe el PIN que aparecen en el pie de la pantalla. El mando incluye flechas, OK, Atrás, Inicio, volumen y controles multimedia. Una vez activado Accesibilidad, el servidor sigue disponible en otras apps.
- Acciones globales Inicio y Atrás. Flechas y OK usan la jerarquía accesible de la app visible; OK intenta un toque por coordenadas si la app no ejecuta el clic accesible.
- El servicio de accesibilidad debe activarse manualmente en Ajustes > Accesibilidad > Polaroid Lite Remote. Android lo mantiene al cambiar de app y lo vuelve a iniciar mientras permanezca habilitado. Lee localmente el árbol interactivo de la ventana actual para mover el foco y ejecutar clics; no envía el contenido a la red.
- El PIN se genera al iniciar cada servidor; cinco intentos incorrectos bloquean emparejamientos durante 30 segundos. Tras emparejar, el token del iPhone se conserva en Safari y en los datos privados de la TV para reanudar la sesión tras reinicios o cambios de PIN. **Desconectar** revoca ese token. El servicio global queda disponible mientras Accesibilidad esté activada.
- Mantiene la interfaz liviana para el hardware de 1 GB de RAM; no usa WebView para la TV.

## Instalar en la TV

Con ADB conectado a la tele:

```sh
adb install -r release/PolaroidLiteHome.apk
adb shell am start -n tv.polaroid.litehome/.HomeActivity
```

Para control global, activa **Polaroid Lite Remote** en Ajustes > Accesibilidad desde el botón visible en la barra superior. El botón vuelve a recibir el foco al regresar al launcher; pulsa OK para entrar a Ajustes. Al regresar, usa la dirección y el PIN que muestra la TV. Para convertir Polaroid Lite Home en inicio predeterminado usa `adb shell pm set-home-activity tv.polaroid.litehome/.HomeActivity` o elige **SIEMPRE** en el selector HOME; para restaurar el inicio original, selecciona su actividad HOME. La sesión del iPhone anterior puede requerir emparejarse otra vez.

## Compilar

Requiere JDK 17, Gradle 8.7, Android SDK Platform 35 y Android Build Tools 34.0.0 o compatible.

```sh
gradle assembleDebug
```

El APK de depuración se genera en `app/build/outputs/apk/debug/app-debug.apk`. Para instalarlo:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
