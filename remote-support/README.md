# RemoteAssist

RemoteAssist se compone de tres partes:

- **Aplicación Android (host):** comparte la pantalla del teléfono y, si el usuario activa el Servicio de Accesibilidad, recibe controles de toque, desplazamiento y navegación.
- **Visor de escritorio (Windows):** muestra la pantalla compartida y envía los controles.
- **Servidor de señalización:** empareja ambos dispositivos mediante un código de seis dígitos.

El video y los controles viajan por una conexión WebRTC entre los dispositivos. El servidor de señalización no retransmite el video.

## Antes de usarlo

Configura la URL pública que Render asignó a tu servidor. El proyecto trae como ejemplo `wss://remoteassist.onrender.com/ws`; reemplázala si tu servicio tiene otro dominio. Los clientes Android y Windows deben conectarse al mismo servidor.

Para funcionar en distintas redes, configura también un servicio TURN. El código incluye STUN para conexiones sencillas, pero STUN por sí solo no funciona en todas las redes. Usa credenciales TURN temporales si tu proveedor las ofrece: las credenciales que se compilan dentro de una aplicación pueden extraerse del APK.

## Desplegar el servidor en Render

El archivo `render.yaml` configura un servicio web llamado `remoteassist`. Después del despliegue, usa la URL HTTPS que Render asigne, cambiando `https://` por `wss://` y agregando `/ws`. Por ejemplo: `wss://remoteassist.onrender.com/ws`.

Render debe aceptar las actualizaciones de conexión WebSocket y terminar TLS. El servidor Node escucha el puerto indicado por la variable `PORT` y ofrece una respuesta de estado en `/`.

Cada sala vence diez minutos después de crear el código. El servidor limita por dirección de origen los intentos de conexión. Puedes cambiar los valores predeterminados con estas variables del servicio:

| Variable | Valor predeterminado | Uso |
| --- | ---: | --- |
| `ROOM_TTL_MS` | `600000` | Duración de una sesión, en milisegundos. |
| `MAX_ROOMS` | `5000` | Máximo de salas simultáneas. |
| `MAX_JOIN_ATTEMPTS` | `60` | Intentos de conexión por dirección durante diez minutos. |

## Ejecutar el servidor localmente

Desde una terminal de PowerShell:

```powershell
cd .\server
npm install
npm start
```

El servidor local escucha en el puerto `8080`, salvo que definas `PORT`.

## Visor de Windows

### Ejecutarlo desde el código

```powershell
cd .\viewer-desktop
npm install
$env:SIGNALING_URL = 'wss://tu-servidor.onrender.com/ws'
$env:TURN_URL = 'turn:tu-servidor-turn.example:3478'
$env:TURN_USERNAME = 'usuario-temporal'
$env:TURN_CREDENTIAL = 'credencial-temporal'
npm start
```

Si no defines variables TURN, el visor utiliza STUN. Para probar un servidor local, usa `$env:SIGNALING_URL = 'ws://localhost:8080/ws'`.

### Generar el instalador `.exe`

```powershell
cd .\viewer-desktop
npm install
npm run dist
```

El instalador NSIS de 64 bits se crea en `viewer-desktop/dist`. El instalador generado por este proyecto no tiene firma digital; Windows puede mostrar un aviso de editor desconocido. Para distribuirlo públicamente, firma el ejecutable con un certificado de firma de código.

## Aplicación Android y generación del APK

La aplicación requiere Android Studio, JDK 17, Android SDK Platform 34 y Android Build Tools 34.0.0. Acepta las licencias del Android SDK desde Android Studio o con `sdkmanager --licenses` antes de compilar. Android explica la instalación y las licencias en la [guía de Android SDK](https://developer.android.com/tools/sdkmanager).

El proyecto fija Gradle 8.7 en `host-android/gradle/wrapper/gradle-wrapper.properties`, pero el ZIP original no incluye los scripts ni el archivo JAR del Gradle Wrapper. Por eso, para compilar desde una terminal necesitas tener Gradle 8.7 instalado. También puedes configurar en Android Studio una instalación local de Gradle 8.7.

### Configurar la URL y TURN

Android lee estos valores durante la compilación. Puedes definirlos como variables de entorno antes de abrir Android Studio desde esa terminal, o pasarlas como opciones al comando Gradle:

```powershell
$env:SIGNALING_URL = 'wss://tu-servidor.onrender.com/ws'
$env:TURN_URL = 'turn:tu-servidor-turn.example:3478'
$env:TURN_USERNAME = 'usuario-temporal'
$env:TURN_CREDENTIAL = 'credencial-temporal'
```

También puedes pasarlos como propiedades Gradle. No guardes credenciales privadas en un archivo que vayas a subir al repositorio.

### Crear un APK de depuración

Desde una terminal, con Gradle 8.7 instalado:

```powershell
cd .\host-android
gradle :app:assembleDebug
```

El APK se genera en `host-android/app/build/outputs/apk/debug/app-debug.apk`.

También puedes abrir `host-android` en Android Studio, sincronizar Gradle y elegir **Build → Build Bundle(s) / APK(s) → Build APK(s)**. Si Android Studio no encuentra Gradle, configura la distribución local de Gradle 8.7 en **Settings → Build, Execution, Deployment → Build Tools → Gradle**.

El APK de depuración sirve para instalar y probar la aplicación. Para distribuir una versión final, elige la variante `release` y genera un APK firmado desde **Build → Generate Signed Bundle / APK**. Conserva el almacén de claves y sus contraseñas: se necesitan para publicar actualizaciones con la misma identidad.

### Probar en una red local

En un Android **debug**, el manifiesto permite tráfico sin cifrar para pruebas locales:

- En un emulador de Android, puedes usar `ws://10.0.2.2:8080/ws` para llegar al puerto 8080 de la PC.
- En un teléfono físico, usa la dirección LAN de la PC, por ejemplo `ws://192.168.1.20:8080/ws`, y asegúrate de que ambos dispositivos estén en la misma red.

Las compilaciones `release` exigen `wss://`. No uses una dirección `ws://` en un APK de producción.

## Usarla fuera de casa

El servidor local en `localhost` solo sirve dentro de la computadora donde corre. Para conectar dispositivos desde redes distintas:

1. Sube el proyecto a un repositorio privado de GitHub.
2. En Render, crea un **Blueprint** conectado a ese repositorio; Render usará `render.yaml` para desplegar el servidor.
3. Copia el dominio que Render asigne y configura `SIGNALING_URL` con `wss://<dominio-asignado>/ws` al ejecutar o empaquetar el visor y al compilar el APK.
4. Configura TURN para redes que no permitan una conexión WebRTC directa. Usa el mismo servidor TURN y credenciales temporales en ambos clientes.
5. Mantén el teléfono y la PC conectados a internet. En cada sesión, alguien debe iniciar el uso compartido en el teléfono, aceptar el permiso de Android y compartir el código con el visor.

No publiques el repositorio si contiene credenciales. No incluyas credenciales TURN permanentes en el APK. Sin un servidor público desplegado, los clientes solo podrán comunicarse en la red local; sin TURN, las conexiones desde ciertas redes pueden fallar.

## Iniciar una sesión

1. Abre el host Android y pulsa **Iniciar sesión de soporte**.
2. Acepta el permiso del sistema para compartir la pantalla y comparte el código de seis dígitos con la persona que te ayudará.
3. En el visor, escribe el código y pulsa **Conectar**.
4. Para permitir toques y navegación remotos, activa el Servicio de Accesibilidad de RemoteAssist en los ajustes de Android. Es un permiso sensible: habilítalo solo si necesitas esos controles.
5. Para terminar la sesión, pulsa **Detener** en la notificación persistente de Android. Android solicita autorización para compartir la pantalla en cada sesión.

## Estructura del proyecto

```text
remote-support/
├── host-android/      Aplicación Android y captura WebRTC
├── server/            Servidor WebSocket de señalización
├── viewer-desktop/    Visor Electron y configuración del instalador Windows
├── render.yaml        Configuración de despliegue en Render
└── README.md          Esta guía
```
