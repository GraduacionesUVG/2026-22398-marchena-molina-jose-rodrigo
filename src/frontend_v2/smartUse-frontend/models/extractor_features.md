# Especificación del extractor de features (dispositivo)

Última revisión: 2026-10-19. Describe, sin ambigüedad, todo lo que el
dispositivo tiene que calcular para producir los vectores de entrada de los
modelos F y S: desde los eventos de pantalla hasta el score y la decisión de
intervenir. Es la referencia para portar el extractor (Java o Kotlin).

La implementación de referencia es el código Python de este repositorio:

| Paso | Código de referencia |
|---|---|
| Épocas y sesiones | `pipelines/real/real_preprocess_2.py`, `pipelines/real/real_preprocess_3.py` |
| Métricas de ventana y puntos de decisión | `smartuse/windows.py` |
| Cobertura | `smartuse/coverage.py` |
| Perfil y features | `smartuse/features.py`, `smartuse/features_dynamics.py` |
| Modelo | `smartuse/model.py`, `export/java/smartuse/SmartUseModel.java` |

**Cómo se verifica un port.** `export/fixtures/` tiene dos personas de MPUD
(datos públicos) con la entrada y cada resultado intermedio esperado.
`tests/test_fixtures.py` recalcula todo con el código Python; un port pasa
si reproduce los mismos archivos. Ver la sección 10.

---

## 1. Convenciones

- **Tiempo.** Segundos desde la época Unix en UTC, como `double`. La hora
  local es UTC + `offset_s` (constante por persona; el horario de verano no
  se maneja, y Guatemala no lo tiene). "Día local" = de medianoche a
  medianoche en hora local.
- **Intervalos semiabiertos.** Toda ventana es [*a*, *b*): una sesión que
  termina justo en *a*, o empieza justo en *b*, no entra.
- **Logaritmos.** Natural. `log1p(x)` = ln(1 + *x*). Las duraciones y los
  intervalos en segundos salvo que el nombre diga `min`.
- **Cuantiles.** Definición de numpy por defecto (interpolación lineal,
  tipo 7 de Hyndman y Fan): para valores ordenados *v*<sub>0..n−1</sub>, el
  cuantil *q* está en la posición *q*·(*n* − 1).
- **Desviación estándar.** Poblacional (divide por *n*, no por *n* − 1).
- **Vector final.** Se convierte a `float32` antes del modelo, en el orden
  exacto de la sección 8.

## 2. Entrada en el dispositivo

- **Eventos de pantalla:** encendido (`SCREEN_INTERACTIVE` / `ACTION_SCREEN_ON`)
  y apagado (`SCREEN_NON_INTERACTIVE` / `ACTION_SCREEN_OFF`), con su hora
  real. Llegan en tiempo real si se registran desde un servicio.
- **Apps por época de uso:** número de paquetes distintos con
  `ACTIVITY_RESUMED` durante la época, **incluido el launcher** y
  **excluido el paquete de SmartUse** (y su overlay). Solo se usa el conteo.

## 3. Épocas y sesiones

**3.1 Épocas.** Cada evento de pantalla inicia una época de uso (encendido)
o de no-uso (apagado). Varios eventos seguidos del mismo tipo se fusionan
en una época: se conserva el primer inicio, y las apps son el máximo de las
fusionadas. Cada época dura hasta el inicio de la siguiente; la época en
curso está abierta.

**3.2 Sesiones** (en este orden):

1. Una época de uso con **0 apps** cuenta como no-uso (encendido sin
   actividad en primer plano).
2. Las épocas de uso restantes se unen si el hueco entre el fin de una y el
   inicio de la siguiente es **menor que 30 s**.
3. Por sesión: `start` (primera época), `end` (fin de la última), `apps` =
   **suma** de las apps de sus épocas (las apps repetidas entre épocas no se
   pueden identificar: es un máximo).

En el dispositivo, la sesión en curso está abierta: su `end` es *t* y sus
apps **no se conocen hasta que cierra** (el extractor nunca las lee).

**3.3 Sesiones sin cierre (*T*<sub>max</sub> = 3 h).** Una sesión de más de
3 h se marca `no_close`: casi siempre es un apagado que no se registró o un
teléfono desatendido. **No es una feature y el modelo nunca la ve.** Su
papel:

- **En producción (regla de ejecución):** cuando la sesión en curso pasa de
  3 h, no se generan más puntos de decisión, no se infiere ni se interviene
  hasta el siguiente cambio de pantalla. Coincide con el entrenamiento, que
  nunca tuvo puntos de decisión después de 3 h dentro de una sesión.
- **En el perfil (sección 6):** las ventanas que se solapan con una sesión
  `no_close` no entran al perfil.
- **En las features:** una sesión `no_close` ya cerrada sigue en el registro
  y cuenta en las features como cualquier otra (en el entrenamiento también
  estaba). Solo deja de existir el problema mientras está abierta, porque
  pasadas las 3 h no se infiere.

## 4. Puntos de decisión

- Uno al **iniciar cada sesión** (el desbloqueo que la abre).
- Después, uno **cada *k* = 10 min** dentro de la sesión, mientras *t* <
  `end` y *t* < `start` + 3 h.
- Un encendido a menos de 30 s del último apagado continúa la misma sesión:
  no genera un punto nuevo.

## 5. Cobertura

- **Dato faltante:** una época de no-uso de **más de 10 h** es dato faltante
  (teléfono apagado o app detenida), no no-uso.
- **Día suficiente:** un día local cuenta si tiene **al menos 6 horas de
  reloj distintas** con algún inicio de época (de uso o de no-uso).
- Solo afectan la construcción del perfil (sección 6) y la evaluación; en
  producción, un día insuficiente debería emitir un aviso de cobertura.

## 6. Perfiles (dos: uno por modelo)

El dispositivo construye **dos perfiles** con los mismos días, porque cada
modelo usa ventanas de etiqueta distintas:

| Perfil | Lo usa | Ventana de cada punto de decisión *t* |
|---|---|---|
| Perfil F | modelo F | [*t*, *t* + 60 min) (*b* = 0) |
| Perfil S | modelo S | [*t* − 30, *t* + 30 min) (*b* = 30) |

**6.1 Puntos y días del perfil.** Un punto de decisión es utilizable si su
ventana:

- no se solapa con una sesión `no_close`;
- no empieza antes del inicio de los datos de la persona, no pasa del fin de
  los datos y no se solapa con un dato faltante;
- y el punto cae en un día suficiente.

Los **días del perfil** son los **primeros 3 días locales con al menos un
punto utilizable** (no los 3 primeros días calendario). El perfil se calcula
al terminar el tercero de esos días, cuando ya pasaron 60 min del último
punto (su ventana tiene que estar completa), y se **congela**. No hay
intervención antes.

**6.2 Métricas de una ventana** [*a*, *a* + *W*), con *W* = 60 min: las
sesiones se recortan a la ventana;

- *n* = número de sesiones que se solapan;
- span = fin de la última recortada − inicio de la primera recortada;
- **F** = *n* · span / *W* si *n* ≥ 2, si no 0;
- **S** = ln(max(duración recortada más larga, 1 s)); 0 si no hay sesiones.

**6.3 Contenido de cada perfil.** Con los valores *f* y *s* de las ventanas
de los puntos utilizables de los días del perfil:

| Campo | Definición |
|---|---|
| `pf`, `ps` | Cuantil 0.80 de *f* y de *s* (los umbrales de la etiqueta) |
| `f_q`, `s_q` | Cuantiles 0.05, 0.10, …, 0.95 (19 valores) de *f* y de *s* |
| `f_median`, `s_median` | Mediana de *f* y de *s* |

Y, del registro de sesiones de cada día del perfil (sesiones recortadas al
día local), la mediana entre los 3 días de:

| Campo | Definición por día |
|---|---|
| `freq_day` | Número de sesiones |
| `use_day_min` | Suma de las duraciones / 60 |
| `logdur_median` | Mediana de log1p(duración) si hay 2 sesiones o más, si no 0 |
| `nonuse_day_min` | Suma de los huecos entre sesiones consecutivas / 60 |
| `lognonuse_median` | Mediana de log1p(hueco) si hay 2 huecos o más, si no 0 |
| `sessions_by_hour` (24) | Sesiones iniciadas en cada hora local; mediana por hora entre los días |

Los campos de Lin y `sessions_by_hour` son iguales en los dos perfiles; los
de la primera tabla difieren.

**6.4 Respaldo con el valor previo poblacional.** Para `pf` y `ps`, si hay
pocos datos propios: `P` = *w* · propio + (1 − *w*) · previo, con *w* =
*n* / (*n* + *n*<sub>0</sub>), *n* = puntos utilizables del perfil,
*n*<sub>0</sub> = 71 (para `pf`) y 247 (para `ps`). Los valores previos están
en `export/models/production.json` (`population_prior`). Con *d* = 3 el
perfil del despliegue es solo el propio (E1b). **Decisión abierta:** qué
cuenta como "cobertura baja" (por ejemplo, no tener 3 días suficientes al
cabo de una semana) y qué hacer con los demás campos del perfil en ese caso.

## 7. Features en el momento *t*

**7.1 Reglas generales.**

- Solo el registro hasta *t*: sesiones con `start` ≤ *t*; los `end` se
  recortan a *t*.
- **Sesión en curso:** la última iniciada, si su `end` ≥ *t*. Sus apps no
  se leen nunca.
- **Sesiones cerradas:** todas las anteriores a la sesión en curso (o todas,
  si no hay sesión en curso).
- Cada feature F usa el **perfil F**; cada feature S, el **perfil S**. Las
  features que no dependen del perfil son iguales en los dos vectores.

**7.2 Estado actual.**

| Feature | Definición |
|---|---|
| `cur_logdur` | log1p(*t* − `start` de la sesión en curso); 0 si no hay |
| `since_last_logmin` | En sesión: log1p((`start` en curso − `end` de la anterior) / 60), 0 si no hay anterior. Fuera de sesión: log1p((*t* − `end` de la última) / 60) |
| `no_previous` | 1 si no hay sesión anterior a la actual, si no 0 (en los datos de entrenamiento siempre fue 0) |

**7.3 Ventana de 15 min** [*t* − 900, *t*): sesiones cuyo `end` recortado
es mayor que *t* − 900, con `start` recortado al inicio de la ventana.

| Feature | Definición | Mínimo |
|---|---|---|
| `n_15` | Número de sesiones | — |
| `f_15` | F de la ventana con *W* = **15 min** (*n* · span / 900) | *n* ≥ 2 |
| `logdur_median_15`, `logdur_mean_15` | Mediana y media de log1p(duración recortada) | *n* ≥ 2, si no 0 |
| `few2_15` | 1 si *n* < 2 | — |
| `logdur_sd_15` | Desviación poblacional de log1p(duración recortada) | *n* ≥ 3, si no 0 |
| `burst_15` | Burstiness de los huecos entre sesiones de la ventana (7.6) | 3 huecos o más, si no 0 |
| `few3_15` | 1 si hay menos de 3 huecos | — |
| `apps_mean_15`, `apps_max_15` | Media y máximo de las apps de las sesiones **cerradas** de la ventana | al menos una, si no 0 |
| `noapps_15` | 1 si no hay sesiones cerradas en la ventana | — |

**7.4 Día local hasta *t*** [medianoche local, *t*), sesiones recortadas. Solo cuentan las sesiones que
empezaron **estrictamente antes** de *t*: en el desbloqueo que abre una sesión, esa sesión no entra aquí.

| Feature | Definición |
|---|---|
| `freq_day` | Número de sesiones |
| `use_day_logmin` | log1p(suma de duraciones / 60) |
| `logdur_median_day` | Mediana de log1p(duración) si hay 2 o más, si no 0 |
| `nonuse_day_logmin` | log1p(suma de huecos entre sesiones / 60) |
| `lognonuse_median_day` | Mediana de log1p(hueco) si hay 2 huecos o más, si no 0 |
| `since_first_logmin` | log1p((*t* − inicio recortado de la primera sesión del día) / 60); 0 si no hay |
| `burst_day` | Burstiness de los huecos del día (3 o más, si no 0) |
| `max_f60_day`, `max_s60_day` | Máximo de F y de S (6.2, *W* = 60 min) entre las **horas de reloj completas** del día: [*h*, *h* + 1 h) con *h* + 1 h ≤ *t*; 0 si no hay |
| `positive_hours_day` | Cuántas de esas horas tienen F > `pf` o S > `ps` del perfil del modelo |

**7.5 Perfil congelado y posición relativa.** Con F₆₀ y S₆₀ = F y S
(6.2) de la última hora [*t* − 3 600, *t*), calculados aunque no sean
entrada del modelo:

| Feature | Definición |
|---|---|
| `prof_pf`, `prof_ps` | `pf`, `ps` del perfil |
| `prof_f_median`, `prof_s_median` | `f_median`, `s_median` |
| `prof_freq_day` | `freq_day` del perfil |
| `prof_use_day_logmin` | log1p(`use_day_min`) |
| `prof_logdur_median` | `logdur_median` |
| `prof_nonuse_day_logmin` | log1p(`nonuse_day_min`) |
| `prof_lognonuse_median` | `lognonuse_median` |
| `f60_rank` | Interpolación lineal de F₆₀ sobre (`f_q` → 0.05, …, 0.95); fuera del rango se fija en 0.05 o 0.95 (como `numpy.interp`) |
| `s60_rank` | Igual con S₆₀ y `s_q` |
| `sessions_vs_hour_typical` | Sesiones iniciadas en [*t* − 1 h, *t*) − `sessions_by_hour`[hora local de *t*] |

**7.6 Burstiness** de *m* intervalos con media μ y desviación σ: *r* =
σ / μ (0 si μ = 0, y entonces B = 0), con la corrección de Kim y Jo (2016):

B = (√(*m* + 1) · *r* − √(*m* − 1)) / ((√(*m* + 1) − 2) · *r* + √(*m* − 1))

**7.7 Dinámica (solo el modelo F, 42 features).** Sin perfil. Bins
consecutivos que terminan en *t*:

| Escala | Ventana | Bins |
|---|---|---|
| `15m` | [*t* − 15 min, *t*) | 3 de 5 min |
| `1h` | [*t* − 1 h, *t*) | 4 de 15 min |
| `24h` | [*t* − 24 h, *t*) (móvil, no el día calendario) | 24 de 1 h |

Dos señales por bin: `n` = sesiones **iniciadas** en el bin; `min` =
minutos de pantalla encendida dentro del bin (solapamiento de cada sesión,
con `end` recortado a *t*, así que la sesión en curso cuenta hasta *t*).

Por escala y señal, sobre el vector *x* de *B* bins (del más viejo al más
nuevo):

| Sufijo | Definición |
|---|---|
| `slope` | Pendiente de mínimos cuadrados de *x* contra 0..*B* − 1 (cov / var poblacionales); 0 si *B* < 2 |
| `accel` | `slope` de los últimos ⌈*B*/2⌉ bins − `slope` de los primeros ⌈*B*/2⌉ |
| `rel_change` | (*x*<sub>último</sub> − media de los anteriores) / (media de los anteriores + 1) |
| `com` | Σ *i*·*x*<sub>*i*</sub> / Σ *x* / (*B* − 1); 0.5 si Σ *x* = 0 |
| `peak` | máx(*x*) / media(*x*); 0 si Σ *x* = 0 |
| `persist` | Con *d* = diferencias consecutivas: si *d*<sub>último</sub> ≠ 0, cuántas diferencias seguidas desde el final tienen su mismo signo, con ese signo (+ sube, − baja); si no, 0 |

Nombres: `<señal>_<escala>_<sufijo>`, por ejemplo `n_1h_slope`.

Entre escalas, por señal, con tasas por minuto (bin / minutos del bin),
desviaciones poblacionales y ε = 0.01:

| Feature | Definición |
|---|---|
| `<señal>_var_15m_1h` | ln((σ<sub>15m</sub> + ε) / (σ<sub>1h</sub> + ε)) |
| `<señal>_var_1h_24h` | ln((σ<sub>1h</sub> + ε) / (σ<sub>24h</sub> + ε)) |
| `<señal>_level_15m_24h` | ln((media<sub>15m</sub> + ε) / (media<sub>24h</sub> + ε)) |

## 8. Orden de los vectores

**S (36):** `cur_logdur, since_last_logmin, no_previous, n_15, f_15,
logdur_median_15, logdur_mean_15, few2_15, logdur_sd_15, burst_15, few3_15,
apps_mean_15, apps_max_15, noapps_15, freq_day, use_day_logmin,
logdur_median_day, nonuse_day_logmin, lognonuse_median_day,
since_first_logmin, burst_day, max_f60_day, max_s60_day, positive_hours_day,
prof_pf, prof_ps, prof_f_median, prof_s_median, prof_freq_day,
prof_use_day_logmin, prof_logdur_median, prof_nonuse_day_logmin,
prof_lognonuse_median, f60_rank, s60_rank, sessions_vs_hour_typical`

**F (78):** las mismas 36, con el **perfil F**, seguidas de las 42 de
dinámica en este orden: para la escala `15m`, luego `1h`, luego `24h`; dentro
de cada escala, la señal `n` y luego `min`; dentro de cada señal, `slope,
accel, rel_change, com, peak, persist`. Después: `n_var_15m_1h,
n_var_1h_24h, n_level_15m_24h, min_var_15m_1h, min_var_1h_24h,
min_level_15m_24h`.

El orden autoritativo es la línea `features` de cada archivo de modelo
(`export/models/F.model`, `S.model`); la clase Java rechaza un vector de otro
largo.

**Lo que no entra.** Las 36 features podadas no incluyen el bloque de la
ventana de 60 min (F₆₀ y S₆₀ solo se usan dentro de `f60_rank`, `s60_rank` y
los máximos del día), ni la hora del día, el fin de semana o el tipo de
punto de decisión.

## 9. Score y decisión

**9.1 Score** (`SmartUseModel.score`): para cada una de las 5 semillas,
margen = `base_margin` + suma de las hojas de sus árboles (en cada nodo,
`x < umbral` va a `yes`, si no a `no`, y un `NaN` va a `missing`); se
promedian los 5 márgenes; *p* = sigmoide(margen promedio). F aplica Platt:
*p* = sigmoide(1.248 · logit(*p*) + 0.625), con *p* recortado a [10⁻⁶,
1 − 10⁻⁶]. S queda sin calibrar.

**9.2 Cortes:** F dispara si *p* > 0.302 (bajado de 0.446 el 2026-10-22); S si *p* > 0.242.

**9.3 Reglas de ejecución** (`export/models/production.json`):

1. Ningún punto de decisión después de 3 h dentro de una sesión (3.3).
2. **Enfriamiento de 10 min** entre intervenciones de cualquier patrón.
3. **Regla de episodio:** como máximo una intervención por patrón cada 60
   min.
4. Si los dos disparan en el mismo punto, interviene el de **mayor margen
   sobre su corte** (*p* − *c*).
5. **Tope diario:** 20 intervenciones por persona y día local (salvaguarda, por encima del percentil 90).
6. Los scores bloqueados por las reglas 2 a 5 se **registran** igual, sin
   mostrarse.

## 10. Verificación con los fixtures

`export/fixtures/<persona>/` (personas de MPUD, `offset_s` = +2 h):

| Archivo | Paso que verifica |
|---|---|
| `epochs.csv` | Entrada (épocas ya formadas, con su conteo de apps) |
| `sessions.csv` | 3.2 y 3.3 |
| `points_b0.csv`, `points_b30.csv` | 4, 5 y 6.1–6.2: puntos de decisión, métricas de la ventana de cada modelo (`f`, `s`, `n_sessions`) y máscaras (`masked`, `censored`, `day_ok`) |
| `profile_F.json`, `profile_S.json` | 6.3 (incluye los días del perfil y `offset_s`) |
| `vectors_F.csv`, `vectors_S.csv` | 7 y 8 por punto de ejemplo, más `expected_score` y `expected_fires` (9) |

Tolerancias: vectores con 10⁻⁵ (relativo y absoluto) y scores con 10⁻⁶. El
score se verifica además con `export/parity/` y `smartuse.ParityCheck`
(diferencia máxima Java − Python de 2·10⁻⁷).

## 11. Detalles que es fácil equivocar

1. Las apps de la **sesión en curso** nunca entran: ni en `apps_*`, ni en el
   conteo de la sesión al construir el registro.
2. Las apps de una sesión son la **suma** de sus épocas, pero las de una
   época fusionada (eventos del mismo tipo seguidos) son el **máximo**.
3. Las épocas de uso con **0 apps** se descartan **antes** de unir sesiones
   a menos de 30 s.
4. Hay **dos perfiles**; la feature `prof_pf` del modelo S es el percentil de
   F calculado sobre las ventanas desplazadas de S.
5. `f_15` usa *W* = 15 min; F₆₀, `max_f60_day` y el perfil usan *W* = 60 min.
6. La ventana de 24 h de la dinámica es **móvil**; el bloque del día usa el
   **día calendario local**.
7. `max_f60_day` y `max_s60_day` solo miran horas de reloj **completas**.
8. Los días del perfil son días con **puntos utilizables**, no días
   calendario.
9. Cuantiles con interpolación lineal (tipo 7) y desviaciones poblacionales.
10. El vector va en `float32` y en el orden exacto del archivo de modelo.
11. En el desbloqueo que abre una sesión (*t* = `start`), la sesión recién iniciada **sí** cuenta en la
    ventana de 15 min (con duración 0, en `n_15` y en las duraciones), pero **no** en el bloque del día,
    en `sessions_vs_hour_typical` ni en la señal `n` de la dinámica, que solo cuentan inicios anteriores
    a *t*.
12. Empates en la regla 4 de la sección 9.3: si los márgenes son iguales, interviene F.
