"""Spanish glossary of the model features (short name + short definition), for the SHAP
legends. Source: README.md, section 6. The 42 dynamics features of
model F are generated from their <signal>_<scale>_<stat> pattern.

The figure labels in plots/shap_models.py (LABELS) take precedence over NAMES here.
"""
from __future__ import annotations

# --- the 36 features shared by F and S (each model uses its own profile) ------------------
_BASE: dict[str, tuple[str, str]] = {
    # 7.2 estado actual
    "cur_logdur": ("Duración de la sesión en curso",
                   "log1p de los segundos transcurridos en la sesión en curso; 0 si no hay "
                   "sesión abierta (punto de desbloqueo)."),
    "since_last_logmin": ("Minutos desde la sesión anterior",
                          "En sesión: log1p de los minutos entre el fin de la sesión anterior y "
                          "el inicio de la actual (0 si no hay anterior). Fuera de sesión: "
                          "log1p de los minutos desde el fin de la última sesión."),
    "no_previous": ("Sin sesión anterior",
                    "1 si no hay sesión anterior a la actual; si no, 0 (en los datos de "
                    "entrenamiento siempre fue 0)."),
    # 7.3 ventana de 15 min
    "n_15": ("Sesiones en los últimos 15 min", "Número de sesiones en los últimos 15 min."),
    "f_15": ("F de los últimos 15 min",
             "F con W = 15 min (sesiones × extensión / 900 s); 0 con menos de 2 sesiones."),
    "logdur_median_15": ("Mediana de duración (15 min)",
                         "Mediana de log1p(duración recortada) de las sesiones de los últimos "
                         "15 min; 0 con menos de 2 sesiones."),
    "logdur_mean_15": ("Media de duración (15 min)",
                       "Media de log1p(duración recortada) de las sesiones de los últimos 15 "
                       "min; 0 con menos de 2 sesiones."),
    "few2_15": ("Menos de 2 sesiones (15 min)",
                "1 si hay menos de 2 sesiones en los últimos 15 min."),
    "logdur_sd_15": ("Dispersión de duración (15 min)",
                     "Desviación poblacional de log1p(duración recortada) en los últimos 15 "
                     "min; 0 con menos de 3 sesiones."),
    "burst_15": ("Burstiness de los huecos (15 min)",
                 "Burstiness (Kim y Jo, 2016) de los huecos entre sesiones de los últimos 15 "
                 "min; 0 con menos de 3 huecos."),
    "few3_15": ("Menos de 3 huecos (15 min)",
                "1 si hay menos de 3 huecos entre sesiones en los últimos 15 min."),
    "apps_mean_15": ("Apps por sesión, media (15 min)",
                     "Media de apps de las sesiones cerradas de los últimos 15 min; 0 si no hay."),
    "apps_max_15": ("Apps por sesión, máximo (15 min)",
                    "Máximo de apps de las sesiones cerradas de los últimos 15 min; 0 si no hay."),
    "noapps_15": ("Sin sesiones cerradas (15 min)",
                  "1 si no hay sesiones cerradas en los últimos 15 min."),
    # 7.4 día local hasta t
    "freq_day": ("Sesiones en el día hasta ahora",
                 "Número de sesiones del día local, desde la medianoche hasta t."),
    "use_day_logmin": ("Minutos de uso en el día",
                       "log1p de los minutos en sesión del día local hasta t."),
    "logdur_median_day": ("Mediana de duración en el día",
                          "Mediana de log1p(duración) de las sesiones del día; 0 con menos de 2."),
    "nonuse_day_logmin": ("Minutos sin uso entre sesiones del día",
                          "log1p de la suma de minutos entre sesiones consecutivas del día."),
    "lognonuse_median_day": ("Mediana de hueco entre sesiones del día",
                             "Mediana de log1p(hueco) entre sesiones del día; 0 con menos de 2 "
                             "huecos."),
    "since_first_logmin": ("Minutos desde la primera sesión del día",
                           "log1p de los minutos desde el inicio de la primera sesión del día "
                           "hasta t; 0 si no hay."),
    "burst_day": ("Burstiness de los huecos del día",
                  "Burstiness (Kim y Jo, 2016) de los huecos entre sesiones del día; 0 con "
                  "menos de 3 huecos."),
    "max_f60_day": ("F máximo de una hora completa del día",
                    "Máximo de F entre las horas de reloj completas del día hasta t; 0 si no hay."),
    "max_s60_day": ("S máximo de una hora completa del día",
                    "Máximo de S entre las horas de reloj completas del día hasta t; 0 si no hay."),
    "positive_hours_day": ("Horas positivas en el día",
                           "Cuántas horas de reloj completas del día tienen F o S por encima "
                           "del umbral personal del perfil del modelo."),
    # 7.5 perfil congelado y posición relativa
    "prof_pf": ("Umbral personal de F",
                "Percentil 80 de $F$ en las ventanas de los puntos de decisión de los días del "
                "perfil del participante; es el umbral que define la etiqueta de $F$."),
    "prof_ps": ("Umbral personal de S",
                "Percentil 80 de $S$ en las ventanas de los puntos de decisión de los días del "
                "perfil del participante."),
    "prof_f_median": ("Mediana de F (perfil)",
                      "Mediana de F en las ventanas de los días del perfil."),
    "prof_s_median": ("Mediana de S (perfil)",
                      "Mediana de S en las ventanas de los días del perfil."),
    "prof_freq_day": ("Sesiones por día típicas (perfil)",
                      "Mediana de sesiones por día en los días de perfil de la persona."),
    "prof_use_day_logmin": ("Minutos de uso por día típicos (perfil)",
                            "log1p de la mediana de minutos en sesión por día del perfil."),
    "prof_logdur_median": ("Duración típica de sesión (perfil)",
                           "Mediana entre días del perfil de la mediana de log1p(duración)."),
    "prof_nonuse_day_logmin": ("Minutos sin uso por día típicos (perfil)",
                               "log1p de la mediana de minutos entre sesiones por día del perfil."),
    "prof_lognonuse_median": ("Hueco típico entre sesiones (perfil)",
                              "Mediana entre días del perfil de la mediana de log1p(hueco)."),
    "f60_rank": ("Posición de F de la última hora en el perfil",
                 "Posición de F de la última hora [t − 60 min, t) dentro de los percentiles "
                 "del perfil de la persona (0.05 a 0.95)."),
    "s60_rank": ("Posición de S de la última hora en el perfil",
                 "Posición de S de la última hora dentro de los percentiles del perfil de la "
                 "persona (0.05 a 0.95)."),
    "sessions_vs_hour_typical": ("Sesiones de la última hora vs. lo típico",
                                 "Sesiones iniciadas en la última hora menos la mediana del "
                                 "perfil para esa hora local."),
}

# --- 7.7 dynamics (model F only) -------------------------------------------------------------
# signal -> (short name, with article, without article)
_SIGNALS = {"n": ("sesiones iniciadas", "las sesiones iniciadas", "sesiones iniciadas"),
            "min": ("minutos de pantalla", "los minutos de pantalla encendida",
                    "minutos de pantalla encendida")}
# scale -> (short window, window with article, bins)
_SCALES = {"15m": ("últimos 15 min", "los últimos 15 min", "3 bins de 5 min"),
           "1h": ("última hora", "la última hora", "4 bins de 15 min"),
           "24h": ("últimas 24 h", "las últimas 24 h", "24 bins de 1 h")}
_STATS = {
    "slope": ("Pendiente",
              "Pendiente de mínimos cuadrados de {sig} por bin en {win} ({bins})."),
    "accel": ("Aceleración",
              "Pendiente de mínimos cuadrados de {sig} en la mitad más reciente de los bins "
              "menos la de la mitad anterior, en {win} ({bins}); un valor positivo indica que "
              "la actividad se está acelerando."),
    "rel_change": ("Cambio relativo",
                   "(Último bin − media de los anteriores) / (media de los anteriores + 1), "
                   "sobre {sig} en {win} ({bins})."),
    "com": ("Centroide temporal",
            "Centroide temporal (0 = inicio de la ventana, 1 = ahora) de {sig} en {win} "
            "({bins}); 0.5 si no hay actividad."),
    "peak": ("Pico relativo",
             "Máximo / media de {sig} por bin en {win} ({bins}); 0 si no hay actividad."),
    "persist": ("Persistencia",
                "Número de cambios consecutivos con el mismo signo desde el final, con ese "
                "signo (+ sube, − baja), sobre {sig} en {win} ({bins})."),
}
_CROSS = {
    "var_15m_1h": ("Variabilidad 15 min vs. 1 h",
                   "ln((σ 15 min + 0.01) / (σ 1 h + 0.01)) de las tasas por minuto de {sig}."),
    "var_1h_24h": ("Variabilidad 1 h vs. 24 h",
                   "ln((σ 1 h + 0.01) / (σ 24 h + 0.01)) de las tasas por minuto de {sig}."),
    "level_15m_24h": ("Nivel 15 min vs. 24 h",
                      "ln((media 15 min + 0.01) / (media 24 h + 0.01)) de las tasas por minuto "
                      "de {sig}."),
}


def _dynamics() -> dict[str, tuple[str, str]]:
    out = {}
    for sig, (sig_short, sig_art, sig_bare) in _SIGNALS.items():
        for scale, (win_short, win_art, bins) in _SCALES.items():
            for stat, (stat_name, template) in _STATS.items():
                out[f"{sig}_{scale}_{stat}"] = (
                    f"{stat_name} de {sig_short} ({win_short})",
                    template.format(sig=sig_art, win=win_art, bins=bins))
        for cross, (name, template) in _CROSS.items():
            out[f"{sig}_{cross}"] = (f"{name} de {sig_short}", template.format(sig=sig_bare))
    return out


GLOSSARY: dict[str, tuple[str, str]] = {**_BASE, **_dynamics()}
NAMES = {k: v[0] for k, v in GLOSSARY.items()}
DEFINITIONS = {k: v[1] for k, v in GLOSSARY.items()}
