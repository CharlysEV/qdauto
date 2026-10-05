# Protocolo QDLink (Wi-Fi) — resumen

Fuente: QDLink `com.neusoft.qdrivelink` 1.9.7 (versionCode 107), extraído del S25 Ultra y decompilado con jadx 1.5.6.
Todo lo de aquí sale de **leer el código**; nada está todavía probado contra el coche. El detalle con citas `archivo:línea` está en:

- [01-discovery-transport.md](01-discovery-transport.md): red, descubrimiento, sockets, ciclo de vida
- [02-video-ssp.md](02-video-ssp.md): captura, encoder, formato de vídeo, librería nativa
- [03-control-input.md](03-control-input.md): mensajes de control, handshake, táctil y teclas

---

## 1. Red

- QDLink **no configura la Wi-Fi**. O el móvil comparte zona Wi-Fi y el coche se conecta a ella (se recomienda 5 GHz), o se usa Wi-Fi Direct con el coche como *group owner* (`groupOwnerIntent=0`).
- No hay IPs fijas. La IP del coche se saca del origen de su broadcast UDP.

## 2. Descubrimiento (UDP)

```
Coche  ──UDP broadcast──▶  móvil :18463     "Connect_Broadcast"  (lleva DeviceUUID, DeviceName)
Móvil  ──UDP unicast────▶  coche :18464     "Broadcast_ACK"      (un solo envío, sin reintento)
```

Trama UDP en ASCII:
`QDrive_SSPLink_UDP_MSG` + longitud total (4 hex) + longitud del tipo (2 hex) + tipo + longitud del JSON (4 hex) + JSON.

El ACK le dice al coche a qué puerto TCP conectarse (QDLink elige uno aleatorio entre 10001 y 65535):

```json
{"ControlPort":0,"MirrorPort":P,"AudioPort":0,"OS":0,"DeviceName":"","DeviceUUID":"","DeviceFeature":{"PassistMobileNum":""}}
```

## 3. Transporte

- **Una sola conexión TCP** lleva todo: control JSON, vídeo H.264, táctil, micro del coche y heartbeat.
- **El móvil es el servidor** (escucha en `P`) y **el coche se conecta**, como mucho 20 s después del ACK.
- El audio multimedia va por **Bluetooth A2DP**, no por la Wi-Fi.
- No hay autenticación ni cifrado. La activación online está desactivada en esta versión.

## 4. Trama "5A5A" (todo big-endian)

Cabecera de 16 bytes (`h0/a.java`):

| Offset | Tamaño | Campo |
|---|---|---|
| 0 | 4 | ASCII `5A5A` |
| 4 | u32 | tamaño total del mensaje (**incluye** esta cabecera) |
| 8 | u16 | longitud de la cabecera extendida (0 en control, 32 en vídeo) |
| 10 | u8 | msgType: 0 = control JSON, 1 = vídeo, 2 = táctil, 13 = control de "apps" |
| 11–12 | 2 | 0 |
| 13 | u8 | formato del payload: 0 = binario, 1 = JSON, 2 = vídeo |
| 14–15 | 2 | 0 |

**Vídeo** (msgType 1): cabecera de 16 bytes, luego cabecera extendida de 32 bytes (`h0/d.java`) y después NAL Annex B.

| Offset | Tamaño | Campo |
|---|---|---|
| 0 | u16 | 32 |
| 2 | u8 | 1 |
| 3 | u8 | 0 |
| 4 | u32 | ancho |
| 8 | u32 | alto |
| 12 | i16 | ángulo |
| 14 | u8 | orientación |
| 15 | u8 | tipo de codificación |
| 16 | u32 | fps |
| 20 | u32 | bitrate |
| 24 | u32 | GOP |
| 28 | u8 | appType (1 = UI de QDLink, 2 = espejo) |
| 29–31 | 3 | 0 |

- Cada mensaje lleva un buffer de salida del encoder.
- SPS y PPS van juntos en un **mensaje propio** al principio.
- No hay timestamp, número de secuencia ni CRC.

**Táctil** (msgType 2, coche → móvil): action i32 + número de dedos u8. Por cada dedo: id u8, acción u8 (1 = down, 2 = up, 3 = move), x float32, y float32.

## 5. Handshake

```
coche conecta por TCP a P
móvil  → bloque "!BIN" de 512 bytes (AppStatus, protocolo legado; se manda siempre)
móvil  → {"CMD":"HEARTBEAT"} al cabo de 1 s y luego cada 3 s   (corta si el coche pasa >5 s sin enviar nada)
coche  → CAR_INFO        {Version, CarType, Platform, CarWidth, CarHeight, CarFactory, HUFactory, MirrorTypeReq, ...}
móvil  → PHONE_INFO  +  UPDATE_NOTIFY{UpdateStatus:5}
coche  → VIDEO_SUP_REQ
móvil  → VIDEO_SUP_RSP   {VideoFormat:3, VideoSupport:1}     (QDLink pide aquí el permiso de captura)
coche  → VIDEO_ARGS      {bitrate, fps, gop, ...}
móvil  → SPEECH_ARGS     {1, 16000, 1, 16}
coche  → VIDEO_CTRL      {PlayStatus:1}
móvil  → vídeo: SPS/PPS y luego frames
```

- Los mensajes de control son JSON: `{"CMD":"...","PARA":{...}}` (msgType 0) o `{"AppID":..,"FunctionID":..,"Para":{..}}` (msgType 13).
- El C10 en la base de datos interna: `CarFactory` 018, `CarType` 2D4 (volante a la izquierda) o 2D5 (a la derecha), `HUFactory` 119.

## 6. Teclas

- `PHONE_KEYS`: 1 = Home, 2 = Atrás, 3 = Recientes.
- Música (AppID `Music`): `PlayControl`, `PlayControlPlay`, `PlayControlPause`, `Prev`, `Next`, `MuteControl`.

## 7. Por qué QDLink va como va

| Limitación | Causa en el código |
|---|---|
| Hace falta la pantalla encendida | El espejo captura la pantalla real con un VirtualDisplay público, que se queda en negro con la pantalla apagada. Por eso usa un wake lock completo. |
| Pide permiso de captura en cada sesión | MediaProjection; si se rechaza, se desconecta |
| Tirones | Envío bloqueante en el hilo de render, buffer TCP de 4 MiB (la latencia crece), nunca pide un keyframe de verdad, temporizador de fps fijo |
| Táctil torpe | `AccessibilityService.dispatchGesture` con trazos troceados cada 200 ms; no hay pulsación larga real y los arrastres van a saltos |
| No reconecta | No existe lógica de reconexión |

## 8. Qué significa para nuestra app

1. **Nada de MediaProjection**: le damos al coche el H.264 que genera Android Auto, así que **no hace falta pantalla encendida** ni pedir permiso de captura.
2. **Táctil nativo**: los `x,y` en float del coche se traducen a mensajes de input de Android Auto. No hace falta AccessibilityService, así que hay multitáctil, arrastres suaves y pulsación larga.
3. **Vídeo**: el coche espera H.264 Annex B con SPS/PPS aparte (QDLink usa Baseline, nivel 3.1).
   - Si la resolución que negociamos con Android Auto cuadra con `CarWidth × CarHeight`, se puede reenviar **sin recodificar**.
   - Si no, decodificamos a Surface y recodificamos.
4. **Rendimiento**:
   - Envío en un hilo aparte con cola corta que descarta frames si se acumulan.
   - Buffer TCP pequeño.
   - Keyframe real bajo demanda.
5. **Reconexión**: escuchar siempre el broadcast UDP desde un servicio en primer plano y reconectar solos.

## 9. Incógnitas (hay que verlas en vivo)

- Valores reales de `CAR_INFO` en el C10 (`CarWidth`/`CarHeight`, `MirrorTypeReq`) y de `VIDEO_ARGS`.
- Si el táctil llega en píxeles del coche o del móvil.
- Si el coche acepta un perfil H.264 distinto de Baseline o una resolución distinta de la que pide.
- Qué hace el coche si no recibe el bloque `!BIN` inicial.

**Cómo sacarlo sin captura de red**:
1. En QDLink → Ajustes, tocar 6 veces en menos de 2,2 s la esquina inferior izquierda para abrir la pantalla oculta.
2. Activar los logs.
3. Con eso, `adb logcat` muestra cada JSON enviado y recibido y cada evento táctil.
