# SmartUse

Prototipo de sistema de detección y reducción de uso compulsivo de smartphones en estudiantes universitarios guatemaltecos mediante un modelo predictivo y un módulo de intervención UI/UX.

Trabajo de graduación: Licenciatura en Ingeniería en Ciencia de la Computación y Tecnologías de la Información, Universidad del Valle de Guatemala, 2026.

## Descripción

Las herramientas de bienestar digital disponibles dependen de que el usuario decida limitar su propio uso o se basan en el tiempo total de pantalla, que no distingue el uso funcional del uso problemático. **SmartUse** identifica, a partir de los registros del propio dispositivo, momentos de uso asociados al uso problemático e interviene sobre la interfaz en esos momentos, sin depender de la iniciativa del usuario.

El sistema integra cuatro componentes:

- **Aplicación Android de recolección** (`src/frontend_v1`): registra eventos de pantalla y de aplicaciones en primer plano mediante `UsageStatsManager`, los agrupa en épocas de uso y no uso y las sincroniza con el backend. No registra contenido, notificaciones, ubicación ni nombres de aplicaciones; estos últimos se transforman con HMAC-SHA256 y nunca salen del teléfono.
- **Aplicación Android de despliegue** (`src/frontend_v2`): calcula las features y ejecuta en el propio dispositivo dos modelos XGBoost, uno de **uso fragmentado (F)** y otro de **uso sticky (S)**. Cuando un modelo supera su corte, muestra una intervención visual: un velo gris semitransparente con un contador de 10 s y un botón de cierre siempre disponible. Ningún dato de uso sale del teléfono; al backend solo llegan el registro, la encuesta de cierre y las métricas de rendimiento.
- **Backend serverless en AWS** (`src/backend`): API REST con autenticación por token para el registro, la sincronización, el retiro y el envío final, sobre Aurora PostgreSQL Serverless v2, definida como código con AWS CDK. Incluye un tablero de Grafana para seguir la sincronización.
- **Pipeline de datos y modelos** (`src/data_ml`): limpieza, sesiones, etiquetas relativas al percentil 80 de cada participante, features a varias escalas temporales, preentrenamiento con el conjunto de datos público MPUD, ajuste fino con los datos del grupo de recolección, evaluación dejando un participante fuera (LOSO), explicabilidad (SHAP, PDP/ICE) y exportación de los modelos a la aplicación.

Además, `src/sas-sv` contiene el formulario web estático de tamizaje con la Smartphone Addiction Scale - Short Version (SAS-SV), que calcula el puntaje sin almacenar respuestas.

### Resultados principales

- El modelo de uso sticky alcanzó una mediana del AUC por participante de **0.953** y un F1 de **0.746**. El de uso fragmentado pronostica la hora siguiente con un AUC de **0.710** y un F1 de **0.400**.
- Ambos modelos superaron a la línea base de persistencia en todos los participantes evaluables.
- En el dispositivo, el percentil 90 del tiempo de ejecución por punto de decisión no superó **1.4 s** y el consumo estimado de batería fue menor a **0.25 % por día**.
- La usabilidad percibida fue mayormente favorable (8 de 13 participantes respondieron la encuesta de cierre).

El detalle completo está en el [informe final](docs/informe_final.pdf).

## Tecnologías utilizadas

| Componente | Tecnologías |
|---|---|
| Aplicaciones Android | Kotlin, Jetpack Compose, Material 3, WorkManager, SQLite, OkHttp, Android Keystore, Java (implementación de los modelos), JUnit |
| Backend | TypeScript, Node.js, pnpm, AWS CDK, Amazon API Gateway, AWS Lambda, Amazon Aurora PostgreSQL Serverless v2 (RDS Data API), AWS Secrets Manager, Jest |
| Monitoreo | Grafana OSS |
| Datos y modelos | Python, pandas, NumPy, PyArrow, SciPy, scikit-learn, XGBoost, PyTorch (LSTM de comparación), Matplotlib, Jupyter, pytest |
| Formulario SAS-SV | HTML, CSS y JavaScript estáticos, Vercel |
| Pruebas en dispositivos | Firebase Test Lab, Samsung Remote Test Lab |

## Estructura del repositorio

```
├── capturas/                 capturas de pantalla del sistema
├── demo/demo.mp4             video demostrativo
├── docs/
│   ├── informe_final.pdf
│   └── consentimiento_firmado.pdf
├── src/
│   ├── .env.example          variables de entorno de todos los componentes
│   ├── backend/smartUse-backend/        backend AWS (CDK), Grafana y scripts
│   ├── data_ml/smartUse-dataMining/     pipeline de datos, experimentos y modelos
│   ├── frontend_v1/smartUse-frontend/   app Android de recolección
│   ├── frontend_v2/smartUse-frontend/   app Android de despliegue
│   └── sas-sv/                          formulario web de la SAS-SV
└── README.md
```

Cada subproyecto tiene su propio `README.md` con el detalle técnico: arquitectura, requisitos, protocolo de sincronización, features y experimentos.

## Requisitos previos

- **Git**
- **Backend:** una cuenta de AWS, AWS CLI v2 configurado con un perfil, Node.js 24 y pnpm. Python 3.10+ con `boto3` para los scripts.
- **Aplicaciones Android:** Android Studio con su JDK incluido (JBR; la app de despliegue requiere un JDK con `jlink`, no basta un JRE), Android SDK con API 33 y un dispositivo o emulador con Android 13 o superior. Python 3 (biblioteca estándar) para las verificaciones en `testlab/` y `backend-checks/`.
- **Datos y modelos:** Python 3.12, Java (para la prueba de paridad del modelo exportado) y el conjunto de datos público MPUD (Mobile Phone Use Dataset) para el preentrenamiento.
- **Monitoreo (opcional):** Windows PowerShell, `bash` y `openssl`.

## Instalación

### 1. Clonar el repositorio

```bash
git clone https://github.com/MarchMol/2026-22398-marchena-molina-jose-rodrigo.git
cd 2026-22398-marchena-molina-jose-rodrigo
```

### 2. Configurar las variables de entorno

`src/.env.example` lista todas las variables del proyecto, agrupadas por componente, con el lugar donde se configura cada grupo:

```bash
cp src/.env.example src/.env
# Editar con los valores correspondientes; ver los pasos de cada componente
```

### 3. Backend en AWS

```bash
cd src/backend/smartUse-backend/src
pnpm install
pnpm build                 # verificación de tipos
pnpm test                  # pruebas unitarias de las funciones y de la infraestructura

export AWS_PROFILE=smartuse
npx cdk bootstrap          # una vez por cuenta y región
npx cdk deploy --all       # crea las pilas de red, datos y API y aplica el esquema
```

`cdk deploy` imprime la URL base de la API (`https://<api-id>.execute-api.<region>.amazonaws.com/prod`), que usan las aplicaciones. Para verificar el despliegue:

```bash
cd ..
python scripts/check_deployment.py https://<api-id>.execute-api.<region>.amazonaws.com/prod
```

Para eliminar la infraestructura: `npx cdk destroy --all`.

### 4. Aplicaciones Android

Los pasos son los mismos para ambas versiones (`frontend_v1`, recolección, y `frontend_v2`, despliegue):

```bash
cd src/frontend_v1/smartUse-frontend      # o src/frontend_v2/smartUse-frontend
cp local.properties.example local.properties
# Completar smartuse.baseUrl con la URL de la API y los datos de contacto del estudio
```

### 5. Pipeline de datos y modelos

```bash
cd src/data_ml/smartUse-dataMining
python -m venv .venv
source .venv/bin/activate              # en Windows: .venv\Scripts\activate
pip install -r requirements.txt
```

Los datos crudos no forman parte del repositorio. Para reproducir el entrenamiento, se coloca MPUD en `data/mpud_raw/`. Los datos del grupo de recolección se eliminan conforme al protocolo aprobado por el CEIFI. Los modelos entrenados ya se incluyen en `export/models/` y en la app de despliegue.

### 6. Formulario SAS-SV

`src/sas-sv/index.html` es un archivo estático sin dependencias. Se puede abrir directamente en el navegador o publicar en cualquier hosting estático, como Vercel.

## Ejecución

### Aplicaciones Android

Desde la carpeta de cada app (en Windows, `gradlew.bat` en lugar de `./gradlew`):

```bash
./gradlew installDebug        # compilar e instalar en el dispositivo o emulador conectado
./gradlew assembleRelease     # APK de distribución (firmado si se configuran las credenciales)
./gradlew test                # pruebas unitarias
```

Al abrir la app, el asistente de configuración solicita aceptar los términos, los datos de registro y los permisos: acceso a datos de uso, notificaciones, ejecución en segundo plano y, en la versión de despliegue, mostrar contenido sobre otras aplicaciones. En la versión de despliegue, las intervenciones comienzan cuando se completan los perfiles de uso habitual (3 días). En la compilación debug, la pantalla de datos permite mostrar una intervención de prueba y ejecutar un punto de decisión a demanda.

### Pipeline de datos y modelos

Desde `src/data_ml/smartUse-dataMining`:

```bash
# Limpieza (fases 1 a 3)
python pipelines/mpud/mpud_extraction.py
python pipelines/mpud/mpud_preprocess_1.py
python pipelines/mpud/mpud_preprocess_2.py
python pipelines/mpud/mpud_preprocess_3.py
python pipelines/real/real_preprocess_1.py
python pipelines/real/real_preprocess_2.py
python pipelines/real/real_preprocess_3.py

# Ventanas, features y etiquetas (fases 4 y 5)
python -m pipelines.labels.build_windows <sesiones> <salida> --mode points --epochs <épocas>
python -m pipelines.labels.build_features real
python -m pipelines.labels.build_features mpud

# Modelos finales y exportación
python -m experiments.cutoff_f1
python -m experiments.train_final
python -m experiments.export_models
python -m experiments.make_fixtures

# Pruebas
python -m pytest
```

Los argumentos de cada paso están en el docstring de su script y en la sección 13 del `README.md` del subproyecto.

### Monitoreo con Grafana (opcional)

Desde `src/backend/smartUse-backend`:

1. Autorizar la IP propia en el grupo de seguridad de Aurora (PostgreSQL, puerto 5432).
2. `bash grafana/setup-db-access.sh`: crea el rol de solo lectura y genera `grafana/.env`.
3. `powershell -ExecutionPolicy Bypass -File grafana/run-grafana.ps1`: sirve el tablero en <http://localhost:3000>.

## Capturas

Las capturas de pantalla del sistema se encuentran en [`/capturas`](capturas/) y están numeradas en el orden de uso del sistema.

| # | Captura | Contenido |
|---|---|---|
| 01 | [captura-01](capturas/captura-01.png) | Formulario SAS-SV: resultado de un caso elegible |
| 02 | [captura-02](capturas/captura-02.png) | Registro: aceptación de términos y condiciones y datos del participante |
| 03 | [captura-03](capturas/captura-03.png) | Pasos de permisos del asistente de configuración |
| 04 | [captura-04](capturas/captura-04.png) | Página principal |
| 05 | [captura-05](capturas/captura-05.png) | Estado de la instalación, datos de perfil e información recolectada |
| 06 | [captura-06](capturas/captura-06.png) | Modal de ayuda, notificación persistente y notificación de cierre del estudio |
| 07 | [captura-07](capturas/captura-07.png) | Intervención visual: pantalla antes y durante la intervención |
| 08 | [captura-08](capturas/captura-08.png) | Componentes de la intervención visual |
| 09 | [captura-09](capturas/captura-09.png) | Retiro del estudio en los grupos de despliegue y de recolección |
| 10 | [captura-10](capturas/captura-10.png) | Formularios finales y confirmación del envío |
| 11 | [captura-11](capturas/captura-11.png) | Pantallas finales: envío de respuestas, retiro y fin del periodo de estudio |
| 12 | [captura-12](capturas/captura-12.png) | Tablero de Grafana para el seguimiento de la sincronización |
| 13 | [captura-13](capturas/captura-13.png) | Infraestructura del backend en AWS |

## Demo

El video demostrativo se encuentra en [`/demo/demo.mp4`](demo/demo.mp4).

## Documentación

- El informe final del proyecto está disponible en [`/docs/informe_final.pdf`](docs/informe_final.pdf).
- El consentimiento de uso de foto y video firmado se encuentra en [`/docs/consentimiento_firmado.pdf`](docs/consentimiento_firmado.pdf).
- Documentación técnica por componente:
  - [Backend](src/backend/smartUse-backend/README.md) (especificación de la API en `docs/api/openapi.yaml`)
  - [Datos y modelos](src/data_ml/smartUse-dataMining/README.md)
  - [App de recolección](src/frontend_v1/smartUse-frontend/README.md)
  - [App de despliegue](src/frontend_v2/smartUse-frontend/README.md)

## Privacidad y datos

Este repositorio no contiene datos de participantes. Los datos crudos y procesados del estudio se mantuvieron fuera del control de versiones y se eliminan conforme al protocolo aprobado por el Comité de Ética en Investigación de la Facultad de Ingeniería (CEIFI). Los fixtures y vectores de paridad incluidos provienen del conjunto de datos público MPUD. Las URL, credenciales y datos de contacto se configuran mediante archivos locales excluidos de Git (`.env`, `local.properties`).

## Autor

**José Rodrigo Marchena Molina**, carné 22398

Asesor: Ing. Luis Alberto Suriano
