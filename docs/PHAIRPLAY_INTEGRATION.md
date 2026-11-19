# PhairPlay integrado en Polaroid Lite Home

## Crédito y licencia

El receptor integrado parte del proyecto [PhairPlay](https://github.com/mazer666/PhairPlay), creado por mazer666 y colaboradores. El código importado corresponde a la base `1.0.0-alpha.8-polaroid-api24` (snapshot upstream `e091278`). El proyecto upstream se distribuye bajo Apache License 2.0; la copia de la licencia está en [`../phairplay/LICENSE`](../phairplay/LICENSE).

Lite Home incorpora PhairPlay y adapta su pantalla y su ejecución al launcher Polaroid. Las modificaciones de integración de este repositorio incluyen el módulo Android `:phairplay`, la sección Cast dentro de Lite Home, el arranque del servicio del receptor y compatibilidad con Android 7/API 24. Conserva el crédito y la licencia del proyecto fuente al redistribuir este código.

## Cómo está integrado

- `:app` contiene el launcher y es el único paquete instalable: `tv.polaroid.litehome`.
- `:phairplay` es una biblioteca Android que contiene la recepción AirPlay y DLNA, el servicio foreground, ajustes y pantallas del receptor.
- `settings.gradle` incluye ambos módulos y `app/build.gradle` depende de `project(':phairplay')`.
- El botón Cast abre la sección PhairPlay dentro de la actividad de Lite Home. No inicia una segunda app para mostrar la interfaz.
- `BootReceiver` inicia el servicio del receptor al arrancar Android. Lite Home presenta el estado y los controles desde su sección PhairPlay.

Para abrir el launcher manualmente en la TV:

```sh
adb connect <IP_DE_LA_TV>:5555
adb shell am start -n tv.polaroid.litehome/.HomeActivity
```

Desde Inicio, selecciona el botón Cast para entrar a PhairPlay. En Receptor se ve el estado y en Ajustes se configuran los protocolos y preferencias.

Cuando se detiene una transmisión que PhairPlay abrió sobre Lite Home, regresa a Lite Home con la sección PhairPlay seleccionada y el aside en Receptor. Funciona tanto si el launcher seguía vivo en segundo plano como si Android debe crear de nuevo su actividad.

## Compatibilidad de referencia

La integración se compiló e instaló en la Polaroid de referencia:

| Dato | Valor |
|---|---|
| Lite Home | 3.24 |
| Android | 7.0 / API 24 |
| Plataforma | MediaTek MT5863 / CVTE |
| Mainboard | TP.MT5510I.PB801 |
| Firmware | `20180926_104833` |
| Panel | 1920 × 1080 |

En esta TV se confirmó video AirPlay desde iPhone y el retorno al launcher al detener la transmisión. La corrección de la negociación de audio RAOP se compiló e instaló; la reproducción de audio desde Fedora queda pendiente de prueba en vivo.

## Compilar

Instala JDK 17 y estos componentes del Android SDK:

- Android SDK Platform 35
- Android Build Tools 35.0.0 o compatible
- Android NDK `28.2.13676358`
- CMake `3.22.1`

Desde la raíz del repositorio, compila Lite Home y el módulo PhairPlay en un APK:

```sh
./gradlew :app:assembleDebug
```

Gradle wrapper 8.7 queda fijado en `gradle/wrapper/gradle-wrapper.properties`. El APK sale en:

```text
app/build/outputs/apk/debug/app-debug.apk
```

El módulo `:phairplay` compila como dependencia de la tarea; no hace falta compilar ni instalar un APK independiente.

## Instalar o actualizar por ADB

Activa ADB por red en la TV, conecta el equipo a la misma red y usa:

```sh
adb connect <IP_DE_LA_TV>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n tv.polaroid.litehome/.HomeActivity
```

La opción `-r` actualiza el paquete y conserva sus datos. Android exige que el APK nuevo tenga la misma firma que el instalado. Si aparece `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, compila con la misma clave local usada para la versión instalada; no desinstales primero si necesitas conservar sus datos. Mantén los keystores y contraseñas fuera de Git.

## Audio AirPlay desde Fedora

El transporte clásico RAOP comunica tres puertos al emisor. El `SETUP` integrado responde con `server_port=6000`, `control_port=6001` y `timing_port=6002`; el receptor RTP de audio escucha en UDP 6000. Esta respuesta permite a PipeWire continuar después de `SETUP` y enviar `RECORD`. La confirmación final requiere reproducir audio mientras la TV está conectada a la red.

