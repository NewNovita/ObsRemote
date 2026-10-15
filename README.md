# OBS Remote (Android)

App Android nativa para controlar **escenas** y **audio** de OBS Studio desde el teléfono,
por la red local (LAN).

- **Kotlin + Jetpack Compose** (Material 3, colores dinámicos).
- Cliente del protocolo **obs-websocket v5** implementado con **OkHttp** — sin dependencias
  problemáticas, reconexión y decodificación de eventos bajo control propio.
- Solo **escenas** y **audio** (mute + volumen), tal como se pidió.

---

## 1. Preparar OBS (una sola vez)

1. Abre OBS.
2. **Herramientas → Configuración del servidor WebSocket**.
3. Marca **Habilitar servidor WebSocket**.
4. Puerto: **4455** (déjalo por defecto).
5. Pulsa **Generar** o escribe una **contraseña**. Apúntala.
6. Pulsa **Mostrar información de conexión**: ahí verás la **IP** y el **puerto**.

Anota la **IP de tu PC** (algo como `192.168.1.50`). Si cambia cada vez que reinicias el
router, reserva la IP por DHCP en el router o usa el nombre `.local` del equipo.

> El teléfono y el PC deben estar en **la misma red WiFi**.

---

## 2. Abrir el proyecto

1. Instala **Android Studio** (versión reciente, con Android SDK 35).
2. **File → Open** y selecciona la carpeta `ObsRemote`.
3. Espera al **Gradle Sync**. La primera vez descarga dependencias y el wrapper de Gradle
   (necesita internet). Si te pide crear/actualizar el wrapper, acepta.
4. Conecta tu teléfono por USB (con **Depuración USB** activada) o usa un emulador.
5. Pulsa **Run ▶**.

Para generar un APK instalable: **Build → Build Bundle(s)/APK(s) → Build APK(s)**.

---

## 3. Usar la app

1. Al abrir, escribe la **IP** de tu PC, el **puerto** (4455) y la **contraseña**.
2. Pulsa **Conectar**. El punto del encabezado se pone **verde**.
3. Pestaña **Escenas**: toca una para ponerla al aire. La escena activa se resalta.
4. Pestaña **Audio**: cada fuente tiene botón de **mute** y un **slider de volumen**.

Los ajustes se guardan en el teléfono, así que la próxima vez conecta solo.

---

## 4. Cómo está organizado el código

```
app/src/main/java/com/marco/obsremote/
├─ MainActivity.kt              Punto de entrada (Compose + edge-to-edge)
├─ obs/
│  ├─ ObsProtocol.kt            OpCodes, máscara de eventos y nombres de requests
│  ├─ ObsWebSocket.kt           Cliente del protocolo v5 (OkHttp + autenticación)
│  └─ ObsApi.kt                 Operaciones de alto nivel: escenas y audio
├─ data/
│  └─ SettingsStore.kt          Persistencia de IP / puerto / contraseña (DataStore)
└─ ui/
   ├─ ObsViewModel.kt           Estado, eventos, reconexión y debounce del volumen
   ├─ ObsRemoteApp.kt           Toda la interfaz (conexión, escenas, audio, ajustes)
   └─ theme/Theme.kt            Tema Material 3
```

### Detalles de implementación que vale la pena conocer

- **Autenticación v5**: `base64(sha256(base64(sha256(password + salt)) + challenge))`,
  calculada en `ObsWebSocket.buildAuthString`.
- **Suscripción a eventos**: solo pedimos `General | Scenes | Inputs`. No pedimos los
  eventos de "alto volumen" (los medidores de audio), que saturarían el WiFi sin aportar nada.
- **Detección de fuentes de audio**: `GetInputList` devuelve también fuentes de vídeo, que no
  responden a `GetInputVolume`. En lugar de mantener una lista de tipos de audio distinta por
  sistema operativo, se pregunta a todas y se conservan las que responden.
- **Escrituras optimistas**: al cambiar de escena o mover un volumen, la interfaz se actualiza
  al instante y luego se confirma con OBS. Si falla, se revierte.
- **Debounce del volumen**: no se envía nada a OBS hasta que el dedo se detiene ~150 ms.
  Sin esto, arrastrar un slider generaría decenas de peticiones por segundo.
- **Anti-eco**: cuando mueves un slider, se ignoran los `InputVolumeChanged` de esa fuente
  para que el control no "pelee" con el dedo.
- **Sincronización**: los eventos `CurrentProgramSceneChanged`, `InputMuteStateChanged` e
  `InputVolumeChanged` mantienen la app al día aunque cambies cosas desde el PC.

---

## 5. Problemas frecuentes

| Síntoma | Causa probable |
|---|---|
| "Sin conexión" y no pasa nada | El servidor WebSocket está desactivado en OBS |
| "OBS pide contraseña" | OBS exige contraseña y la app no tiene ninguna puesta |
| Se conecta y se cae | WiFi y PC en redes distintas (o el PC se durmió) |
| No aparecen escenas | OBS está abierto pero sin ninguna escena creada |
| No aparece una fuente de audio | Esa fuente no tiene canal de audio en OBS |

---

## 6. Ideas para seguir creciendo

- Botones de **grabación / stream / cámara virtual** (`StartRecord`, `StartStream`, `StartVirtualCam`).
- Atajos rápidos: un widget de Android que cambie a una escena concreta.
- Panel de **transiciones** (`SetCurrentSceneTransition`).
- Reconexión automática con *backoff* si se cae la WiFi.
