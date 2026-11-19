# SmartUse — aplicación de despliegue (Android)

Aplicación Android del grupo de **despliegue** de una tesis sobre el uso
compulsivo del teléfono inteligente. Ejecuta en el propio teléfono dos modelos
XGBoost entrenados con los datos del grupo de recolección y, cuando detectan
un patrón de uso, muestra una breve intervención visual a pantalla completa.
**Ningún dato de uso sale del teléfono:** al backend solo llegan el registro,
los formularios finales y métricas de rendimiento.

La distribución fue manual (APK, sin Play Store), para menos de 50
participantes, con Android 13 o superior. El identificador de la aplicación es
`com.example.smartuse_deployment`, distinto del de la app de recolección, para
que ambas puedan convivir en un mismo teléfono.

## Qué hace

1. **Configuración inicial.** La persona participante acepta los términos,
   indica edad, género, carrera y su puntaje de tamizaje SAS-SV, y concede los
   permisos: acceso a datos de uso, notificaciones, exención de optimización de
   batería y mostrar sobre otras aplicaciones. Si el teléfono tiene uso
   registrado en los 3 días anteriores, se le pregunta si autoriza usarlo para
   empezar antes. La app se registra con `POST /users` como
   `experiment_group = "inference"`.
2. **Captura.** Un servicio en primer plano y un ciclo de `WorkManager` (cada
   15 min) copian de `UsageStatsManager` los eventos de pantalla
   encendida/apagada y de aplicación en primer plano a un registro local, y los
   convierten en **épocas** contiguas de uso / no uso.
3. **Perfiles.** Tras 3 días locales con datos utilizables (o, como máximo, a
   las 72 h del registro) se congelan dos perfiles del uso típico de la
   persona, uno por modelo. Hasta entonces no hay intervenciones.
4. **Puntos de decisión.** En cada desbloqueo que abre una sesión y cada
   10 min de uso (hasta 3 h), la app reconstruye las sesiones hasta ese
   momento, calcula las features (78 para F, 36 para S) y evalúa los dos
   modelos:
   - **F** (uso frecuente): revisiones frecuentes en la próxima hora.
   - **S** (uso sostenido): uso prolongado alrededor de ese momento.
5. **Intervención.** Si un modelo supera su umbral y las reglas de ejecución lo
   permiten (10 min entre intervenciones, 60 min por patrón, máximo 20 al día;
   gana el mayor margen, F en empate), se muestra durante 10 s un velo gris que
   deja pasar los toques, con un panel y un botón para cerrarlo. Solo se
   muestra con el teléfono en uso y siempre puede cerrarse.
6. **Fin.** A los 21 días, en la fecha de corte del estudio
   (`GET /cutoff-deployment-group`) o al retirarse, todo se detiene: la persona
   llena el cuestionario final, la app hace **un** `POST /final` (respuestas +
   métricas, reintentado hasta recibir 2xx) y luego borra los datos de uso del
   teléfono.

## Privacidad

- **Nunca sale del teléfono:** épocas, registro crudo, sesiones, perfiles,
  vectores de features, puntajes de los modelos ni decisiones.
- **Nunca se registra:** cuántas intervenciones hubo, cuándo, ni si se
  cerraron (requisito del comité de ética). El único estado que se guarda es
  el de control de las reglas de ejecución (última intervención por patrón y
  conteo del día), que no se analiza ni se envía.
- **Los puntajes de los modelos** solo aparecen en logcat; no se guardan.
- **Métricas de rendimiento** (por punto de decisión: tiempo, batería,
  corriente media, memoria máxima) se guardan aparte, sin marcas de tiempo, y
  se envían una sola vez al final, mezcladas y sin identificadores.
- La identidad de cada aplicación se guarda solo como
  `HMAC-SHA256(clave por instalación, nombre del paquete)`; la clave vive en el
  Android Keystore, no es exportable y no puede revertirse.

## Estructura

```
app/src/main/java/com/example/smartuse_frontend/
  service/       servicio en primer plano y planificador de puntos de decisión
  inference/     línea de tiempo, sesiones, features, perfiles, modelos (SmartUseModel.java) y reglas
  intervention/  la intervención (ventanas superpuestas) y la tarjeta de recordatorio
  data/, data/db/ captura, SQLite (registro crudo, épocas canónicas, perfiles), Keystore, borrado local
  jobs/          ciclo de WorkManager, compuerta y reintentos
  study/         ventana del estudio y la máquina de estados del fin
  forms/, finalsend/  cuestionario final y su envío
  metrics/       métricas de rendimiento (metrics.db)
  network/       cliente HTTP (OkHttp) y DTOs
  nav/, ui/      Jetpack Compose: configuración, inicio, estado, datos, términos, formularios, pantalla final
app/src/main/assets/   los modelos F.model y S.model
app/src/test/          pruebas unitarias, incluida la paridad con la implementación de referencia
app/src/androidTest/   pruebas instrumentadas, con un backend falso en proceso
models/                paquete del modelo: especificación del extractor, modelos, fixtures y vectores de paridad
testlab/               ejecución de las pruebas instrumentadas en Firebase Test Lab
backend-checks/        verificación del contrato contra el backend real
```

`models/extractor_features.md` es la especificación de referencia del
extractor (épocas, sesiones, perfiles, features, reglas); el código cita sus
secciones como §n. Los fixtures son dos personas del conjunto de datos público
MPUD con todos los resultados intermedios esperados.

Endpoints usados: `POST /users`, `GET /cutoff-deployment-group`, `POST /final`.

## Requisitos

- Android Studio. El módulo incluye una fuente Java (`SmartUseModel.java`),
  así que Gradle necesita un JDK con `jlink`, como el JBR de Android Studio
  (no basta un JRE).
- Un dispositivo o emulador con Android 13+ (API 33). `targetSdk` es 33 a
  propósito: con 34+ el servicio en primer plano necesita un tipo declarado y
  con 35+ cambian sus límites.
- Python 3 (solo biblioteca estándar) para `testlab/` y `backend-checks/`.

## Configuración

La URL del backend, el contacto del estudio y las credenciales de firma **no
están en el código**. Copie `local.properties.example` a `local.properties`
(ignorado por git) y complete:

| Clave | Uso |
|---|---|
| `smartuse.baseUrl` | URL base del backend (o variable de entorno `SMARTUSE_BASE_URL`). Sin ella la app compila pero no puede conectarse. |
| `smartuse.contact.phone`, `smartuse.contact.email` | Contacto del estudio que ven las personas participantes (Inicio, Términos, pantallas finales); o variables `SMARTUSE_CONTACT_PHONE` / `SMARTUSE_CONTACT_EMAIL`. Sin ellas se muestra un texto de "no configurado". |
| `smartuse.release.baseUrl` | Opcional: otra URL para la versión release. |
| `smartuse.release.storeFile`, `storePassword`, `keyAlias`, `keyPassword` | Opcional: firma de la versión release; sin ellas el APK sale sin firmar. |
| `smartuse.cycleIntervalMinutes`, `smartuse.stuckAfterFailedRuns` | Opcional, solo debug: ajustar el ciclo para pruebas (mínimo 15 min). |

## Compilar e instalar

En Windows use `gradlew.bat` en lugar de `./gradlew`.

```
./gradlew assembleDebug        # APK de depuración
./gradlew installDebug         # compilar e instalar en el dispositivo conectado
./gradlew assembleRelease      # APK release (R8), firmado si hay credenciales
```

En la versión debug, la pantalla Datos tiene una tarjeta `[DEBUG]` con el
estado del pipeline y botones para ejecutar la captura, mostrar una
intervención de prueba y ejecutar un punto de decisión a demanda.

## Pruebas

### Unitarias

```
./gradlew testDebugUnitTest
./gradlew testDebugUnitTest --tests "com.example.smartuse_frontend.inference.SmartUseModelParityTest"
```

Además de la lógica de la app, comprueban contra los fixtures que la
implementación reproduce la de referencia: épocas, sesiones, perfiles,
vectores de features y puntajes de los modelos (paridad a 1e-6), y que las
reglas de ejecución coinciden con `models/models/production.json`.

### Instrumentadas

Corren sobre la versión debug contra un backend falso (MockWebServer en
`127.0.0.1`). Cada prueba parte de una instalación limpia (Android Test
Orchestrator).

```
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.example.smartuse_frontend.e2e.Live
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.smartuse_frontend.e2e.StudyServiceTest
```

- `@Live` (`e2e/live/`) habla con el backend real configurado y **registra
  participantes de prueba reales** (`university_major = "QA-BACKEND-CHECK"`);
  por eso se excluye arriba.
- `@Soak` espera las ejecuciones periódicas reales de WorkManager (20–30 min):
  solo en emulador.

### Firebase Test Lab

1. Instale la Google Cloud CLI y ejecute `gcloud auth login`,
   `gcloud config set project <id-del-proyecto-firebase>` y
   `gcloud components install beta` (para iniciar sin permisos concedidos).
2. Elija dispositivos en `testlab/devices.txt` (uno por línea,
   `model=…,version=…`; `python testlab/run_test_lab.py --models` lista los
   disponibles).
3. Ejecute:

```
python testlab/run_test_lab.py                   # suite completa (excepto @Soak y @Live)
python testlab/run_test_lab.py --live            # solo @Live, contra el backend real
python testlab/run_test_lab.py --targets "class com.example.smartuse_frontend.e2e.PermissionsTest"
testlab\run_compat.bat                           # API 33, 34, 35 y 36 (testlab/devices-compat.txt)
testlab\run_release_robo.bat                     # prueba Robo del APK release en API 35 y 36
python testlab/run_screenshots.py                # recorrido de capturas de todas las pantallas
```

Las capturas se descargan en `testlab/screenshots/` (ignorado por git).

### Verificación del backend

`backend-checks/run_checks.py` comprueba el contrato de los tres endpoints
contra el backend real configurado (`SMARTUSE_BASE_URL` o
`smartuse.baseUrl`). **Registra participantes de prueba reales**
(`university_major = "QA-BACKEND-CHECK"`, `experiment_group = "inference"`),
que no pueden borrarse.

```
python backend-checks/run_checks.py                  # genera fixtures nuevos y ejecuta todo
python backend-checks/run_checks.py --reuse-fixtures # reutiliza backend-checks/fixtures/
python backend-checks/run_checks.py --only cutoff final
```

### Pruebas manuales

**M1 — Intervención de extremo a extremo** (versión debug, app registrada,
permisos concedidos):

1. En Datos → `[DEBUG]`, pulse "Mostrar intervención". Esperado: velo gris
   con el panel arriba; los toques pasan a la app de debajo; "Cerrar" la
   quita y, si no se toca, desaparece sola a los 10 s.
2. Con los perfiles congelados (la pestaña Perfil de Datos lo indica), pulse
   "Ejecutar inferencia". Esperado: en logcat (`adb logcat`) aparecen los
   puntajes de F y S; si alguno supera su umbral se muestra la intervención.

**M2 — Permiso de notificaciones revocado a mitad del estudio** (no se puede
automatizar: revocarlo mata el proceso de la propia prueba):

1. Con la notificación del estudio visible, desactive las notificaciones
   (Ajustes → Apps → SmartUse → Notificaciones, o
   `adb shell pm revoke com.example.smartuse_deployment android.permission.POST_NOTIFICATIONS`).
2. Abra la app. Esperado: Estado muestra "Notificaciones: Falta" y
   "Corregir" abre los ajustes de notificaciones de la app.
3. Vuelva a activarlas. Esperado: la notificación del estudio vuelve a
   aparecer.

## Parámetros principales

| Parámetro | Valor |
|---|---|
| Ciclo de captura | cada 15 min (WorkManager) |
| Perfiles | primeros 3 días utilizables; forzados a las 72 h del registro |
| Puntos de decisión | desbloqueo que abre sesión + cada 10 min de uso, hasta 3 h |
| Sesión | épocas de uso separadas por menos de 30 s |
| Umbrales | F 0.302, S ≈ 0.2416 (`models/models/production.json`) |
| Reglas | 10 min entre intervenciones, 60 min por patrón, 20 al día |
| Intervención | 10 s, siempre cerrable |
| Duración por participante | 21 días o la fecha de corte, lo que ocurra primero |
| Versiones de Android | 13 a 16 (API 33–36) probadas; `minSdk` = `targetSdk` = 33 |
