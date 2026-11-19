# smartUse — minería de datos y modelo

Repositorio de la fase 2 de smartUse (trabajo de graduación): limpieza de
los datos de uso del teléfono, definición de la etiqueta, features,
experimentos, entrenamiento de los modelos y su exportación al dispositivo.
Este documento es la única documentación del repositorio: explica qué se
decidió, por qué, con qué evidencia y cómo reproducirlo.

El código está en inglés y la documentación en español. Los datos no se
publican (ver [§13.4](#134-privacidad-y-qué-se-publica)); sí se publican el
código completo, los notebooks ejecutados y los modelos entrenados.

## Resumen

- **Objetivo.** Predecir, en el teléfono, momentos de **uso fragmentado
  (F)** y **uso sticky (S)** (Siebers et al., 2023) para disparar una
  intervención UI/UX justo a tiempo (JITAI). Se clasifica el momento, no la
  persona.
- **Datos.** MPUD (España, 2016; 215 personas, 203 tras la limpieza) para
  preentrenar y validar aproximaciones; datos reales (Guatemala, octubre de
  2026; 19 personas, 18 utilizables, 209 días de registro) para ajustar y
  evaluar.
- **Etiqueta.** En cada punto de decisión, F o S de una ventana de 60 min
  por encima del percentil 80 del perfil propio, congelado con los primeros
  3 días utilizables.
- **Modelos.** Dos XGBoost (profundidad 4), uno por patrón, preentrenados
  en MPUD y ajustados con los datos reales; cada uno es un ensamble
  fusionado de 5 semillas.

| | Modelo F | Modelo S |
|---|---|---|
| Qué hace | Predice una hora fragmentada en los próximos 60 min | Detecta, con 30 min de anticipación, una sesión que se vuelve sticky |
| Features | 78 (36 + 42 de dinámica) | 36 |
| Ventana de la etiqueta | [*t*, *t* + 60 min) | [*t* − 30, *t* + 30 min) |
| Calibración | Platt | Ninguna |
| AUC (mediana por persona, dejando una persona fuera) | 0.710 (IC 95 % 0.676–0.729) | 0.953 (0.923–0.970); la parte ya observada sola da 0.904 |
| **Corte operativo** | **0.3024** (máximo F1) | **0.2416** (regla de E9b) |

- **Operación.** Enfriamiento de 10 min, una intervención por patrón cada
  60 min, tope de 12 por persona y día.
- **Exportación.** Los modelos se exportan como árboles a un formato de
  texto propio y una clase Java sin dependencias; paridad Java–Python
  ≤ 2·10⁻⁷.

## Contenido

1. [Contexto y objetivo](#1-contexto-y-objetivo)
2. [Marco teórico](#2-marco-teórico)
3. [Datos](#3-datos)
4. [Limpieza: del dato crudo al registro de sesiones](#4-limpieza-del-dato-crudo-al-registro-de-sesiones)
5. [Ventanas, etiqueta y perfil](#5-ventanas-etiqueta-y-perfil)
6. [Features](#6-features)
7. [Modelos y evaluación](#7-modelos-y-evaluación)
8. [Operación: regla de decisión y cortes](#8-operación-regla-de-decisión-y-cortes)
9. [Extractor en el dispositivo y exportación](#9-extractor-en-el-dispositivo-y-exportación)
10. [Registro de experimentos](#10-registro-de-experimentos)
11. [Decisiones y desviaciones](#11-decisiones-y-desviaciones)
12. [Limitaciones](#12-limitaciones)
13. [Repositorio y reproducibilidad](#13-repositorio-y-reproducibilidad)
14. [Glosario y notación](#14-glosario-y-notación)
15. [Referencias](#15-referencias)

---

## 1. Contexto y objetivo

### 1.1 El proyecto

smartUse estudia el uso problemático del smartphone y construye una
intervención *just-in-time* (JITAI): el teléfono detecta momentos de riesgo
y muestra una intervención de interfaz antes o durante ese momento.

| Fase | Contenido | Dónde vive |
|---|---|---|
| 1 | App de recolección: captura el uso real de participantes, sin modelo | app + `smartUse-backend` |
| **2** | **Datos, etiqueta, features, experimentos y modelo** | **este repositorio** |
| 3 | App v2 con el modelo en el dispositivo (despliegue de la JITAI) | app + `smartUse-backend` |

### 1.2 Objetivo

> Predecir, con datos de pantalla registrables en el teléfono, los momentos
> de **uso fragmentado** (muchas sesiones cortas y dispersas) y de **uso
> sticky** (una sesión que se prolonga) de cada persona, para disparar una
> intervención UI/UX en el momento oportuno.

Requisitos que se derivan:

1. **Por momento, no por persona.** Una JITAI necesita saber *cuándo*
   intervenir, no *a quién* clasificar.
2. **Relativo a la persona.** La etiqueta compara cada momento con el uso
   típico de esa persona (perfil propio).
3. **Calculable en el dispositivo.** Solo con lo que el teléfono registra:
   encendidos y apagados de pantalla, número de apps por época y la hora.
4. **Paridad entrenamiento–despliegue.** El modelo aprende exactamente la
   tarea que resolverá en operación: mismos puntos de decisión, mismas
   reglas de sesión, mismo perfil congelado.
5. **Usabilidad.** Las intervenciones tienen que ser pocas y acertadas.

### 1.3 De Lin et al. a Siebers et al.

La primera versión del proyecto usaba los umbrales de Lin et al. (2017)
como etiqueta. Se reemplazaron por dos razones: no se transfieren (son
cortes derivados de 79 estudiantes taiwaneses en 2013–2014, sobre promedios
de 24 h) y clasifican personas, no momentos. La definición de uso
compulsivo pasó a ser **conductual y por periodo**: uso fragmentado (F) y
uso sticky (S), según Siebers et al. (2023). Los parámetros de Lin
(frecuencia, total y mediana de uso y de no-uso) se conservan como
**features**, no como umbrales.

El repositorio se reinició desde cero el 2026-10-15 con estas reglas: dos
conjuntos de datos (MPUD y reales); cada paso del pipeline es un script que
lee la salida anterior y escribe un archivo nuevo; la etiqueta, la ventana
y el modelo se deciden por experimentos con criterio fijado **antes** de
correrlos; la limpieza solo estandariza y el juicio sobre qué es uso
pertenece a la etapa de etiqueta.

### 1.4 Preguntas de investigación

| # | Pregunta | Dónde se responde |
|---|---|---|
| P1 | ¿Se puede construir una etiqueta de momento de riesgo, sin medida subjetiva, a partir solo de eventos de pantalla? | §5 |
| P2 | ¿Qué tan robusta es la etiqueta a sus parámetros (umbral de sesión, ventana, percentil, días de perfil, regla de 1 app)? | §4.7, §5.6, §5.7 |
| P3 | ¿F y S son patrones distintos? | E7 (§5.6), E8b (§7.4) |
| P4 | ¿Qué modelo y qué features predicen mejor F y S en personas nuevas? | §7 |
| P5 | ¿Sirve un dataset público de otra época y país (MPUD) para preentrenar? | E13 (§7.3) |
| P6 | ¿Con qué corte y reglas se logra una carga de intervenciones aceptable con precisión útil? | §8 |
| P7 | ¿Es viable en el dispositivo? | §9 |

**Objetivo de evaluación.** Evaluar, dejando una persona fuera, dos modelos
—uno que predice uso fragmentado en la hora siguiente y otro que detecta,
con 30 min de anticipación, sesiones que se vuelven sticky— por su AUC
mediano por persona frente a baselines de persistencia y de parte observada,
y por la precisión y la carga de intervenciones en sus cortes operativos.
Meta fijada el 2026-10-19: AUC de cada patrón cerca de 0.72 (por encima de
0.70) y, en cada persona, el modelo por encima de la persistencia. La parte
por persona se cumple en los dos modelos; F queda en 0.710 y S en 0.953
(mayormente detección).

### 1.5 Alcance

**Dentro:** limpieza, representación común de los dos conjuntos, etiqueta,
perfil, features, experimentos, modelos, calibración, cortes, exportación
Java del modelo, especificación del extractor con fixtures, y el código de
las figuras de la tesis (incluido el análisis de los datos del despliegue).

**Fuera:** la app y su backend, el port del extractor al frontend y el
diseño de la intervención.

---

## 2. Marco teórico

### 2.1 Lin et al. (2017): precedente

Mide la alternancia entre **épocas de uso** (pantalla encendida) y **épocas
de no-uso** (apagada) y deriva umbrales diarios frente a un diagnóstico
clínico de adicción al smartphone (frecuencia, duración y mediana del
no-uso, RMSSD, Similarity Index; AUC de 0.63 a 0.66). La nomenclatura de
"épocas" del proyecto viene de aquí. Excluye el sueño como la época de
no-uso más larga entre las 21:00 y las 12:00. Lo que se conserva son sus
parámetros como features del día y del perfil (§6).

### 2.2 Siebers, Beyens y Valkenburg (2023): F y S

Adolescentes de 13 a 16 años en Países Bajos; logs de 3 semanas y muestreo
de experiencias (ESM) 6 veces al día.

- **Sesión:** de pantalla encendida a apagada; un apagado y encendido a
  menos de 30 s cuentan como una sola sesión.
- **Uso fragmentado:** F = *n*<sub>sesiones</sub> × span / *W*, con span =
  fin de la última sesión − inicio de la primera; F = 0 con menos de dos
  sesiones.
- **Uso sticky:** S = ln(duración de la sesión más larga). El log se
  justifica porque la permanencia es log-normal.
- *W* = 60 min, atado al intervalo de su ESM.

| Hallazgo | Uso en smartUse |
|---|---|
| ICC de 0.16 (F) y 0.27 (S): la mayor parte de la varianza es **dentro de la persona** | Percentil del perfil propio, no poblacional |
| La asociación con la distracción fue dentro de la persona | Etiqueta relativa a la persona |
| Correlación F–S dentro de la persona: 0.18 | Referencia de E7 |
| Efecto en el 77 % (F) y 55 % (S) de los participantes | La elegibilidad no garantiza el efecto en cada persona |
| Ni F ni S se asociaron en promedio a retraso de tareas | Resultados formulados como distracción, no productividad |

smartUse no mide distracción: transfiere la **fórmula** de F y S de
estudios que sí la validaron (Siebers et al.; Yi, Wu y Liu, 2025, con
universitarios y el mismo umbral de 30 s), no umbrales absolutos.

### 2.3 Otras fuentes

| Fuente | Qué aporta |
|---|---|
| Time2Stop (Orzikulova et al., 2024) | Precedente directo de JITAI para sobreuso: inferencia periódica, enfriamiento de 10 min tras cada intervención, recepción como métrica y no como etiqueta, peso reducido a datos de otros usuarios (precedente del valor previo) |
| Zheng y Choe | Escala diaria como predictor de la duración restante de la sesión |
| Nahum-Shani et al. (2018) | Componentes de una JITAI (abajo) |
| Goh y Barabási (2008); Kim y Jo (2016) | Burstiness y memoria: en dinámica humana la memoria entre intervalos es débil, así que un vector fijo de estadísticos basta (no hace falta la secuencia cruda) |
| Pielot et al. (2017) | Mobile Phone Use Dataset (MPUD) |
| Kwon et al. (2013) | SAS-SV, escala usada para la elegibilidad |

| Componente JITAI | smartUse |
|---|---|
| Puntos de decisión | Cada inicio de sesión y cada 10 min dentro de ella |
| Variables de adaptación | Vector de features (pantalla, conteo de apps, perfil, dinámica) |
| Opciones de intervención | Intervención UI/UX por patrón (F o S) o nada |
| Reglas de decisión | Score > corte, sujeto a enfriamiento, episodio y tope diario |
| Resultados proximales | Recepción (la sesión termina) y cambio de F y S en la hora siguiente |

**Burstiness** de *m* intervalos con media μ y desviación σ, *r* = σ/μ, con
la corrección de Kim y Jo para pocos eventos:
B = (√(*m* + 1)·*r* − √(*m* − 1)) / ((√(*m* + 1) − 2)·*r* + √(*m* − 1)).

### 2.4 Métricas

| Métrica | Definición | Uso |
|---|---|---|
| AUC-ROC | Probabilidad de que un positivo al azar reciba mayor score que un negativo | Métrica principal |
| PR-AUC | Área bajo la curva precisión–sensibilidad | Complemento con clase minoritaria |
| Brier | Media de (score − *y*)² | Calibración |
| ECE (10 bins) | Media ponderada de \|score medio − tasa observada\| | Calibración |
| Precisión, sensibilidad, F1 | En un corte dado; F1 = 2PR/(P + R) | Operación |
| Kappa de Cohen | Acuerdo corregido por azar entre dos etiquetas | Robustez de la etiqueta |
| Bootstrap por personas | Se remuestrean personas, 1 000 réplicas, IC 95 % de la mediana | Incertidumbre con 18 personas |

---

## 3. Datos

### 3.1 Panorama

| | MPUD | Datos reales |
|---|---|---|
| Origen | Mobile Phone Use Dataset (Pielot et al., 2017), público | Fase 1 de smartUse (app de recolección) |
| Año y lugar | 2016, España (UTC+2 en verano) | 2026, Guatemala (UTC−6, sin horario de verano) |
| Personas | 215 (203 tras la limpieza) | 19 (18 tras la exclusión) |
| Periodo (UTC) | 2016-04-14 → 2016-07-12 | 2026-10-01 → 2026-10-18 |
| Días por persona | 8–79 (mediana 26) | 2.1–17.1 (mediana 10.2) |
| Unidad | Evento suelto (pantalla, app, notificación) | Época de uso / no-uso |
| Filas | 4 352 340 eventos | 59 528 épocas |
| Rol | Validar aproximaciones (E12, E6) y preentrenar (E13) | Ajuste fino, perfil y **toda** la evaluación |

**Ninguno trae etiqueta**: se construye a partir de las distribuciones
(§5). Los datos de despliegue (fase 3: grupo con la JITAI activa del
2026-10-22 al 2026-11-04, formularios y métricas finales) solo los usa el
código de figuras de `plots/`.

### 3.2 MPUD

215 CSV (uno por persona, 80 columnas). `pipelines/mpud/mpud_extraction.py`
los une en `data/usage/mpud.csv` con los sensores `Screen` (`On`,
`Unlocked`, `Off`), `App` (paquete en primer plano), `Notif` y
`NotifDismissed`, y las columnas `uuid`, `ts_utc` (UTC al milisegundo),
`ts_phone` (reloj del teléfono al segundo), `sensor_id` y `value`.

- Un periodo de pantalla encendida **sin** `Unlocked` es un periodo solo en
  la pantalla de bloqueo.
- Secuencia esperada: `Off` → `On` → (`Unlocked`) → `Off`. Hay 51 930
  marcadores repetidos y 19 562 `Off` → `Unlocked` (desbloqueo sin
  encendido registrado, probablemente por huella). **Regla:** ese `Unlocked`
  inicia el periodo de uso.
- Se ordena por `ts_utc` al milisegundo; con la hora local al segundo
  aparecían eventos del mismo segundo mal ordenados.
- `ts_phone` − `ts_utc` es la zona horaria del teléfono (se espera +2 h).
  109 personas siempre en +2 h; 94 con 228 eventos sueltos fuera de +2 h
  (`ts_raw` salta; `ts` es coherente); 12 con tramos de 1 h o más en otra
  zona (viajes o residencia). Verificado con `eda/timezone_drift_check.py`.

### 3.3 Datos reales

Participantes con consentimiento, capturados por la app de la fase 1 y
guardados por el backend; `data/usage/real_data.csv` es la exportación
definitiva del 2026-10-18. Columnas: `client_record_id`, `user_id`,
`start_ts` (UTC, ms), `distinct_app_count` (paquetes distintos con
`ACTIVITY_RESUMED` en la época), `granularity` (no se usa), `is_use`.

Cada fila es una época que termina donde empieza la siguiente de la misma
persona; **la última época de cada persona no tiene fin** y se descarta.
29 770 épocas de uso y 29 758 de no-uso.

**Semántica de `distinct_app_count`:**

| Apps | Proporción | Interpretación | Tratamiento |
|---|---|---|---|
| 0 | ~31 % | Pantalla encendida sin actividad en primer plano (pantalla de bloqueo, panel de notificaciones) | No es sesión: pasa a no-uso |
| 1–2 | ~42 % | Launcher solo, apertura desde notificación: *checking habits* | Se conserva; núcleo de F |
| 3+ | ~26 % | Uso activo con cambio de apps | Se conserva |

Los paquetes se guardaron como hash: no hay identidad ni categoría de app,
notificaciones, ubicación ni eventos de desbloqueo, keyguard o apagado. F y
S no se afectan: solo usan tiempos de pantalla.

**Persona excluida.** Una persona con unos 2 días de datos y 34 sesiones
(`EXCLUDED_USERS` en `pipelines/real/real_preprocess_1.py`, por
seudónimo): no alcanza para perfil y evaluación. No se excluye por uso
bajo; las reglas de cobertura posteriores (*d* + 2 días) la habrían
excluido igual.

**Tamaño efectivo.** 18 participantes, 209 días de registro (mediana 10.5,
de 4.4 a 17.1). Para generalizar a personas nuevas el tamaño efectivo es
**18**: los 16 281 ejemplos están correlacionados dentro del día y de la
persona.

### 3.4 Diferencias entre conjuntos

| Aspecto | MPUD | Datos reales |
|---|---|---|
| Época y población | 2016, España | 2026, Guatemala |
| Escala | 215 personas, semanas a meses | 19 personas, días a dos semanas |
| Uso de pantalla | `On`, `Unlocked`, `Off` | Solo `is_use` |
| Encendido sin desbloqueo | Periodo sin `Unlocked` | Época de uso con 0 apps |
| Apps | Paquete de cada app | Solo el número de paquetes distintos por época |
| Notificaciones | Sí | No |

Para usarlos juntos, los dos se llevan al **mismo registro de sesiones**
(inicio, fin, número de apps), quitando a MPUD la información que los datos
reales no tienen.

---

## 4. Limpieza: del dato crudo al registro de sesiones

Cada fase es un script independiente que lee la salida anterior y escribe
un archivo nuevo en `data/processed/`; los archivos de `data/usage/` no se
modifican. Los análisis trabajan en hora local; las duraciones, en UTC.

### 4.1 Pipeline

```
data/mpud_raw/*.csv ──mpud_extraction──► data/usage/mpud.csv        data/usage/real_data.csv
                                             │                                   │
                     mpud_preprocess_1 (zona horaria)          real_preprocess_1 (zona horaria)
                     mpud_preprocess_2 (épocas)                real_preprocess_2 (épocas)
                     mpud_preprocess_3 (sesiones)              real_preprocess_3 (sesiones)
                                             └──────────────┬────────────────────┘
                                                registro de sesiones común
                                                            │
                     pipelines/labels/build_windows  (fase 4: puntos de decisión, grilla, máscaras, cobertura)
                     pipelines/labels/build_features (fase 5: perfil, features y etiquetas; + dinámica, + extra)
                                                            │
                                          experiments/ y notebooks/ (§7, §8)
```

### 4.2 Reglas de limpieza

Siguen a Siebers et al.: cuenta como uso el tiempo de una app en primer
plano con la pantalla encendida, sin la app del propio estudio. Se aplican
igual en entrenamiento y en el dispositivo.

| # | Paso | Datos de recolección | Dispositivo |
|---|---|---|---|
| 1 | Reconstruir sesiones con eventos de pantalla | Fase 3 | Igual |
| 2 | Unir sesiones a menos de 30 s | Fase 3; E5 lo confirma | Igual |
| 3 | Excluir eventos de smartUse | Impacto bajo (corría en segundo plano); en MPUD se excluye `org.pielot.sns2` | Obligatorio (el overlay alargaría la sesión) |
| 4 | Descontar encendidos sin desbloqueo | Épocas con 0 apps fuera; E12 cuantifica el error | Misma regla (al menos 1 app, launcher incluido) |
| 5 | Sesiones sin cierre | Más de *T*<sub>max</sub> = 3 h → `no_close`; sus ventanas quedan enmascaradas (sin etiqueta, no 0) | Pasadas 3 h se detiene la inferencia hasta el siguiente cambio de pantalla |
| 6 | No-uso frente a dato faltante | No-uso de más de 10 h = dato faltante; ventanas que se solapan, censuradas | Heartbeat de la app |
| 7 | Dividir sesiones en el borde de la ventana | Fase 4 | Igual |
| 8 | Tope de S | Con *W* = 60 min, tope ln(3 600) | Igual |
| 9 | Cobertura insuficiente | Día suficiente: ≥ 6 horas con algún evento; persona: ≥ *d* + 2 días así | Aviso |

**Caveat principal:** los datos de recolección no distinguen desbloqueos,
keyguard ni apagados del dispositivo, así que los pasos 4 a 6 son
aproximaciones.

### 4.3 Fase 1: zona horaria

**MPUD** (`mpud_preprocess_1.py`): zona por evento = `ts_phone` − `ts_utc`,
redondeada a 15 min. Se eliminan las 12 personas con un tramo de 1 h o más
fuera de +2 h; en las demás, los 228 eventos fuera de +2 h se reconstruyen
(`ts_utc` = `ts_phone` − 2 h, `ts_rebuilt` = `True`). Salida: 203 personas,
4 099 087 eventos.

**Reales** (`real_preprocess_1.py`): `start_utc` y `start_local` = UTC −
6 h; exclusión de la persona con ~2 días. Salida: 18 personas, 59 430
épocas.

En hora local, el mínimo de encendidos cae a las 05:00 (MPUD) y a las 03:00
(reales): las horas quedan bien.

### 4.4 Marcadores repetidos

Había una fase de duplicados (marcadores iguales a menos de 1.5 s) y otra
de la carrera desbloqueo/encendido de MPUD. **Se eliminaron:** la fase de
épocas fusiona todos los inicios consecutivos del mismo tipo y ya las
absorbe (las salidas son idénticas). Los scripts de `eda/` documentan el
diagnóstico:

- MPUD uso → uso: 1 194 intervalos, mediana 39 min. La actividad se parece
  al uso normal y luego se detiene (apps por minuto unas 40 veces menores):
  son apagados no registrados. Es el insumo de E6.
- MPUD no-uso → no-uso: en el 73 % se publica una notificación dentro; una
  notificación enciende la pantalla sin `On` registrado y se apaga sola.
- Reales: 28 uso → uso (mediana 36 min) en 13 personas.

### 4.5 Fase 2: épocas

Esquema común: `user_id`, `start_utc`, `start_local`, `is_use`,
`distinct_app_count`, `merged_markers`, `duration_s` (+ `client_record_id`
en reales; + `unlocked` en MPUD). Inicios consecutivos del mismo tipo se
fusionan (se conserva el primero); la duración llega hasta el inicio de la
siguiente época.

| | Reales (`real_preprocess_2.py`) | MPUD (`mpud_preprocess_2.py`) |
|---|---|---|
| Apps de una época fusionada | Máximo de los marcadores | Paquetes distintos dentro de la época |
| Salida | 59 359 épocas (29 675 de uso), 18 personas | 716 302 épocas (358 122 de uso), 203 personas |
| Mediana de uso / no-uso | 19 s / 78 s | 35 s / 149 s |

Reglas propias de MPUD: inicio de uso = `On`, o `Unlocked` justo después de
`Off`; inicio de no-uso = `Off`. No cuentan como app el registrador,
`com.android.systemui`, keyguard y pantallas de bloqueo, ni teclados; sí
cuentan launchers, `android` y `null`.

### 4.6 Fase 3: sesiones

`real_preprocess_3.py` y `mpud_preprocess_3.py`, en este orden:

1. **Regla de sesión:** una época de uso cuenta solo con al menos 1 app; con
   0 apps pasa a no-uso. En MPUD se usa la misma regla (**no** `unlocked`)
   para que las sesiones sean las que los datos reales pueden ver.
2. **Unión a menos de 30 s.**

La regla 1 va primero: si se uniera primero, un encendido corto con 0 apps
entre dos sesiones las uniría.

Columnas: `user_id`, `start_utc`, `start_local`, `end_utc`, `duration_s`,
`n_epochs`, `app_count_max` (**suma** de las apps de sus épocas: un
máximo, porque no se pueden identificar apps repetidas), `merged_markers`,
`unlocked` (solo MPUD), `no_close` (`duration_s` > 3 h).

| Con 30 s | Reales | MPUD |
|---|---|---|
| Épocas de uso con 0 apps → no-uso | 9 315 (31 %) | 133 598 (37 %) |
| **Sesiones** | **15 885** | **179 961** |
| Mediana de duración | 64 s | 81 s |
| `no_close` | 41 | 1 319 |

### 4.7 E12: error de la regla de al menos 1 app

Los datos reales no tienen desbloqueo; en MPUD se compara la regla de 1 app
con el desbloqueo real sobre las mismas ventanas de 60 min (105 589
ventanas, 203 personas). Descriptivo, sin criterio previo.

| | F | S |
|---|---|---|
| Error absoluto mediano | 0 | 0 |
| Spearman, mediana por persona | 0.96 | 0.96 |
| Marca sobre el p80: acuerdo / kappa | 95 % / 0.84 | 94 % / 0.82 |

Por época la regla se equivoca en los dos sentidos (10 % de las épocas con
0 apps tenía desbloqueo; 16 % de las de 1–2 apps no), pero a nivel de
ventana los errores se compensan: kappa 0.84 en "F o S", con la misma
prevalencia (30.7 %).

### 4.8 E6: umbral de sesiones sin cierre (*T*<sub>max</sub>)

**Criterio previo:** verdad aproximada en MPUD (sin cierre si el hueco más
largo sin apps dura > 30 min y más de la mitad de la sesión); sensibilidad
≥ 80 %; enmascaramiento por persona en los reales con mediana ≤ 5 % y p90
≤ 15 %.

| *T*<sub>max</sub> | Sensibilidad | Precisión | Enmascarado por persona (mediana / p90) |
|---|---|---|---|
| 2 h | 47 % | 78 % | 3.7 % / 26.6 % |
| **3 h** | **40 %** | **86 %** | **3.1 % / 22.4 %** |
| 4 h | 36 % | 91 % | 1.1 % / 20.2 % |

**Ningún candidato cumple:** el 53 % de las sesiones sin cierre dura 2 h o
menos (el 80 % era inalcanzable), y el p90 lo empujan 2–3 personas con la
pantalla encendida durante horas. Se mantiene 3 h sin relajar el criterio;
las sesiones sin cierre de ≤ 2 h quedan como limitación.

---

## 5. Ventanas, etiqueta y perfil

Código: `smartuse/windows.py`, `smartuse/coverage.py`,
`smartuse/profile.py`, `pipelines/labels/build_windows.py`,
`pipelines/labels/build_features.py`.

### 5.1 Métricas de ventana

Para una ventana [*a*, *a* + *W*), con las sesiones recortadas a ella:

- *n* = sesiones que se solapan con la ventana;
- span = fin de la última recortada − inicio de la primera recortada;
- **F** = *n* · span / *W* si *n* ≥ 2; si no, 0;
- **S** = ln(max(sesión recortada más larga, 1 s)); 0 si no hay sesiones.

Los intervalos son semiabiertos y recortar fija el tope de S en
ln(3 600) ≈ 8.19. *W* = 60 min por la validez de Siebers.

### 5.2 Puntos de decisión y grilla

| Modo | Instantes *t* | Uso |
|---|---|---|
| `points` | Cada inicio de sesión y luego cada *k* = 10 min dentro de ella, mientras *t* < fin y *t* < inicio + 3 h | Entrenamiento, perfil, evaluación |
| `grid` | Ventanas contiguas de *W* alineadas a la hora local | E4, E5, E7, E12 (comparan registros sobre las mismas ventanas) |

*k* = 10 min coincide con el enfriamiento y cumple *k* ≤ *h* = 60 min, así
que todo el tiempo de uso queda bajo el horizonte de alguna predicción. Un
encendido a menos de 30 s del último apagado es la misma sesión: no hay
punto nuevo. Con la pantalla apagada no se predice.

### 5.3 Máscaras y cobertura

| Marca | Definición | Efecto |
|---|---|---|
| `masked` | La ventana se solapa con una sesión `no_close` | Sin etiqueta; fuera de entrenamiento, evaluación y perfil |
| `censored` | La ventana empieza antes de la primera sesión, pasa del fin de los datos o se solapa con un dato faltante | Igual |
| `day_ok` | El punto cae en un día local con ≥ 6 horas de reloj con algún inicio de época | Días insuficientes fuera |
| Persona | ≥ *d* + 2 días utilizables | Si no, excluida |

| | Reales, `points` | Reales, `grid` | MPUD, `points` | MPUD, `grid` |
|---|---|---|---|---|
| Ventanas | 22 010 | 5 040 | 249 672 | 120 247 |
| **Utilizables** | **20 842** | **4 641** | **220 130** | **99 523** |
| Con F > 0 | 90 % | 63 % | 84 % | 43 % |

### 5.4 La etiqueta

En cada punto de decisión *t*, con el perfil congelado de la persona:

- `y_f` = 1[F(ventana de F) > *P*<sub>80</sub>(F de la persona)]
- `y_s` = 1[S(ventana de S) > *P*<sub>80</sub>(S de la persona)]
- `y` = `y_f` ∨ `y_s` (se conserva como resumen; **la decisión usa `y_f` e
  `y_s` por separado**, ver E8b)

La comparación es estricta: un empate en el percentil nunca es positivo.

**Ventanas de la etiqueta.** Con desplazamiento *b*, la etiqueta en *t*
mira [*t* − *b*, *t* + 60 − *b*):

| Patrón | *b* | Ventana | Naturaleza |
|---|---|---|---|
| F | 0 | [*t*, *t* + 60 min) | Predicción enteramente futura |
| S | 30 | [*t* − 30, *t* + 30 min) | Detección + anticipación de 30 min |

**Por qué el percentil es de la persona:** en Siebers la mayor parte de la
varianza es dentro de la persona y el efecto en la distracción fue dentro
de la persona; un percentil poblacional reintroduciría el problema de los
cortes de Lin.

**Prevalencia** (datos reales, 16 281 ejemplos, 18 personas): `y` 43.9 %,
`y_f` 26.5 %, `y_s` 17.2 % (con *b* = 30). En MPUD (193 980 ejemplos,
196 personas): `y` 40.3 %. La prevalencia no es la tasa de intervención:
eso lo decide el corte (§8).

### 5.5 El perfil

Dos perfiles por persona, con los mismos días, porque F y S usan ventanas
de etiqueta distintas. Se calculan sobre las **ventanas de los puntos de
decisión utilizables** (la misma población que la etiqueta; "perfil B").

- `pf`, `ps`: percentil 80 de F y de S (umbrales de la etiqueta).
- `f_q`, `s_q`: cuantiles 0.05…0.95 (19 valores); `f_median`, `s_median`.
- Parámetros de Lin del día (frecuencia, uso total, mediana del log de la
  duración, no-uso total, mediana del log del no-uso) y `sessions_by_hour`
  (24 valores): mediana entre los días del perfil.

**Días y congelamiento:** los **primeros 3 días locales con al menos un
punto utilizable** (no días calendario). Se congela al terminar el tercero
(con la ventana del último punto completa) y no se actualiza más. En
entrenamiento, esos días no son ejemplos.

**Valor previo poblacional** (arranque en frío): *P* = *w* ·
*P*<sub>propio</sub> + (1 − *w*) · *P*<sub>previo</sub>, *w* = *n* / (*n* +
*n*<sub>0</sub>), *n* = puntos propios, *n*<sub>0</sub> = 71 (F) y 247 (S)
(E2). Los valores previos están en `production.json`
(`population_prior`). Con *d* = 3 se usa **solo el perfil propio** (E1b); el
previo queda para antes del día *d* o si una persona pierde días por
cobertura.

### 5.6 Experimentos de la etiqueta (sin perfil)

Datos reales, ventanas `grid` de la hora local; marca "sobre el percentil
80 de la persona" calculado sobre todas sus ventanas.

**E5, umbral de sesión.** Criterio: confirmar 30 s si el kappa frente a
30 s es ≥ 0.80 con 0, 10, 60 y 90 s.

| Umbral | Sesiones | Prevalencia | Kappa con 30 s |
|---|---|---|---|
| 0 s | 20 360 | 36.9 % | 0.88 |
| 10 s | 18 163 | 37.0 % | 0.91 |
| **30 s** | **15 885** | **36.8 %** | — |
| 60 s | 13 370 | 37.4 % | 0.88 |
| 90 s | 11 596 | 37.1 % | 0.83 |

**Cumple.** El número de sesiones cambia mucho; la etiqueta casi no.

**E7, solapamiento de F y S.** Criterio: mediana por persona de
Spearman(F, S) ≤ 0.5. Resultado: **0.03** (Siebers: 0.18); de los
positivos, 46 % solo F, 48 % solo S, 7 % ambos. **Cumple.**

**E4, ventana *W*.** Solo reporte: prevalencia 37.0 % con 60 min; con
30 min, 35.7 % por ventana y 49.5 % en "alguna de las dos mitades" (kappa
0.70 con 60 min). *W* = 60 queda fijado por validez.

### 5.7 Experimentos del perfil

Datos reales, *k* = 10 min, perfil B, etiqueta en cada punto utilizable.

**E3, percentil *p*.** Una primera corrida con el perfil sobre la grilla
dio 66 % de prevalencia: los puntos de decisión ocurren durante el uso y su
hora futura es más activa que la hora típica (error de diseño, corregido
con el perfil B). Criterio de la segunda corrida: F 20 % ± 3, S 20 % ± 3,
"F o S" 36 % ± 4.

| *p* | F positiva | S positiva | F o S | Kappa de estabilidad |
|---|---|---|---|---|
| 70 | 29.5 % | 29.9 % | 57.5 % | 0.78 |
| **80** | **19.5 %** | **19.9 %** | **39.2 %** | 0.73 |
| 90 | 9.8 % | 9.6 % | 19.4 % | 0.67 |

**Cumple: *p* = 80.** En los puntos de decisión F y S casi nunca coinciden
(0.5 % de los positivos).

**E1, días de perfil (kappa).** Criterio: el menor *d* ≤ 3 cuya mejora de
kappa frente al perfil de todos los días, al pasar a *d* + 1, sea < 0.02.

| *d* | 1 | 2 | 3 | 4 | 5 |
|---|---|---|---|---|---|
| Kappa | 0.59 | 0.72 | 0.81 | 0.86 | 0.88 |
| Error relativo del percentil de F | 21 % | 16 % | 11 % | 9.8 % | 8.4 % |

**No cumple** (mejoras 0.13, 0.10, 0.04). S se estabiliza rápido; F
necesita días. *d* = 3 provisional, a revisar con AUC (E1c) y F1 (E1d).

**E1b, perfil combinado con el valor previo.** Criterio: con *d* = 3, no
peor que el propio. Resultado: kappa −0.02, error del percentil de F +0.5
puntos. **No cumple:** solo perfil propio; el previo queda para arranque en
frío.

**E2, peso del previo.** *n*<sub>0</sub> (días) = varianza del percentil con
un solo día / varianza entre personas: 0.74 días en F y 2.6 en S; con una
mediana de 95 puntos por día, **71 y 247 puntos**. Las personas difieren
mucho en F y poco en S.

**E10, día de inicio.** Kappa 0.82 empezando entre semana y 0.82 en fin de
semana: no importa.

**E1c, días de perfil con AUC.** Criterio: confirmar *d* = 3 si su AUC
queda a < 0.01 del perfil con todos los días (no desplegable).

| | AUC 3 días | AUC todos los días | Kappa de las etiquetas |
|---|---|---|---|
| F | 0.710 | 0.711 | 0.725 |
| S | 0.953 | 0.954 | 0.803 |

**Cumple.** El perfil corto cambia las etiquetas de F pero no el orden.

**E1d, días de perfil con F1** (2026-10-21/22). Brazos *d* = 3, 4, 5 con
tablas y modelos reconstruidos y corte de E9b anidado. Criterio: cambiar
*d* si el F1 sube ≥ 0.02 en algún modelo sin caídas > 0.01 de F1 ni AUC.

| Mediana por persona | F *d*=3 | F *d*=4 | F *d*=5 | S *d*=3 | S *d*=4 | S *d*=5 |
|---|---|---|---|---|---|---|
| AUC | 0.712 | 0.697 | 0.711 | 0.944 | 0.950 | 0.951 |
| F1 (corte E9b) | 0.266 | 0.271 | 0.083 | 0.724 | 0.738 | 0.771 |
| F1 (corte de máximo F1) | 0.369 | 0.437 | 0.389 | 0.742 | 0.732 | 0.758 |
| F1 de la persistencia | 0.305 | 0.303 | 0.298 | 0.534 | 0.490 | 0.510 |

**Se mantiene *d* = 3** (con *d* = 4 nadie sube 0.02 y el AUC de F baja;
con *d* = 5 F cae 0.18). **Hallazgo:** el F1 de F lo limita el piso de
precisión de la regla de corte, no el perfil; con ese corte, F quedaba
incluso bajo la persistencia. Esto llevó a cambiar el corte de F (§8.4).

### 5.8 E4b: desplazamiento de la ventana de la etiqueta

Mantener *W* = 60 pero correr la ventana hacia el pasado (*b* = 0, 15,
30). Con *b* > 0 parte de la ventana ya ocurrió, así que parte del AUC es
detección. Baselines: persistencia, persistencia desplazada y **parte
observada** (F o S de [*t* − *b*, *t*)). Criterio: adoptar *b* si suma
≥ 0.01 de AUC y, en cada persona, supera al baseline más fuerte.

| | Modelo | Persistencia | Parte observada | Personas sobre el baseline más fuerte |
|---|---|---|---|---|
| F, *b* = 0 | 0.713 | 0.583 | — | 17/17 |
| F, *b* = 15 | 0.845 | 0.636 | **0.950** | 0/17 |
| F, *b* = 30 | 0.914 | 0.680 | **0.987** | 0/17 |
| S, *b* = 0 | 0.687 | 0.543 | — | 16/16 |
| S, *b* = 15 | 0.786 | 0.601 | 0.762 | 14/16 |
| **S, *b* = 30** | **0.953** | 0.703 | 0.904 | **16/16** |

**Decisión:** F se queda con *b* = 0 (la parte observada sola supera al
modelo: sería detección pura). S pasa a *b* = 30: sobre la parte observada
el modelo suma una mediana de +0.042 por persona. **S es un detector de
sesiones que se vuelven sticky con 30 min de anticipación, no una
predicción desde cero.**

---

## 6. Features

Código: `smartuse/features.py`, `smartuse/features_dynamics.py`,
`smartuse/features_extra.py` (grupos no adoptados).

### 6.1 Principios

1. **Vector fijo, no secuencia cruda** (memoria débil en dinámica humana).
2. **Solo el registro hasta *t*:** sesiones con inicio ≤ *t*, fines
   recortados a *t*; las apps de la sesión en curso **nunca** se leen (en
   los datos de recolección solo se conocen al cerrar).
3. **Log de duraciones e intervalos** (`log1p`); mediana y cuantiles antes
   que media.
4. **Lin como features**, no como umbrales.
5. **Datos insuficientes:** mínimo de eventos por estadístico (2 para la
   mediana; 3 para desviación y burstiness). Debajo, valor 0 y una marca
   (`few2_*`, `few3_*`, `noapps_*`).
6. **Día calendario local** en el bloque diario (con una ventana móvil, el
   no-uso total sería 1 440 − uso).

### 6.2 Convenciones numéricas

Tiempo en segundos UTC (`double`), hora local = UTC + `offset_s`; intervalos
semiabiertos; log natural; cuantiles con interpolación lineal (tipo 7, el
de numpy); desviaciones poblacionales (divide por *n*); vector final en
`float32`. **Sesión en curso:** la última iniciada, si su fin ≥ *t*.

### 6.3 Las 36 features base (modelos F y S)

Cada feature que depende del perfil usa el perfil del modelo (F o S).

**Estado actual**

| Feature | Definición |
|---|---|
| `cur_logdur` | log1p(*t* − inicio de la sesión en curso); 0 si no hay |
| `since_last_logmin` | En sesión: log1p((inicio en curso − fin de la anterior) / 60), 0 si no hay anterior. Fuera de sesión: log1p((*t* − fin de la última) / 60) |
| `no_previous` | 1 si no hay sesión anterior a la actual |

**Ventana de 15 min** [*t* − 900, *t*), sesiones recortadas

| Feature | Definición | Mínimo |
|---|---|---|
| `n_15` | Número de sesiones | — |
| `f_15` | F con *W* = **15 min** | *n* ≥ 2 |
| `logdur_median_15`, `logdur_mean_15` | Mediana y media de log1p(duración recortada) | *n* ≥ 2 |
| `few2_15` | 1 si *n* < 2 | — |
| `logdur_sd_15` | Desviación de log1p(duración recortada) | *n* ≥ 3 |
| `burst_15` | Burstiness de los huecos entre sesiones | 3 huecos |
| `few3_15` | 1 si hay menos de 3 huecos | — |
| `apps_mean_15`, `apps_max_15` | Media y máximo de las apps de las sesiones **cerradas** | al menos una |
| `noapps_15` | 1 si no hay sesiones cerradas en la ventana | — |

**Día local hasta *t*** [medianoche local, *t*), solo sesiones que
empezaron **estrictamente antes** de *t*

| Feature | Definición |
|---|---|
| `freq_day` | Número de sesiones |
| `use_day_logmin` | log1p(suma de duraciones / 60) |
| `logdur_median_day` | Mediana de log1p(duración) si hay 2 o más |
| `nonuse_day_logmin` | log1p(suma de huecos entre sesiones / 60) |
| `lognonuse_median_day` | Mediana de log1p(hueco) si hay 2 huecos o más |
| `since_first_logmin` | log1p((*t* − inicio de la primera sesión del día) / 60) |
| `burst_day` | Burstiness de los huecos del día |
| `max_f60_day`, `max_s60_day` | Máximo de F y S (*W* = 60) entre las **horas de reloj completas** del día |
| `positive_hours_day` | Cuántas de esas horas tienen F > `pf` o S > `ps` |

**Perfil y posición relativa** (F₆₀ y S₆₀ = F y S de [*t* − 3 600, *t*))

| Feature | Definición |
|---|---|
| `prof_pf`, `prof_ps`, `prof_f_median`, `prof_s_median` | Campos del perfil |
| `prof_freq_day`, `prof_logdur_median`, `prof_lognonuse_median` | Parámetros de Lin del perfil |
| `prof_use_day_logmin`, `prof_nonuse_day_logmin` | log1p de los minutos de uso y no-uso del perfil |
| `f60_rank`, `s60_rank` | Interpolación lineal de F₆₀ (S₆₀) sobre los cuantiles `f_q` (`s_q`) → 0.05…0.95, fijada en los extremos |
| `sessions_vs_hour_typical` | Sesiones iniciadas en la última hora − `sessions_by_hour`[hora local de *t*] |

**Poda.** El catálogo original tenía 52 features; la ablación de E8 (caída
de AUC al quitar cada bloque: perfil 0.053, estado actual 0.030, 15 min
0.021, día 0.018, **60 min 0.005**, **hora y contexto 0.004**) llevó a quitar
el bloque de 60 min y el de hora y contexto (`PRUNED_BLOCKS` en
`experiments/variants.py`). F₆₀ y S₆₀ siguen dentro de los rangos y los
máximos del día, pero no son entrada directa; no entran la hora del día, el
fin de semana ni el tipo de punto.

### 6.4 Dinámica: 42 features (solo el modelo F)

Sin perfil. Dos señales por bin: `n` = sesiones **iniciadas** en el bin;
`min` = minutos de pantalla encendida dentro del bin (la sesión en curso
cuenta hasta *t*).

| Escala | Ventana | Bins |
|---|---|---|
| `15m` | [*t* − 15 min, *t*) | 3 de 5 min |
| `1h` | [*t* − 1 h, *t*) | 4 de 15 min |
| `24h` | [*t* − 24 h, *t*), **móvil** | 24 de 1 h |

Por escala y señal, sobre los *B* bins del más viejo al más nuevo:

| Sufijo | Definición |
|---|---|
| `slope` | Pendiente de mínimos cuadrados contra 0..*B* − 1 |
| `accel` | `slope` de la segunda mitad − `slope` de la primera |
| `rel_change` | (último − media de los anteriores) / (media de los anteriores + 1) |
| `com` | Σ *i*·*x*<sub>i</sub> / Σ *x* / (*B* − 1); 0.5 si vacío |
| `peak` | máx / media; 0 si vacío |
| `persist` | Pasos seguidos desde el final con el mismo signo de cambio, con signo |

Entre escalas, por señal, con tasas por minuto y ε = 0.01:
`<s>_var_15m_1h` = ln((σ<sub>15m</sub> + ε)/(σ<sub>1h</sub> + ε)),
`<s>_var_1h_24h`, y `<s>_level_15m_24h` = ln((media<sub>15m</sub> + ε)/(media<sub>24h</sub> + ε)).
Nombres: `<señal>_<escala>_<sufijo>`, por ejemplo `n_1h_slope`.

Motivación: el modelo veía niveles de cada ventana, pero no cómo cambiaba
la actividad. Toda la dinámica suma +0.012 a F y resta 0.010 a S (E8d), así
que solo F la lleva.

### 6.5 Grupos probados y rechazados (E8c)

| Grupo | Idea | F | S |
|---|---|---|---|
| Alternancia (sesiones cortas, cambio corta/larga, memoria, RMSSD) | Ritmo y cambio de régimen | +0.002 | +0.003 |
| Sesión en curso (percentil de la duración, supervivencia) | Puntos dentro de una sesión | −0.000 | −0.009 |
| Relativas al perfil | Escalas propias | +0.009 | −0.005 |
| Tendencia (5 min, aceleración, desde la última sesión larga) | Cambios recientes | +0.005 | +0.003 |
| Perfil por hora | Riesgo típico por hora | +0.009 | +0.002 |
| Todos | — | +0.009 | +0.001 |

Ninguno llega a +0.01: con solo tiempos de pantalla y conteos de apps,
~0.69–0.70 parece el techo de información de los datos (antes de la
dinámica).

### 6.6 Paridad

La función de features recibe el registro y *t*, recorta los fines a *t*,
ignora las sesiones posteriores y nunca lee las apps de la sesión en curso.
Una prueba verifica que el vector con el registro completo es idéntico al
del registro cortado en *t*.

---

## 7. Modelos y evaluación

Código: `experiments/modeling.py`, `experiments/tabular.py`,
`experiments/lstm.py`, `experiments/variants.py`,
`experiments/calibration.py`, `experiments/train_final.py`,
`smartuse/model.py`; notebooks en `notebooks/`.

### 7.1 Protocolo

| Elemento | Decisión |
|---|---|
| Partición | **Dejando una persona fuera** (LOSO): 18 pliegues |
| Validación interna | En cada pliegue, 3 personas de entrenamiento para la parada temprana; en MPUD, 20 personas |
| Métrica principal | **Mediana del AUC por persona** |
| Incertidumbre | IC 95 % de la mediana por bootstrap de personas |
| Complementos | AUC agrupado, PR-AUC, Brier, AUC de inicio (puntos cuya última hora fue negativa) |
| MPUD | Nunca entra en la evaluación |
| Semillas | Desde E8b, 5 semillas por variante y se compara la media (dos corridas iguales difieren ~0.015) |
| Regla de adopción | +0.01 de AUC o más, fijado antes |

La mediana por persona se prefiere al AUC global porque este mezcla el
orden entre personas; una JITAI personal solo necesita ordenar los momentos
dentro de cada persona. Personas con AUC definido: 17 en F y 16 en S (las
demás no tienen las dos clases).

**Baselines:** persistencia (la última hora sobre el perfil, por patrón),
persistencia desplazada y parte observada (E4b), siempre positivo (F1).

### 7.2 Modelos candidatos

| Modelo | Preentrenamiento (MPUD) | Ajuste fino (reales) |
|---|---|---|
| Regresión logística L2 (SGD) | Tasa 0.01 | Mismos pesos, tasa 0.001 |
| XGBoost, profundidad 4 (`subsample` y `colsample_bytree` 0.8, parada temprana a 50) | Tasa 0.1 | Sigue sumando árboles, tasa 0.02 |
| LSTM (1 capa, 32 unidades) + MLP, sobre las sesiones de 24 h + perfil y hora | Adam 3·10⁻³ | Adam 3·10⁻⁴ |

### 7.3 E8 y E13: modelo y preentrenamiento

Criterios: todo modelo debe superar a la persistencia; MPUD se queda si
suma ≥ 0.01 (E13); XGBoost sobre LR si la supera por ≥ 0.01; LSTM solo si
supera al mejor tabular por ≥ 0.01. Etiqueta `y`, 52 features.

| | AUC | IC 95 % | PR-AUC | Brier |
|---|---|---|---|---|
| Persistencia | 0.567 | 0.532–0.577 | 0.495 | 0.396 |
| LR, solo reales / MPUD + ajuste | 0.530 / 0.581 | — / 0.542–0.593 | 0.495 / 0.541 | 0.262 / 0.243 |
| XGBoost, solo reales | 0.547 | 0.500–0.615 | 0.458 | 0.248 |
| **XGBoost, MPUD + ajuste** | **0.606** | 0.532–0.626 | 0.551 | 0.239 |
| LSTM, solo reales / MPUD + ajuste | 0.510 / 0.547 | — / 0.502–0.585 | 0.434 / 0.522 | 0.253 / 0.242 |

- **E13:** MPUD se queda (+0.051 LR, +0.059 XGBoost, +0.037 LSTM). **Sin
  MPUD, ningún modelo supera a la persistencia.**
- **E8:** XGBoost + MPUD. La LSTM queda bajo la persistencia, como se
  esperaba con memoria débil.

### 7.4 E8b: dos modelos

El score único separa mal cada patrón (AUC 0.535 contra `y_f`, 0.551 contra
`y_s`). Variantes con 5 semillas:

| | 52 features | 36 podadas |
|---|---|---|
| Referencia: modelo único contra `y` | 0.585 | 0.594 |
| Dos modelos combinados contra `y` | 0.574 | 0.574 |
| **Modelo de F contra `y_f`** | **0.705** | 0.693 |
| **Modelo de S contra `y_s`** | **0.689** | 0.689 |

Según el criterio ninguna variante se adopta, pero el hallazgo es
estructural: cada patrón es bastante predecible por separado (~0.70); lo
difícil es la etiqueta "o". `y_f` e `y_s` tienen correlación −0.26 y
coinciden en el 0.3 % de los puntos: son momentos opuestos (muchas sesiones
cortas frente a una larga). **Decisión del autor:** dos scores, uno por
patrón, cada uno con su corte; la intervención sabe qué patrón la disparó.
Nueva referencia: dos XGBoost + MPUD con las 36 podadas.

### 7.5 E8c y E8d: más información

E8c (grupos adicionales) no llega a +0.01 en ningún modelo (§6.5). E8d
(dinámica, §6.4):

| Configuración | F (`y_f`) | S (`y_s`) |
|---|---|---|
| Referencia (36) | 0.693 | 0.689 |
| + 24 h | 0.701 | 0.674 |
| **+ toda la dinámica (42)** | **0.705 (+0.012)** | 0.679 (−0.010) |

La dinámica se adopta en F. Con 5 semillas promediadas, F llega a 0.713 y
supera a la persistencia en las 17 personas. La meta de 0.72 no se alcanza
con *b* = 0, lo que motivó E4b (§5.8).

### 7.6 Ensamble fusionado y calibración

**Ensamble fusionado de 5 semillas.** La salida de XGBoost es
sigmoide(margen) y el margen es la suma de los árboles; promediar los
márgenes de 5 semillas equivale a un solo modelo con todos los árboles y
las hojas divididas por 5. Score = sigmoide(media de los márgenes).

| | AUC ensamble (LOSO) | IC 95 % | Semillas sueltas |
|---|---|---|---|
| F | 0.710 | 0.676–0.729 | 0.693–0.715 |
| S | 0.953 | 0.923–0.970 | 0.946–0.954 |

**Calibración.** Criterio: probar Platt e isotónica, ajustadas LOSO;
adoptar la de menor Brier si baja ≥ 0.002. Una calibración monótona no
cambia el AUC; sirve para leer el score como probabilidad.

| | Brier | ECE |
|---|---|---|
| F sin calibrar | 0.1629 | 0.052 |
| **F con Platt** | **0.1595** | **0.014** |
| F isotónica | 0.1603 | 0.021 |
| **S sin calibrar** | **0.0601** | **0.006** |
| S con Platt | 0.0605 | 0.007 |

F con Platt: *p*' = sigmoide(1.248 · logit(*p*) + 0.625). S ya está
calibrado.

### 7.7 Entrenamiento final

`experiments/train_final.py`: por patrón y semilla (0–4), preentrenamiento
en MPUD y ajuste fino con los datos reales. Validación de la parada
temprana: la persona con más puntos y 2 al azar del tercio medio; las otras
15 entrenan. Árboles por semilla (incluidos los de MPUD): F 187, 186, 155,
177, 216 (921); S 98, 174, 251, 247, 228 (998).

### 7.8 F1 dejando una persona fuera

`experiments/f1_loso.py`, scores LOSO del ensamble, 18 personas:

| Patrón | Corte | F1 mediana por persona | F1 agrupado | Precisión agrupada | Sensibilidad agrupada |
|---|---|---|---|---|---|
| F | **Producción (0.302, máximo F1)** | **0.410** | **0.545** | 0.490 | 0.615 |
| F | Máximo F1 anidado | 0.400 | 0.544 | 0.486 | 0.616 |
| F | Regla de E9b anidada | 0.307 | 0.468 | 0.618 | 0.377 |
| F | Persistencia | 0.351 | 0.491 | 0.552 | 0.443 |
| F | Siempre positivo | 0.366 | 0.419 | 0.265 | 1.000 |
| S | **Producción (0.242, regla de E9b)** | **0.747** | **0.742** | 0.677 | 0.821 |
| S | Máximo F1 anidado | 0.735 | 0.752 | 0.731 | 0.774 |
| S | Persistencia | 0.550 | 0.546 | 0.571 | 0.524 |
| S | Siempre positivo | 0.321 | 0.293 | 0.171 | 1.000 |

El corte de F elegido sobre los scores agrupados casi no infla el resultado
frente al corte anidado (0.545 frente a 0.544). En F, el F1 por persona
supera por poco a "siempre positivo" y a la persistencia; en F1 agrupado la
ventaja es mayor. S supera claramente a todos los baselines.

---

## 8. Operación: regla de decisión y cortes

Código: `experiments/e9_cutoff.py`, `experiments/e9b.py`,
`experiments/cutoff_f1.py`, `experiments/f1_loso.py`.

### 8.1 Regla de decisión

En cada punto de decisión:

1. Se calculan *p*<sub>F</sub> y *p*<sub>S</sub>.
2. F dispara si *p*<sub>F</sub> > *c*<sub>F</sub>; S si *p*<sub>S</sub> >
   *c*<sub>S</sub>.
3. Se interviene si lo permiten:
   - **enfriamiento** de 10 min entre intervenciones de cualquier patrón;
   - **regla de episodio:** máximo una intervención por patrón cada 60 min;
   - **tope diario:** 12 por persona y día local.
4. Si los dos disparan, interviene el de mayor margen sobre su corte (*p* −
   *c*); empate → F.
5. Los scores bloqueados por las reglas se **registran** sin mostrarse.
6. Pasadas 3 h dentro de una sesión no hay inferencia hasta el siguiente
   cambio de pantalla.

### 8.2 E9: corte del score único (reemplazado)

Sin máximo de intervenciones fijado antes. Con 2–7 intervenciones por día,
54–61 % de precisión frente a 44 % del azar, pero carga muy desigual (p90
4–8 veces la mediana). Se reemplazó por E9b: la unidad que conviene limitar
es el episodio (racha de puntos positivos con menos de 60 min entre
puntos).

### 8.3 E9b: corte por patrón con regla de episodio

Reglas fijadas antes: regla de episodio + enfriamiento; **piso de
precisión** de 50 % por patrón; **techo de carga** de 12 por día en total
(6 por patrón); se elige el menor corte que cumple piso y techo.

| Modelo | ¿Cumple? | Precisión | Sensibilidad por episodio | Por día (media / p90) |
|---|---|---|---|---|
| F (*b* = 0) | Sí | 51.3 % | 23.9 % | 2.4 / 6 |
| S (*b* = 0) | **No** (máx. 39.7 %) | 39.7 % | 42.8 % | 3.5 / 7 |
| S (*b* = 15) | Sí | 51.0 % | 79.5 % | 5.0 / 9 |
| **S (*b* = 30)** | **Sí** | 52.0 % | 90.8 % | 5.9 / 10 |

En S (*b* = 30), subir el corte casi no baja la cobertura (con precisión
71 %, 92.5 % de los episodios y 4.2 por día). Se aplica la regla sin
cambios. Con los scores finales (ensamble, F con Platt), la regla da
*c*<sub>S</sub> = **0.2416** (precisión 51.9 %, 91.0 % de los episodios, 6.0
por día).

### 8.4 Corte de F por máximo F1 (2026-10-22)

E1d mostró que el F1 de F lo limita el piso de precisión de E9b. Se cambió
el criterio del corte de F al **máximo F1 agrupado por punto** sobre los
scores LOSO del ensamble (16 281 puntos, 18 personas), con búsqueda
exhaustiva (`experiments/cutoff_f1.py`). El corte de S no cambia.

**Corte de F: 0.3024** en la escala de Platt (0.2367 sin calibrar); con la
regla de E9b era 0.4463.

| F, *d* = 3 | Regla de E9b (antes) | Máximo F1 (producción) |
|---|---|---|
| Corte (Platt) | 0.446 | **0.302** |
| F1 agrupado | 0.487 | 0.545 |
| Precisión / sensibilidad agrupadas | 0.613 / 0.403 | 0.490 / 0.615 |
| F1, mediana por persona | 0.333 | 0.410 |
| Puntos donde dispara | 17.4 % | 33.2 % |

**Costo en la simulación de intervenciones de E9b:**

| | Antes | Ahora |
|---|---|---|
| F: precisión de las intervenciones | 0.49 | 0.35 |
| F: sensibilidad por episodio | 0.24 | 0.36 |
| F: por persona-día (media / p90) | 2.5 / 6 | 5.2 / 10 |
| F + S, por persona-día (media / p90) | 8.5 / 14 | 11.1 / 17 |

Se acepta conscientemente que F no cumpla el piso de 0.50 a cambio de
detectar más episodios; el tope de 12 por día sigue limitando los días más
cargados.

### 8.5 Resumen operativo

| Elemento | Valor |
|---|---|
| Corte F | 0.3024 (escala de Platt) |
| Corte S | 0.2416 (sin calibrar) |
| Enfriamiento | 10 min, compartido |
| Regla de episodio | 1 por patrón cada 60 min |
| Tope diario | 12 por persona y día |
| Carga esperada antes del tope | F ~5.2/día, S ~6.0/día, juntos ~11.1 (p90 17) |
| Precisión esperada | F ~0.35, S ~0.52 |
| Episodios alcanzados | F ~36 %, S ~91 % |

---

## 9. Extractor en el dispositivo y exportación

Esta sección es la especificación para portar el extractor (Java o
Kotlin). La implementación de referencia es el código Python:
`pipelines/real/real_preprocess_{2,3}.py` (épocas y sesiones),
`smartuse/windows.py` (ventanas y puntos), `smartuse/coverage.py`,
`smartuse/features.py` y `smartuse/features_dynamics.py` (perfil y
features), `smartuse/model.py` y `export/java/smartuse/SmartUseModel.java`
(modelo).

### 9.1 Entrada

- **Eventos de pantalla:** `SCREEN_INTERACTIVE` / `ACTION_SCREEN_ON` y
  `SCREEN_NON_INTERACTIVE` / `ACTION_SCREEN_OFF`, con su hora real.
- **Apps por época de uso:** número de paquetes distintos con
  `ACTIVITY_RESUMED`, **incluido el launcher** y **excluido smartUse** (y su
  overlay).

### 9.2 Épocas, sesiones y puntos

1. Cada evento de pantalla inicia una época; eventos seguidos del mismo
   tipo se fusionan (primer inicio, **máximo** de apps).
2. Una época de uso con **0 apps** cuenta como no-uso.
3. Las épocas de uso restantes se unen si el hueco es **< 30 s**; las apps
   de la sesión son la **suma** de sus épocas.
4. La sesión en curso está abierta: su fin es *t* y sus apps no se leen.
5. Sesión de más de 3 h (`no_close`): no se generan más puntos ni se
   infiere hasta el siguiente cambio de pantalla. No es feature; una sesión
   `no_close` ya cerrada cuenta en las features como cualquier otra.
6. Puntos de decisión: *t*<sub>0</sub> = inicio de la sesión, luego
   *t*<sub>0</sub> + 10 min, + 20 min…, mientras *t* < fin y *t* <
   *t*<sub>0</sub> + 3 h.

**Cuándo inferir al desbloquear.** La sesión se define por **al menos un
`ACTIVITY_RESUMED`**, no por el desbloqueo ni por el encendido solo (E12:
parecido, pero no igual). Para mantener la paridad:

1. Guardar la hora de encendido *t*<sub>0</sub>.
2. Al llegar el primer `ACTIVITY_RESUMED` (en general < 1 s), inferir con
   las features **calculadas en *t*<sub>0</sub>**.
3. Programar los puntos en *t*<sub>0</sub> + 10, + 20 min…
4. Sin `ACTIVITY_RESUMED` antes del apagado: sin sesión ni inferencia.
5. Encendido a < 30 s del último apagado: misma sesión, sin inferencia de
   desbloqueo; sigue la grilla original.

Los eventos de keyguard, apagado del dispositivo y heartbeat se registran
para validar las aproximaciones y avisar de cobertura baja, pero **no** son
features.

### 9.3 Perfiles en el dispositivo

| Días | Qué pasa |
|---|---|
| 1 a 3 | **Sin intervención.** Se registra el uso y se construyen los perfiles |
| Fin del día 3 | **Congelamiento** de los dos perfiles (F con ventanas *b* = 0; S con *b* = 30) |
| Desde el día 4 | JITAI activa |

Un punto es utilizable para el perfil si su ventana no se solapa con una
sesión `no_close` ni con un dato faltante (no-uso > 10 h), no se sale de
los datos y el punto cae en un día suficiente (≥ 6 horas de reloj con algún
inicio de época). El perfil se calcula como en §5.5 y las features como en
§6.

### 9.4 Orden de los vectores

**S (36):** `cur_logdur, since_last_logmin, no_previous, n_15, f_15,
logdur_median_15, logdur_mean_15, few2_15, logdur_sd_15, burst_15, few3_15,
apps_mean_15, apps_max_15, noapps_15, freq_day, use_day_logmin,
logdur_median_day, nonuse_day_logmin, lognonuse_median_day,
since_first_logmin, burst_day, max_f60_day, max_s60_day, positive_hours_day,
prof_pf, prof_ps, prof_f_median, prof_s_median, prof_freq_day,
prof_use_day_logmin, prof_logdur_median, prof_nonuse_day_logmin,
prof_lognonuse_median, f60_rank, s60_rank, sessions_vs_hour_typical`

**F (78):** las mismas 36 con el perfil F, seguidas de las 42 de dinámica:
escala `15m`, luego `1h`, luego `24h`; dentro de cada escala, la señal `n` y
luego `min`; dentro de cada señal, `slope, accel, rel_change, com, peak,
persist`; al final `n_var_15m_1h, n_var_1h_24h, n_level_15m_24h,
min_var_15m_1h, min_var_1h_24h, min_level_15m_24h`.

El orden autoritativo es la línea `features` de cada archivo de modelo; la
clase Java rechaza un vector de otro largo.

### 9.5 Score

Para cada una de las 5 semillas, margen = `base_margin` + suma de las hojas
(en cada nodo, `x < umbral` va a `yes`, si no a `no`; `NaN` va a
`missing`); se promedian los márgenes; *p* = sigmoide(margen promedio). F
aplica Platt con *p* recortado a [10⁻⁶, 1 − 10⁻⁶]. F dispara si *p* >
0.3024; S si *p* > 0.2416. Las reglas de ejecución de §8.1 están en
`production.json` (`rules`).

### 9.6 Formato del modelo y paridad

- `export/models/{F,S}.model` (texto): versión, patrón, lista ordenada de
  features, calibrador, corte, número de semillas y los árboles de cada
  semilla con su `base_margin`. Lo escribe `experiments/export.py`.
- `export/java/smartuse/SmartUseModel.java`: lee el archivo y calcula el
  score; sin dependencias. Se eligió recorrer los árboles en Java en lugar
  de TFLite: es exacto y de latencia despreciable.
- `experiments/export_models.py` genera los archivos, 3 003 vectores de
  paridad por modelo (`export/parity/`, de MPUD, con casos extremos) y corre
  `smartuse.ParityCheck`: diferencia máxima Java − Python ≤ 2.2·10⁻⁷, sin
  decisiones distintas.

### 9.7 Fixtures

`export/fixtures/<persona>/`, dos personas de MPUD (datos públicos;
`offset_s` = +2 h), con la entrada y cada resultado intermedio esperado:

| Archivo | Verifica |
|---|---|
| `epochs.csv` | Entrada |
| `sessions.csv` | Sesiones y `no_close` |
| `points_b0.csv`, `points_b30.csv` | Puntos, métricas de la ventana de cada modelo y máscaras |
| `profile_F.json`, `profile_S.json` | Perfiles |
| `vectors_F.csv`, `vectors_S.csv` | Vectores, `expected_score` y `expected_fires` |

Tolerancias: vectores 10⁻⁵, scores 10⁻⁶. `tests/test_fixtures.py`
recalcula todo con Python; un port pasa si reproduce los archivos.

### 9.8 Detalles fáciles de equivocar

1. Las apps de la sesión en curso nunca entran.
2. Apps de sesión = **suma** de sus épocas; apps de una época fusionada =
   **máximo**.
3. Las épocas con 0 apps se descartan **antes** de unir a 30 s.
4. Hay dos perfiles: el `prof_pf` del modelo S es el percentil de F sobre
   las ventanas desplazadas de S.
5. `f_15` usa *W* = 15 min; F₆₀, `max_f60_day` y el perfil, 60 min.
6. La ventana de 24 h de la dinámica es **móvil**; el bloque del día usa el
   **día calendario local**.
7. `max_f60_day` y `max_s60_day` solo miran horas de reloj completas.
8. Los días del perfil son días con puntos utilizables, no días calendario.
9. Cuantiles tipo 7 y desviaciones poblacionales; vector en `float32`.
10. En el desbloqueo que abre una sesión, la sesión recién iniciada cuenta
    en la ventana de 15 min (con duración 0), pero no en el bloque del día,
    en `sessions_vs_hour_typical` ni en la señal `n` de la dinámica.

### 9.9 Evaluación en el despliegue

Las etiquetas se recalculan con el perfil congelado y se usan las mismas
métricas: es la confirmación en personas nuevas. Para la intervención, la
**recepción** (la sesión termina después de la intervención) es una
métrica, no una etiqueta; el efecto se mide como el cambio de F y S en la
ventana siguiente. El código de las figuras del despliegue está en
`plots/` (§13.2).

---

## 10. Registro de experimentos

"¿Cumplió?" se refiere al criterio fijado antes de correrlo.

| Exp. | Pregunta | Resultado clave | ¿Cumplió? | Decisión | Código |
|---|---|---|---|---|---|
| E12 | ¿Cuánto error introduce no tener desbloqueo? | Kappa 0.84, misma prevalencia | Descriptivo | Caveat cuantificado | `experiments/e12_one_app_rule.py` |
| E6 | ¿Qué *T*<sub>max</sub>? | 3 h: sensibilidad 40 %, precisión 86 % | **No** | Se mantiene 3 h | `experiments/e6_t_max.py` |
| E5 | ¿Es robusto el umbral de 30 s? | Kappa 0.83–0.91 | Sí | 30 s | `experiments/e5_session_gap.py` |
| E7 | ¿F y S son distintos? | Spearman mediano 0.03 | Sí | F y S por separado | `experiments/e7_f_s_overlap.py` |
| E4 | ¿Cuánto cambia con *W* = 30? | Kappa 0.70 | Solo reporte | *W* = 60 | `experiments/e4_window_w.py` |
| E3 | ¿Qué percentil? | 19.5 / 19.9 / 39.2 % con *p* = 80 | Sí | *p* = 80, perfil B | `experiments/e3_percentile.py` |
| E1 | ¿Cuántos días de perfil (kappa)? | Mejoras 0.13, 0.10, 0.04 | **No** | *d* = 3 provisional | `experiments/e1_profile_days.py` |
| E1b | ¿Combinar con el valor previo? | Kappa −0.02 | **No** | Solo perfil propio | `experiments/e1b_blended_profile.py` |
| E2 | ¿Cuánto pesa el previo? | *n*<sub>0</sub> = 71 (F), 247 (S) | Estimación | Valores fijados | `experiments/e2_prior_weight.py` |
| E10 | ¿Importa el día de inicio? | 0.82 y 0.82 | Sin efecto | Entre semana por logística | `experiments/e1_profile_days.py` |
| E8 | ¿Qué modelo? | XGBoost + MPUD 0.606; LR 0.581; LSTM 0.547; persistencia 0.567 | Sí | XGBoost profundidad 4 | `notebooks/e8_{lr,xgb,lstm}.ipynb` |
| E13 | ¿Aporta MPUD? | +0.051 LR, +0.059 XGBoost | Sí | MPUD se queda | Mismos notebooks |
| E14 | ¿Un retraso de eventos cambia la predicción? | No corrido | — | Limitación declarada | — |
| E9 | ¿Qué corte (score único)? | 2–7/día con 54–61 % de precisión | Sin criterio previo | Reemplazado por E9b | `experiments/e9_cutoff.py` |
| E8b | ¿Variantes de XGBoost? | Por patrón ~0.70; combinado 0.574 | **No** | Dos modelos (decisión del autor) | `notebooks/e8b_xgb_variants.ipynb` |
| E8c | ¿Grupos de features adicionales? | Máx. +0.009 | **No** | Referencia | `notebooks/e8c_feature_groups.ipynb` |
| E8d | ¿Ayuda la dinámica? | F +0.012; S −0.010 | Sí en F | Dinámica solo en F | `notebooks/e8d_dynamics.ipynb` |
| E4b | ¿Mover la ventana de la etiqueta? | S *b* = 30: 0.953, 16/16 | Sí en S | F *b* = 0; S *b* = 30 | `notebooks/e4b_label_offset.ipynb` |
| E9b | ¿Qué cortes por patrón? | F y S (*b* = 30) cumplen | Sí | Cortes de la regla | `notebooks/e9b_cutoffs*.ipynb`, `experiments/e9b.py` |
| Fase G | ¿Ensamble y calibración? | F Platt (Brier −0.0034); S sin mejora | Sí | F Platt, S sin calibrar | `notebooks/g_calibration.ipynb`, `experiments/train_final.py` |
| E1c | ¿*d* = 3 cuesta AUC? | −0.001 en F y S | Sí | *d* = 3 | `notebooks/e1c_profile_days_auc.ipynb` |
| E1d | ¿Mejora el F1 con *d* = 4 o 5? | *d* = 4: +0.005 / +0.014 | No | *d* = 3 | `experiments/e1d_profile_days_f1.py` |
| Corte de F | ¿Cambiar el criterio del corte de F? | F1 agrupado 0.487 → 0.545 | Decisión del autor | *c*<sub>F</sub> = 0.3024 | `experiments/cutoff_f1.py`, `experiments/f1_loso.py` |
| E11 | Latencia y batería en el dispositivo | Fuera de este repositorio | — | — | — |

Con criterio cumplido: E5, E7, E3, E8, E13, E8d (F), E4b (S), E9b, fase G,
E1c. Con criterio **no** cumplido: E6, E1, E1b, E8b, E8c, E1d; en todos se
mantuvo la opción por defecto sin relajar el criterio, salvo E8b, donde el
hallazgo llevó a los dos modelos.

Orden de ejecución:

```
Datos ─► ventanas ─► E12, E6 ─► E5, E7, E4 ─► cobertura ─► E3, E1, E1b, E2, E10
   ─► E8, E13, E9, E8b, E8c, E8d, E4b, E9b ─► fase G ─► E1c ─► E1d ─► corte de F
```

---

## 11. Decisiones y desviaciones

### 11.1 Decisiones principales

| Decisión | Alternativa | Por qué |
|---|---|---|
| Clasificar momentos, no personas | Clasificar personas (Lin) | Una JITAI necesita saber cuándo intervenir |
| F y S de Siebers; Lin solo como features | Umbrales de Lin | Lin no se transfiere y clasifica personas |
| Sesión = época con ≥ 1 app, también en MPUD | Desbloqueo | Única señal disponible en los datos reales; E12 kappa 0.84 |
| Unión a < 30 s | 0–90 s | Siebers; E5 |
| *T*<sub>max</sub> = 3 h, enmascarar | 2–4 h | E6 no cumplió; precisión 86 % |
| *W* = 60 min; *k* = 10 min | 30 min; 5 min | Validez de Siebers; igual al enfriamiento |
| Percentil 80 de la persona, perfil B | Poblacional; perfil sobre la grilla | ICC bajos; E3 |
| *d* = 3 días, congelado, solo perfil propio | 1–5 días; perfil combinado | E1, E1b, E1c, E1d |
| XGBoost profundidad 4, MPUD + ajuste fino | LR, LSTM; solo reales | E8, E13 |
| **Dos modelos, uno por patrón** | Score único "F o S" | E8b: F y S son momentos opuestos |
| Poda a 36 features | 52 | Ablación < 0.01 por bloque (decisión posterior al resultado, +0.009) |
| Dinámica solo en F | En los dos | E8d |
| F con *b* = 0; S con *b* = 30 | Otros desplazamientos | E4b |
| Ensamble fusionado de 5 semillas | Una semilla | Reduce el ruido entre semillas |
| Platt en F; S sin calibrar | Isotónica | Brier |
| *c*<sub>S</sub> = 0.2416 (regla de E9b) | Cortes más altos | Se aplica la regla fijada antes |
| ***c*<sub>F</sub> = 0.3024 (máximo F1)** | 0.4463 (regla de E9b) | E1d: el piso de precisión limita el F1 de F |
| Enfriamiento de 10 min; episodio de 60 min; tope de 12 | — | Time2Stop; horizonte de la etiqueta; techo de carga |
| Exportar como árboles en Java | TFLite | Exacto, sin dependencias |

### 11.2 Desviaciones del plan

| Plan | Lo que se hizo | Por qué |
|---|---|---|
| Un score para "F o S" y un corte | Dos modelos con su corte | Score único 0.585; por patrón ~0.70 |
| Las dos etiquetas miran los 60 min siguientes | S mira [*t* − 30, *t* + 30) | S con *b* = 0 no pasa de 39.7 % de precisión; costo: S es sobre todo detección |
| Catálogo de 52 features | 36 podadas (+ 42 de dinámica en F) | Ablación y E8d |
| Un modelo entrenado | Ensamble de 5 semillas | Ruido entre semillas |
| Candidatos LR, Random Forest y LSTM | LR, XGBoost y LSTM | XGBoost permite seguir sumando árboles en el ajuste fino |
| LSTM con barrido del largo de la secuencia | Solo 24 h con hasta 256 sesiones | La LSTM se descartó |
| Evaluación temporal y LOSO | Temporal solo en E8 | El protocolo se fijó en LOSO |
| Corte de F por reglas de uso (E9b) | Máximo F1 | E1d |

### 11.3 Riesgo de optimismo

Sobre las mismas 18 personas se probaron muchas variantes. Cada paso tuvo
criterio previo, pero **no hay personas que no se hayan usado en ninguna
decisión**: los AUC finales (F 0.710, S 0.953) son algo optimistas como
estimación en personas nuevas. El corte de F se validó de forma anidada
(0.544 frente a 0.545), lo que acota ese efecto puntual. La evaluación del
despliegue es la confirmación.

---

## 12. Limitaciones

**Constructo**

1. Sin medida subjetiva: se transfiere la fórmula de F y S de estudios que
   validaron su relación con la distracción, en otras poblaciones
   (adolescentes en Países Bajos, universitarios); ninguna de Guatemala.
2. La etiqueta es relativa: "positivo" significa "sobre el percentil 80
   propio", no "problemático" en términos absolutos.
3. En Siebers el efecto no aparece en todas las personas (77 % F, 55 % S).

**Datos**

1. 18 personas son el tamaño efectivo; los ~16 000 puntos están
   correlacionados.
2. Sin desbloqueo, keyguard ni apagado en los datos reales: la regla de 1
   app y las sesiones sin cierre son aproximaciones.
3. Apps solo como conteo en hash; apps por sesión son un máximo.
4. MPUD es de otra época y país, pero sin MPUD ningún modelo supera a la
   persistencia.
5. Sesiones sin cierre de ≤ 2 h quedan sin enmascarar (E6).
6. Deriva del uso: el perfil de 3 días difiere del de todos los días (F,
   kappa 0.725).

**Modelo y evaluación**

1. Señal modesta en F: AUC 0.710 (bajo la meta de 0.72); la peor persona
   queda en 0.49.
2. S es sobre todo detección: de su AUC de 0.953, 0.904 lo da la parte ya
   observada.
3. Techo de información con solo tiempos de pantalla y conteos de apps
   (E8c).
4. Optimismo por pruebas repetidas (§11.3); AUC indefinido en 1 persona (F)
   y 2 (S).

**Operación**

1. Precisión de F baja con el corte de máximo F1 (~0.35).
2. Carga desigual: p90 de 17 por día frente a una media de 11.1, antes del
   tope de 12.
3. La inferencia de desbloqueo espera el primer `ACTIVITY_RESUMED`: la
   intervención aparece justo después de abrir la app.
4. No se midieron el retraso de eventos (E14) ni la latencia y batería en
   el dispositivo (E11).

---

## 13. Repositorio y reproducibilidad

### 13.1 Estructura

```
smartUse-dataMining/
├── README.md, requirements.txt, pytest.ini
├── smartuse/              biblioteca de funciones puras (la que se porta al dispositivo)
│   ├── windows.py         métricas de ventana, puntos de decisión, grilla, máscaras
│   ├── coverage.py        dato faltante y días suficientes
│   ├── profile.py         perfil congelado y valor previo
│   ├── features.py        catálogo de features (52; los modelos usan 36)
│   ├── features_dynamics.py  42 features de dinámica
│   ├── features_extra.py  grupos de E8c (no adoptados)
│   └── model.py           MergedModel: ensamble fusionado + calibrador + corte
├── pipelines/
│   ├── mpud/              extracción y fases 1–3 de MPUD
│   ├── real/              fases 1–3 de los datos reales
│   └── labels/            fase 4 (ventanas) y fase 5 (features y etiquetas)
├── experiments/           un script por experimento, módulos compartidos,
│   │                      entrenamiento final, corte de F y exportación
│   └── notebook_gen/      generadores de los notebooks de E1c, E9b y fase G
├── notebooks/             notebooks ejecutados (E8, E8b–d, E4b, E9b, fase G, E1c)
├── eda/                   verificaciones y diagnósticos de la limpieza
├── plots/                 código de las figuras de la tesis
├── export/
│   ├── models/            F.model, S.model, production.json
│   ├── java/smartuse/     SmartUseModel.java, ParityCheck.java
│   ├── parity/            vectores de paridad (MPUD)
│   └── fixtures/          fixtures del extractor (dos personas de MPUD)
├── tests/                 70 pruebas (pytest)
└── data/
    └── models/            modelos entrenados (lo único de data/ que se publica)
        ├── F/, S/         seed0..4.json (XGBoost) + model.json (features, calibrador, corte)
        ├── production.json  reglas, cortes, procedencia y valor previo poblacional
        └── lstm_mpud.pt   LSTM preentrenada en MPUD (E8, no adoptada)
```

`data/` se completa localmente con `mpud_raw/` (MPUD original),
`usage/` (`mpud.csv`, `real_data.csv`), `deployment_data/` (datos del
despliegue), `processed/` (salida de cada fase) y `reports/` (resultados
de los experimentos). `figures/` recibe las figuras.

### 13.2 Figuras y análisis

| Script | Figuras |
|---|---|
| `eda/timezone_drift_check.py` | Verificación de la zona horaria |
| `eda/mpud_screen_markers.py`, `mpud_repeat_distributions.py`, `real_repeat_markers.py`, `real_repeat_eventplots.py`, `repeat_markers_before_after.py` | Diagnóstico de marcadores repetidos (§4.4) |
| `plots/pre_eda.py` | ICC de F y S y distribuciones por persona (evidencia del percentil propio) |
| `plots/post_eda.py` | Datos procesados: reales frente a MPUD |
| `plots/model_auc.py` | AUC por participante de los modelos F y S frente a la persistencia |
| `plots/shap_models.py` (+ `feature_glossary.py`) | SHAP de los modelos de producción |
| `plots/population.py`, `population_description.py` | Participantes, duración y demografía de la recolección y el despliegue |
| `plots/preliminary_forms.py` | Formulario de reclutamiento |
| `plots/clean_final_answers.py`, `final_answers.py`, `final_metrics.py` | Formulario final de usabilidad y métricas del despliegue (grupo con la JITAI) |

Los scripts de `plots/` se corren como `python plots/<script>.py` y
escriben en `figures/`; comparten el estilo de `plots/plot_style.py`.

### 13.3 Cómo reproducir

Python 3.12 y Java (para la paridad). Desde la raíz del repositorio:

```bash
pip install -r requirements.txt

# Limpieza (fases 1–3)
python pipelines/mpud/mpud_extraction.py      # data/mpud_raw → data/usage/mpud.csv
python pipelines/mpud/mpud_preprocess_1.py    # zona horaria
python pipelines/mpud/mpud_preprocess_2.py    # épocas
python pipelines/mpud/mpud_preprocess_3.py    # sesiones (args: entrada, salida, umbral s, T_max h)
python pipelines/real/real_preprocess_1.py
python pipelines/real/real_preprocess_2.py
python pipelines/real/real_preprocess_3.py

# Fase 4: ventanas (points y grid) con cobertura
python -m pipelines.labels.build_windows <sesiones> <salida> --mode points|grid --epochs <épocas>

# Fase 5: features y etiquetas (variantes con --points para b = 15, 30 y --d para los días de perfil)
python -m pipelines.labels.build_features {real,mpud}
python -m pipelines.labels.build_dynamics_features {real,mpud}
python -m pipelines.labels.build_extra_features {real,mpud}

# Experimentos: un script por experimento (§10) y los notebooks
python -m experiments.e5_session_gap          # ejemplo

# Modelos finales
python -m experiments.cutoff_f1               # corte de F (máximo F1) → data/reports/cutoff_f1.json
python -m experiments.train_final             # data/models/{F,S}, production.json
python -m experiments.export_models           # export/models, export/parity y paridad Java
python -m experiments.make_fixtures           # export/fixtures

# Pruebas
python -m pytest
```

Los argumentos exactos de cada paso están en el docstring de su script.
Semillas: global 0; variantes con 5 semillas (0–4); validación de MPUD con
20 personas al azar (semilla 0).

### 13.4 Privacidad y qué se publica

| Se publica | No se publica |
|---|---|
| Código (`smartuse/`, `pipelines/`, `experiments/`, `eda/`, `plots/`, `tests/`) | Datos crudos y procesados (`data/` salvo `data/models/`) |
| Notebooks ejecutados | Figuras (`figures/`; se regeneran con el código) |
| Modelos entrenados (`data/models/`, `export/models/`) | Resultados intermedios (`data/reports/`) |
| Vectores de paridad y fixtures (derivados de MPUD, público) | Datos del despliegue y formularios |

Los datos reales nunca salieron de la máquina local: todo el entrenamiento
y la evaluación corrieron localmente. Ningún identificador de participante
se publica: donde el código o un resultado necesita referirse a una
persona de los datos reales, usa un seudónimo (`p` + los primeros 8
caracteres hexadecimales del SHA-256 del identificador; `pseudonym()` en
`experiments/common.py`).

### 13.5 Pruebas

70 pruebas (`python -m pytest`): métricas de ventana y puntos de decisión,
cobertura, perfil, features y su paridad (registro completo frente a
cortado en *t*), dinámica, evaluación, calibración, simulación de
intervenciones, `MergedModel`, exportación y paridad Java, y los fixtures
del extractor. Las de paridad Java y de fixtures se saltan si no están
`javac` o los archivos exportados.

---

## 14. Glosario y notación

| Término | Definición |
|---|---|
| **Época** | Bloque continuo de uso (pantalla encendida) o de no-uso (apagada) |
| **Sesión** | Época de uso con al menos 1 app, unida con las siguientes si el hueco es < 30 s |
| **Sesión sin cierre** (`no_close`) | Sesión de más de 3 h; casi siempre un apagado no registrado |
| **Punto de decisión** | Momento en que se infiere: inicio de sesión y cada 10 min dentro de ella, hasta 3 h |
| **Perfil** | Resumen congelado del uso de la persona en sus primeros 3 días utilizables |
| **Valor previo poblacional** | Percentil medio de las demás personas; respaldo en el arranque en frío |
| **Parte observada** | Fracción de la ventana de la etiqueta que ya ocurrió en *t* |
| **Persistencia** | Baseline: la próxima hora será como la última |
| **Episodio** | Racha de puntos positivos de un patrón con < 60 min entre puntos |
| **Ensamble fusionado** | 5 XGBoost con los márgenes promediados, exportado como un solo modelo |
| **Margen** | Suma de las hojas de los árboles (logit del score) |
| **LOSO** | Dejar una persona fuera (*leave-one-subject-out*) |
| **Desplazamiento de zona horaria** | Corrección de la hora de los datos a hora local |

| Símbolo | Significado | Valor |
|---|---|---|
| *W* | Ancho de la ventana de F y S | 60 min |
| *b* | Desplazamiento de la ventana de la etiqueta hacia el pasado | F 0; S 30 min |
| *k* | Intervalo entre puntos de decisión | 10 min |
| *p* | Percentil del perfil | 80 |
| *d* | Días de perfil | 3 |
| *n*<sub>0</sub> | Peso del valor previo (puntos de decisión) | 71 (F), 247 (S) |
| *c*<sub>F</sub>, *c*<sub>S</sub> | Cortes operativos | 0.3024 (Platt), 0.2416 |
| *T*<sub>max</sub> | Umbral de sesión sin cierre | 3 h |

| Exp. | Nombre | Exp. | Nombre |
|---|---|---|---|
| E1 | Días de perfil (kappa) | E8 | Modelo y bloques de features |
| E1b | Perfil combinado con el previo | E8b | Variantes de XGBoost |
| E1c | Días de perfil con AUC | E8c | Grupos de features adicionales |
| E1d | Días de perfil con F1 | E8d | Dinámica de la actividad |
| E2 | Peso del valor previo | E9 | Corte del score único |
| E3 | Percentil | E9b | Corte por patrón con regla de episodio |
| E4 | Sensibilidad a *W* | E10 | Día de inicio |
| E4b | Desplazamiento de la etiqueta | E11 | Latencia y batería |
| E5 | Umbral de sesión | E12 | Regla de al menos 1 app |
| E6 | Umbral de sesiones sin cierre | E13 | Preentrenamiento con MPUD |
| E7 | Solapamiento de F y S | E14 | Retraso de eventos |
| Fase G | Ensamble, calibración y modelos finales | | |

---

## 15. Referencias

- Goh, K.-I., y Barabási, A.-L. (2008). Burstiness and memory in complex systems. *EPL*, 81, 48002.
- Kim, E.-K., y Jo, H.-H. (2016). Measuring burstiness for finite event sequences. *Physical Review E*, 94, 032311.
- Kwon, M., et al. (2013). The Smartphone Addiction Scale: Development and validation of a short version for adolescents. *PLoS ONE*.
- Lin, Y.-H., et al. (2017). To use or not to use? Compulsive behavior and its role in smartphone addiction. *Translational Psychiatry*, 7(2), e1030.
- Nahum-Shani, I., et al. (2018). Just-in-Time Adaptive Interventions (JITAIs) in Mobile Health: Key Components and Design Principles. *Annals of Behavioral Medicine*.
- Orzikulova, A., et al. (2024). Time2Stop: Adaptive and Explainable Human-AI Loop for Smartphone Overuse Intervention. *CHI '24*.
- Pielot, M., et al. (2017). Beyond interruptibility: Predicting opportune moments to engage mobile phone users. *UbiComp '17*.
- Siebers, T., Beyens, I., y Valkenburg, P. M. (2023). The effects of fragmented and sticky smartphone use on distraction and task delay. *Mobile Media & Communication*, 12(1).
- Yi, H., Wu, y Liu (2025). When Smartphones Fragment the Mind: Fragmented Smartphone Use and Anxiety among College Students.
- Zheng, J., y Choe, E. K. To Stop or Not to Stop: Exploring the Intention-Behavior Gaps in Smartphone Usage. arXiv.
