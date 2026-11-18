# Polaroid Lite Home

Launcher nativo y ligero para televisores Android. Usa vistas propias de Android y navegación con control remoto. Incluye un control local para iPhone Safari, protegido por PIN, y un servicio opcional de accesibilidad para usarlo en otras apps. No usa WebView ni contacta servicios externos.

Versión 3.24: PhairPlay está integrado como módulo del launcher. El botón Cast junto al control remoto cambia el contenido central a la sección PhairPlay, dentro de Lite Home: conserva el encabezado, el reloj y el estilo visual del launcher. El panel organiza Receptor y Ajustes en un aside animado; sus etiquetas usan tonos neutros y el foco respeta el mismo radio que el botón. El servicio continúa en segundo plano. La pestaña “Apps” ahora se llama “Aplicaciones”.

La integración se basa en [PhairPlay](https://github.com/mazer666/PhairPlay), proyecto de código abierto de mazer666 y sus colaboradores, bajo Apache 2.0. Lite Home lo integra como módulo Android `:phairplay` y adapta la navegación para el launcher y Android 7/API 24 de esta Polaroid. Se conserva la licencia original en [`phairplay/LICENSE`](phairplay/LICENSE). La arquitectura, puesta en marcha y compilación están en [`docs/PHAIRPLAY_INTEGRATION.md`](docs/PHAIRPLAY_INTEGRATION.md).

Fuentes incluye HDMI 1–3, VGA, TV, AV y YPbPr con los IDs de hardware CVTE/MediaTek correspondientes. Ajustes del sistema se abre desde el botón superior y no aparece como app; Multimedia se fuerza en el catálogo de Sistema si el firmware no lo publica como lanzable.

## Arquitectura

La app separa la interfaz (`HomeActivity`) del acceso al sistema (`data`). `PackageManagerAppsRepository` obtiene apps instaladas; `AndroidTvInputRepository` consulta las entradas que expone el firmware. PhairPlay vive en el módulo `:phairplay`, que aísla protocolos, servicio, ajustes y decodificadores; `:app` integra esa pantalla y servicio en el único paquete `tv.polaroid.litehome`. Así se mantienen los límites de responsabilidades sin crear una segunda app instalable. Se usan vistas nativas compatibles con API 24.

## Compatibilidad probada

| Dato | Equipo probado |
|---|---|
| Marca | Polaroid |
| Android | Android 7.0 Nougat, API 24 |
| Plataforma | MediaTek MT5863 / CVTE |
| Placa | TP.MT5510I.PB801 |
| Firmware reportado | `20180926_104833` |

Prueba en equipo real: Lite Home 3.24 se compiló e instaló en la Polaroid por ADB. Se confirmó la transmisión de video desde iPhone y el retorno al Inicio al detenerla. La corrección reciente de audio RAOP quedó instalada; la prueba de sonido desde Fedora aún está pendiente. **Polaroid Lite Remote** aparece enlazado en `dumpsys accessibility` después de escribir el ajuste y reiniciar la TV. Los APK de Navegador, Chrome y App List antes de desinstalarlos están guardados en `outputs/AppBackups/2026-11-16/`; los hashes y comandos de restauración están junto a ellos. El servicio global se mantiene disponible al salir del launcher cuando Accesibilidad está activada; Android puede detener cualquier app si se fuerza su cierre o se desactiva ese servicio.

En este firmware, la actividad estándar `android.settings.ACCESSIBILITY_SETTINGS` no existe y los Ajustes CVTE no incluyen Accesibilidad. Se puede probar la habilitación del servicio por ADB:

```sh
adb shell settings put secure enabled_accessibility_services tv.polaroid.litehome/.RemoteAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell dumpsys accessibility
```

En esta TV, los ajustes se guardaron pero el servicio no se enlazó hasta reiniciar Android. Después del reinicio, `dumpsys accessibility` mostró `Service[label=Polaroid Lite Remote, ...]`. La opción “Accesibilidad” del menú abre directamente los Ajustes CVTE, ya que este firmware no incluye una pantalla de accesibilidad.

## Funciones

- Muestra las apps que exponen una actividad de inicio Android TV o Android.
- Abre apps con OK y permite navegar con las flechas del control.
- Mantiene el launcher original instalado; no desactiva ni elimina paquetes del sistema.
- Inicio con navegación superior, portada, apps instaladas, espacio vacío para “Continúa viendo” y accesos a entradas de TV.
- Pantallas Apps y Fuentes; Apps oculta paquetes del sistema salvo Descargas y Multimedia. Fuentes presenta HDMI 1–3, VGA, TV, AV y YPbPr en horizontal.
- Animaciones nativas breves en cambios de pantalla y foco del control.
- Botón Cast junto al control remoto que muestra **PhairPlay** en el área central de Lite Home, sin abrir otra Activity ni reemplazar el encabezado y reloj. El contenido mantiene la paleta y el foco de navegación del launcher, con un aside animado para Receptor y Ajustes.
- PhairPlay se inicia automáticamente al arrancar la TV y mantiene un servicio foreground escuchando mientras se usan otras apps. La notificación conserva los controles para detener o reiniciar el receptor.
- Control desde iPhone Safari: con la TV y el iPhone en la misma Wi-Fi, abre en Safari la dirección y escribe el PIN que aparecen en el pie de la pantalla. El mando incluye flechas, OK, Atrás, Inicio, volumen y controles multimedia. Una vez activado Accesibilidad, el servidor sigue disponible en otras apps.
- Acciones globales Inicio y Atrás. Flechas y OK usan la jerarquía accesible de la app visible; OK intenta un toque por coordenadas si la app no ejecuta el clic accesible.
- Si Android enlaza el servicio de accesibilidad, lo mantiene al cambiar de app y lo vuelve a iniciar mientras permanezca habilitado. Lee localmente el árbol interactivo de la ventana actual para mover el foco y ejecutar clics; no envía el contenido a la red.
- El PIN cambia después de cada emparejamiento correcto; cinco intentos incorrectos bloquean emparejamientos durante 30 segundos. Tras emparejar, el token del iPhone se conserva en Safari y en los datos privados de la TV para reanudar la sesión aunque cambie el PIN. **Desconectar** revoca ese token. El servicio global queda disponible mientras Accesibilidad esté activada.
- Mantiene la interfaz liviana para el hardware de 1 GB de RAM; no usa WebView para la TV.

## Instalar en la TV

Con ADB conectado a la tele:

```sh
adb install -r release/PolaroidLiteHome.apk
adb shell am start -n tv.polaroid.litehome/.HomeActivity
```

Para comprobar el estado del control global, consulta `adb shell dumpsys accessibility`; debe aparecer `Polaroid Lite Remote` como servicio enlazado. Si no aparece después de guardar los ajustes seguros, reinicia la TV con `adb reboot` y vuelve a conectarla por ADB. El botón vuelve a recibir el foco al regresar al launcher; al regresar, usa la dirección y el PIN que muestra la TV. Para convertir Polaroid Lite Home en inicio predeterminado usa `adb shell pm set-home-activity tv.polaroid.litehome/.HomeActivity` o elige **SIEMPRE** en el selector HOME; para restaurar el inicio original, selecciona su actividad HOME. La sesión del iPhone anterior puede requerir emparejarse otra vez.

## Compilar

Requiere JDK 17, Android SDK Platform 35, Android NDK `28.2.13676358` y CMake `3.22.1`. El wrapper incluido descarga/usa Gradle 8.7.

```sh
./gradlew :app:assembleDebug
```

El APK se genera en `app/build/outputs/apk/debug/app-debug.apk`. Con ADB habilitado y la TV en la misma red, instálalo y abre el launcher:

```sh
adb connect <IP_DE_LA_TV>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n tv.polaroid.litehome/.HomeActivity
```

Android solo permite actualizar una instalación si ambas versiones tienen la misma firma. Para actualizar la app ya instalada, compila y firma con la misma clave local que se usó para instalarla; no subas keystores ni contraseñas al repositorio. La guía completa está en [`docs/PHAIRPLAY_INTEGRATION.md`](docs/PHAIRPLAY_INTEGRATION.md).
