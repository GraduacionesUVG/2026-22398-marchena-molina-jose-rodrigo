# SmartUse — aplicación de recolección de datos (Android)

Aplicación Android del grupo de **recolección de datos** (grupo control) de una
tesis sobre el uso compulsivo del teléfono inteligente. Registra, en segundo
plano y sin intervenir, cuándo se usa el teléfono, y envía al backend del
estudio una representación agregada y seudonimizada de ese uso. Esos datos
sirvieron para entrenar los modelos que usa la aplicación del segundo grupo
(despliegue), que vive en otro repositorio/rama.

La distribución fue manual (APK, sin Play Store), para menos de 50
participantes, con Android 13 o superior.

## Qué hace

1. **Configuración inicial.** La persona participante acepta los términos
   (desplazándose hasta el final), indica edad, género y carrera, y concede los
   permisos: acceso a datos de uso, notificaciones, exención de optimización de
   batería y, en las marcas que lo requieren, el inicio automático. La app se
   registra con `POST /users` como `experiment_group = "control"`.
2. **Captura** (cada 6 h, `WorkManager`). Copia de `UsageStatsManager` los
   eventos de pantalla encendida/apagada y de aplicación en primer plano a un
   registro local, y los convierte en **épocas** contiguas de uso / no uso con
   el número de aplicaciones distintas usadas.
3. **Sincronización** (cada 6 h y tras cada captura). Envía las épocas cerradas
   a `POST /sync/canonical` en lotes de hasta 500, guiada por un cursor que
   confirma el servidor; un reenvío nunca duplica datos.
4. **Notificación del estudio.** Una sola notificación silenciosa indica que el
   registro está activo y avisa si falta algún permiso.
5. **Fin.** La recolección termina a los 21 días del registro o en la fecha de
   corte del estudio (`GET /cutoff`, 00:00 hora de Guatemala), lo que ocurra
   primero, o antes si la persona se retira (retiro parcial: conserva lo ya
   enviado; retiro total: lo elimina del servidor). En cualquier caso se borran
   los datos locales y queda una pantalla final.

## Privacidad

- **Nunca sale del teléfono:** nombres de aplicaciones, el registro crudo de
  eventos ni marcas de tiempo individuales de cada aplicación.
- **Se envía:** el registro (edad, género, carrera, grupo) y, por época, su
  inicio, si fue de uso o no y cuántas aplicaciones distintas se usaron.
- La identidad de cada aplicación se guarda solo como
  `HMAC-SHA256(clave por instalación, nombre del paquete)`; la clave vive en el
  Android Keystore, no es exportable y no puede revertirse.
- El token de autenticación se cifra con AES-256/GCM (clave en el Keystore).
- La pantalla de datos muestra solo información agregada; la persona puede
  exportar su propio registro crudo (con los nombres ya cifrados) en CSV.

## Estructura

```
app/src/main/java/com/example/smartuse_frontend/
  config/        grupo experimental (siempre "control" en esta app)
  data/          repositorios, almacenamiento seguro, Keystore, borrado local
  data/db/       SQLite: registro crudo, épocas canónicas, sincronización
  jobs/          trabajos de WorkManager (captura, sincronización, retiro), compuerta y reintentos
  network/       cliente HTTP (OkHttp) y DTOs del contrato del backend
  notification/  la notificación del estudio
  study/         ventana del estudio (día 21 / fecha de corte) y su cierre
  nav/, ui/      Jetpack Compose: configuración, inicio, estado, datos, retiro, términos
app/src/test/          pruebas unitarias (JVM)
app/src/androidTest/   pruebas instrumentadas, con un backend falso en proceso
testlab/               ejecución de las pruebas instrumentadas en Firebase Test Lab
backend-checks/        verificación del contrato contra el backend real
```

Endpoints usados: `POST /users`, `GET /cutoff`, `GET /sync/canonical/cursor`,
`POST /sync/canonical`, `POST /opt-out/soft`, `POST /opt-out/hard`.

## Requisitos

- Android Studio (o un JDK 17+ con el Android SDK); Gradle se descarga con el
  wrapper.
- Un dispositivo o emulador con Android 13+ (API 33). `targetSdk` es 33 a
  propósito: con 34+ cambian las reglas de ejecución en segundo plano.
- Python 3 (solo biblioteca estándar) para `testlab/` y `backend-checks/`.

## Configuración

La URL del backend, el contacto del estudio y las credenciales de firma **no
están en el código**.
Copie `local.properties.example` a `local.properties` (ignorado por git) y
complete:

| Clave | Uso |
|---|---|
| `smartuse.baseUrl` | URL base del backend (o variable de entorno `SMARTUSE_BASE_URL`). Sin ella la app compila pero no puede conectarse. |
| `smartuse.contact.phone`, `smartuse.contact.email` | Contacto del estudio que ven las personas participantes (Inicio, Términos, pantallas finales); o variables `SMARTUSE_CONTACT_PHONE` / `SMARTUSE_CONTACT_EMAIL`. Sin ellas se muestra un texto de "no configurado". |
| `smartuse.release.baseUrl` | Opcional: otra URL para la versión release. |
| `smartuse.release.storeFile`, `storePassword`, `keyAlias`, `keyPassword` | Opcional: firma de la versión release; sin ellas el APK sale sin firmar. |
| `smartuse.captureIntervalMinutes`, `syncIntervalMinutes`, `syncBatchSize`, `stuckAfterFailedRuns` | Opcional, solo debug: ajustar los trabajos para pruebas (mínimo 15 min). |

## Compilar e instalar

En Windows use `gradlew.bat` en lugar de `./gradlew`.

```
./gradlew assembleDebug        # APK de depuración
./gradlew installDebug         # compilar e instalar en el dispositivo conectado
./gradlew assembleRelease      # APK release (R8), firmado si hay credenciales
```

## Pruebas

### Unitarias

```
./gradlew testDebugUnitTest
./gradlew testDebugUnitTest --tests "com.example.smartuse_frontend.data.db.EpochBuilderTest"
```

Cubren la construcción de épocas (cualquier partición del registro crudo da la
misma línea de tiempo), los identificadores deterministas, la sincronización
por lotes y su clasificación de fallos, la política de reintentos, la ventana
del estudio, el retiro y los ViewModels.

### Instrumentadas

Corren sobre la versión debug contra un backend falso (MockWebServer en
`127.0.0.1`); nunca llaman al backend real. Cada prueba parte de una
instalación limpia (Android Test Orchestrator).

```
./gradlew connectedDebugAndroidTest
```

Las pruebas `@Soak` esperan las ejecuciones periódicas reales de WorkManager
(20–30 min): solo en emulador, con `smartuse.captureIntervalMinutes=15`.

### Firebase Test Lab

1. Instale la Google Cloud CLI y ejecute `gcloud auth login`,
   `gcloud config set project <id-del-proyecto-firebase>` y
   `gcloud components install beta` (para iniciar sin permisos concedidos).
2. Elija dispositivos en `testlab/devices.txt` (uno por línea,
   `model=…,version=…`; `python testlab/run_test_lab.py --models` lista los
   disponibles).
3. Ejecute:

```
python testlab/run_test_lab.py                   # suite completa (excepto @Soak)
python testlab/run_test_lab.py --targets "class com.example.smartuse_frontend.e2e.PermissionsTest"
testlab\run_compat.bat                           # API 33, 34, 35 y 36 (testlab/devices-compat.txt)
python testlab/run_screenshots.py                # capturas de las pantallas de retiro
```

Las capturas se descargan en `testlab/screenshots/` (ignorado por git).

### Verificación del backend

`backend-checks/run_checks.py` comprueba el contrato de la API contra el
backend real configurado (`SMARTUSE_BASE_URL` o `smartuse.baseUrl`). **Crea
participantes de prueba reales**, marcados con
`university_major = "QA-BACKEND-CHECK"` y retirados (retiro total) al final.

```
python backend-checks/run_checks.py                  # genera fixtures nuevos y ejecuta todo
python backend-checks/run_checks.py --reuse-fixtures # reutiliza backend-checks/fixtures/
python backend-checks/run_checks.py --only cutoff sync
```

### Pruebas manuales

Dos casos no pueden automatizarse, porque matarían el proceso de la propia
prueba. Requieren `adb` y la app registrada.

**M1 — Proceso terminado durante una ejecución.**

1. Con la app en segundo plano, liste los trabajos:
   `adb shell dumpsys jobscheduler com.example.smartuse_frontend`.
2. Fuerce la captura:
   `adb shell cmd jobscheduler run -f -n androidx.work.systemjobscheduler com.example.smartuse_frontend <id>`
   (en API 33, sin `-n androidx.work.systemjobscheduler`), y en menos de un
   segundo: `adb shell am kill com.example.smartuse_frontend`.
3. Fuerce de nuevo captura y sincronización, o abra la app.
4. Esperado: ambas ejecuciones terminan bien, Estado muestra ambos trabajos
   "Al día", los conteos de Datos solo crecen y no hay épocas duplicadas ni
   faltantes. Repita terminando el proceso durante una sincronización.

**M2 — Permiso de notificaciones revocado a mitad del estudio.**

1. Con la notificación del estudio visible, desactive las notificaciones
   (Ajustes → Apps → SmartUse → Notificaciones, o
   `adb shell pm revoke com.example.smartuse_frontend android.permission.POST_NOTIFICATIONS`).
2. Abra la app. Esperado: Inicio y Estado muestran "Notificaciones: Falta" y
   "Corregir" abre los ajustes de notificaciones de la app.
3. Vuelva a activarlas y fuerce una captura (como en M1). Esperado: la
   notificación del estudio vuelve a aparecer.

## Parámetros principales

| Parámetro | Valor |
|---|---|
| Intervalo de captura / sincronización | 6 h / 6 h (y una sincronización tras cada captura) |
| Tamaño de lote | ≤ 500 registros; ≤ 50 lotes y ≤ 3 min por ejecución |
| Reintentos | 5 intentos por ejecución, con espera exponencial |
| Duración por participante | 21 días o la fecha de corte, lo que ocurra primero |
| Versiones de Android | 13 a 16 (API 33–36) probadas; `minSdk` = `targetSdk` = 33 |
