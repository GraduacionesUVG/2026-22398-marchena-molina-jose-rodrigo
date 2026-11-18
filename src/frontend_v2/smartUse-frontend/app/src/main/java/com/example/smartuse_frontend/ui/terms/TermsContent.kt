package com.example.smartuse_frontend.ui.terms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.smartuse_frontend.ui.common.StudyContact

/**
 * The study's terms (deployment group), shared by the setup consent step
 * and the Terms page so the two can never drift apart. The wording is the
 * researcher's approved text — edit it here only, and verbatim.
 */
private sealed interface TermsBlock {
    data class Paragraph(val text: String) : TermsBlock
    data class Bullets(val items: List<String>) : TermsBlock
}

private data class TermsSection(val title: String, val blocks: List<TermsBlock>)

private fun p(text: String) = TermsBlock.Paragraph(text)
private fun bullets(vararg items: String) = TermsBlock.Bullets(items.toList())

private const val TERMS_TITLE = "Términos y condiciones de uso SmartUse"
private const val TERMS_SUBTITLE = "Grupo de despliegue · Versión 1.0 — 21/10/2026"

/** Shown only where the participant actually accepts (setup), not on the read-only page. */
const val TERMS_ACCEPTANCE =
    "He leído estos términos y condiciones, son consistentes con el consentimiento informado " +
            "que firmé, y acepto el uso de la aplicación bajo estas condiciones."

private val TERMS_SECTIONS = listOf(
    // 1
    TermsSection(
        "Naturaleza de la aplicación",
        listOf(
            p(
                "Esta aplicación es un instrumento de investigación del proyecto \"Prototipo de sistema " +
                        "de detección y reducción de uso compulsivo de smartphones en estudiantes universitarios " +
                        "guatemaltecos mediante un modelo predictivo y un módulo de intervención UI/UX\", trabajo " +
                        "de graduación de la carrera de Ingeniería en Ciencias de la Computación y Tecnologías de " +
                        "la Información (CCTI) de la Universidad del Valle de Guatemala, revisado por el Comité de " +
                        "Ética en Investigación de la Facultad de Ingeniería (CEIFI). No es un producto comercial " +
                        "ni una herramienta de diagnóstico.",
            ),
            p(
                "Estos términos corresponden al grupo de despliegue. En este grupo, la aplicación identifica en " +
                        "su dispositivo dos patrones de uso, revisiones frecuentes del teléfono y uso prolongado " +
                        "sin pausa, y muestra una intervención visual en esos momentos (sección 5). El propósito " +
                        "del estudio es evaluar la utilidad, usabilidad y aceptabilidad percibidas de este sistema.",
            ),
            p(
                "El uso de la aplicación está sujeto al consentimiento informado firmado por usted; en caso de " +
                        "discrepancia entre estos términos y dicho consentimiento, prevalece el consentimiento " +
                        "informado.",
            ),
        ),
    ),
    // 2
    TermsSection(
        "Requisitos y permisos",
        listOf(
            p("Para utilizar esta aplicación como participante del grupo de despliegue se requiere:"),
            bullets(
                "Ser estudiante activo de la Universidad del Valle de Guatemala, entre 18 y 24 años de edad.",
                "Contar con un dispositivo Android 13 o superior.",
                "Obtener un puntaje igual o mayor a 36 en la Smartphone Addiction Scale – Short Version " +
                        "(SAS-SV), que se completa durante el registro. Este puntaje es solo un criterio de " +
                        "tamizaje y no constituye una evaluación diagnóstica.",
                "Haber firmado el consentimiento informado del grupo de despliegue.",
            ),
            p(
                "Durante el registro dentro de la aplicación, se le solicitará otorgar cuatro permisos del " +
                        "sistema, necesarios para el funcionamiento del estudio:",
            ),
            bullets(
                "Acceso a datos de uso (PACKAGE_USAGE_STATS): permite a la aplicación leer el registro de uso " +
                        "que Android mantiene en su dispositivo, descrito en la sección 3. Sin este permiso, la " +
                        "aplicación no puede obtener esos datos ni decidir cuándo mostrar una intervención.",
                "Mostrar sobre otras aplicaciones (SYSTEM_ALERT_WINDOW): permite mostrar la intervención " +
                        "visual descrita en la sección 5 sobre la aplicación que esté usando.",
                "Notificaciones: permite mostrar una notificación que indica que su participación en el " +
                        "estudio está activa, así como avisos del estudio (por ejemplo, el fin de su periodo de " +
                        "participación, el aviso de la encuesta de cierre o cambios a estos términos). En " +
                        "Android 14 o superior puede descartar esta notificación; la aplicación la vuelve a " +
                        "mostrar periódicamente.",
                "Exclusión de la optimización de batería: Android puede retrasar o detener las tareas en " +
                        "segundo plano de las aplicaciones para ahorrar batería. Esta exclusión permite que la " +
                        "aplicación registre los eventos de pantalla y evalúe el modelo a tiempo. La aplicación le " +
                        "muestra directamente el diálogo del sistema para activarla o, si no está disponible, la " +
                        "lista correspondiente en los ajustes. No otorga a la aplicación acceso a ningún dato " +
                        "adicional. Algunos fabricantes (por ejemplo, Xiaomi o Samsung) aplican restricciones " +
                        "propias que esta exclusión no evita; por ello, según el fabricante de su dispositivo, el " +
                        "registro puede incluir configuraciones adicionales del sistema (por ejemplo, el inicio " +
                        "automático de la aplicación).",
            ),
            p("Puede revocar cualquiera de estos permisos en cualquier momento desde los ajustes de su dispositivo:"),
            bullets(
                "Revocar el acceso a datos de uso detiene el registro de datos y, con ello, las intervenciones " +
                        "visuales. La revocación no extiende el periodo de participación (sección 9).",
                "Revocar el permiso de mostrar sobre otras aplicaciones impide que se muestren las " +
                        "intervenciones visuales. La revocación no extiende el periodo de participación " +
                        "(sección 9).",
                "Revocar el permiso de notificaciones impide que la aplicación le muestre la notificación del " +
                        "estudio y sus avisos.",
                "Revocar la exclusión de la optimización de batería puede retrasar el registro de datos y las " +
                        "intervenciones visuales.",
            ),
            p(
                "Se recomienda mantener los cuatro permisos activos durante todo el periodo de participación. " +
                        "Dado que estos permisos son necesarios para el funcionamiento de la aplicación, si revoca " +
                        "uno o varios de ellos, la aplicación le mostrará un mensaje de alerta que indica cuáles " +
                        "permisos fueron revocados, qué efecto tiene y cómo volver a otorgarlos. La alerta se " +
                        "muestra dentro de la aplicación cada vez que la abre y, si el permiso de notificaciones " +
                        "está otorgado, también en la notificación del estudio, donde idealmente aparece en unos " +
                        "15 minutos, aunque puede tardar más (por ejemplo, mientras el dispositivo está en " +
                        "reposo). Volver a otorgar un permiso es decisión suya: " +
                        "puede ignorar la alerta o solicitar su retiro (sección 10). Revocar un permiso no tiene " +
                        "ninguna otra consecuencia sobre su participación ni sobre el incentivo.",
            ),
        ),
    ),
    // 3
    TermsSection(
        "Datos que registra la aplicación",
        listOf(
            p(
                "Al registrarse, el servidor del estudio le asigna un identificador aleatorio (ID) y la " +
                        "aplicación le solicita su edad, género y carrera universitaria, y las respuestas a la " +
                        "SAS-SV. La edad se ingresa como número; el género y la carrera, como texto libre.",
            ),
            p(
                "Durante su participación, la aplicación registra en segundo plano los instantes de encendido " +
                        "y apagado de pantalla y lee el registro de uso que Android guarda en su dispositivo. A " +
                        "partir de ellos obtiene:",
            ),
            bullets(
                "Instantes de encendido y apagado de pantalla.",
                "Alternancia de uso entre aplicaciones. El nombre de cada aplicación se transforma en su " +
                        "dispositivo mediante una función hash con clave (HMAC-SHA256). La clave es secreta, se " +
                        "genera para cada instalación y se guarda en el almacén seguro del dispositivo (Android " +
                        "Keystore), del cual no puede exportarse ni sale de su dispositivo. Una misma aplicación " +
                        "produce un valor distinto en cada teléfono, y nadie, incluido el investigador, puede " +
                        "recalcular a qué aplicación corresponde cada valor. Este valor se utiliza únicamente " +
                        "dentro de su dispositivo, para contar cuántas aplicaciones distintas se usaron en cada " +
                        "intervalo de tiempo. La propia aplicación SmartUse no se cuenta.",
            ),
            p(
                "Con estos datos, el modelo predictivo calcula en su dispositivo, al desbloquearlo y cada 10 " +
                        "minutos mientras lo usa, si es probable que ocurra alguno de los dos patrones de uso. La " +
                        "aplicación no guarda los resultados del modelo ni un registro de las intervenciones; solo " +
                        "conserva lo necesario para respetar los límites de la sección 5 (la hora de la última " +
                        "intervención de cada patrón y cuántas se han mostrado ese día). También registra " +
                        "localmente su consumo de batería y memoria RAM.",
            ),
            p(
                "Los datos de uso, el historial previo (si lo autoriza, sección 4) y su perfil de uso (sección 4) " +
                        "se guardan únicamente en el almacenamiento privado de la aplicación en su dispositivo y " +
                        "no se envían al servidor en ningún momento. El investigador no tiene acceso a ellos.",
            ),
            p(
                "La aplicación no registra: el nombre de las aplicaciones que usted utiliza, su contenido, " +
                        "mensajes, el contenido de sus notificaciones, ubicación, ni su nombre, correo electrónico " +
                        "o número de teléfono.",
            ),
        ),
    ),
    // 4
    TermsSection(
        "Perfil de uso e historial previo",
        listOf(
            p(
                "Para decidir cuándo mostrar una intervención, la aplicación compara su uso con su propio uso " +
                        "típico (perfil de uso), que calcula en su dispositivo a partir de 3 días de datos. Una " +
                        "vez calculado, el perfil no cambia durante el resto de su participación.",
            ),
            p(
                "Al registrarse, puede autorizar, de forma opcional y separada, que la aplicación lea hasta 3 " +
                        "días del historial que Android ya guarda en su teléfono de los días anteriores a la " +
                        "instalación. Del historial solo se leen los mismos datos descritos en la sección 3, y " +
                        "nunca sale de su dispositivo.",
            ),
            bullets(
                "Si lo autoriza y el historial alcanza 3 días con datos suficientes, el perfil se calcula al " +
                        "instalar la aplicación y las intervenciones pueden mostrarse desde ese momento.",
                "Si lo autoriza, pero el historial no alcanza los 3 días, se usan los días disponibles y se " +
                        "completan con sus primeros días de participación.",
                "Si no lo autoriza, el perfil se calcula con sus primeros 3 días de participación.",
            ),
            p(
                "Durante los días de participación que se usan para calcular el perfil, la aplicación no " +
                        "muestra intervenciones. No autorizar el uso del historial no tiene ninguna consecuencia " +
                        "sobre su participación ni sobre el incentivo.",
            ),
        ),
    ),
    // 5
    TermsSection(
        "Intervención visual",
        listOf(
            p(
                "Cuando el modelo identifica un posible momento de revisiones frecuentes del teléfono (en la " +
                        "próxima hora) o de uso prolongado sin pausa (en los próximos 30 minutos), puede aparecer " +
                        "una intervención visual: una capa gris semitransparente sobre la pantalla con el texto " +
                        "\"Intervención visual de SmartUse para ayudarte a notar tu uso del teléfono\" y un texto " +
                        "que indica cuál de los dos patrones la motivó. Estos textos expresan una posibilidad, no " +
                        "un diagnóstico: el modelo puede equivocarse.",
            ),
            bullets(
                "La intervención no bloquea el uso del dispositivo ni de ninguna aplicación.",
                "Puede cerrarla en cualquier momento con el botón de la esquina superior derecha.",
                "Si no la cierra, se cierra sola a los 10 segundos. El contador en pantalla solo indica ese " +
                        "cierre automático; no es necesario esperarlo.",
                "Aparece como máximo 20 veces al día, con al menos 10 minutos entre una y otra, y como máximo " +
                        "una vez por hora para cada patrón.",
            ),
            p(
                "Cerrar la intervención de inmediato o dejar que se cierre sola no tiene ninguna consecuencia " +
                        "sobre su participación ni sobre el incentivo.",
            ),
        ),
    ),
    // 6
    TermsSection(
        "Datos que se envían y almacenamiento",
        listOf(
            p("Solo se envían al servidor los siguientes datos, en estos momentos:"),
            bullets(
                "Al registrarse: su ID, edad, género, carrera y puntaje de la SAS-SV.",
                "Al finalizar su participación, ya sea al cumplirse el periodo (sección 9) o al retirarse " +
                        "(sección 10): el registro de consumo de batería y memoria RAM y sus respuestas a la " +
                        "encuesta de cierre.",
            ),
            p(
                "Los envíos se realizan mediante conexión cifrada (HTTPS/TLS). Los datos se almacenan en " +
                        "infraestructura de Amazon Web Services (AWS), en la región us-east-1 (Virginia, Estados " +
                        "Unidos), es decir, fuera de territorio guatemalteco, cifrados en reposo (AES-256). El " +
                        "acceso a los datos está restringido exclusivamente al investigador principal, quien ha " +
                        "firmado un acuerdo de confidencialidad. El panel de monitoreo del estudio se utiliza " +
                        "únicamente para verificar participación activa y consumo de recursos, no para " +
                        "inspeccionar datos individuales en bruto.",
            ),
        ),
    ),
    // 7
    TermsSection(
        "Seudonimización",
        listOf(
            p(
                "Sus datos se consideran seudonimizados, no anónimos: el ID no es por sí mismo un dato " +
                        "personal, pero la reidentificación permanece posible por medio del consentimiento " +
                        "informado firmado y, si recibe el incentivo, del registro de acreditación de horas beca. " +
                        "Ambos documentos permanecen bajo custodia exclusiva del investigador principal.",
            ),
        ),
    ),
    // 8
    TermsSection(
        "Uso de los datos",
        listOf(
            p(
                "Los datos de uso se utilizan únicamente dentro de su dispositivo, para calcular su perfil y " +
                        "decidir cuándo mostrar una intervención. Los datos enviados al servidor se utilizan " +
                        "únicamente para verificar la elegibilidad, describir la muestra, evaluar el consumo de " +
                        "recursos de la aplicación y analizar la utilidad, usabilidad y aceptabilidad percibidas " +
                        "del sistema. No se publican a nivel individual, no se comparten con terceros y no se " +
                        "utilizan para ningún otro fin. Cualquier resultado publicado o presentado se expresa " +
                        "únicamente en forma agregada, sin información que permita identificar a un participante.",
            ),
        ),
    ),
    // 9
    TermsSection(
        "Duración de la participación",
        listOf(
            p(
                "La meta de participación es de 14 días, con un máximo de 21 días contados desde que completa " +
                        "su registro en la aplicación. La meta de 14 días no es obligatoria: finalizar su " +
                        "participación antes de ese plazo no afecta su permanencia en el estudio ni el incentivo. " +
                        "El periodo máximo puede acortarse por razones de cronograma del estudio; en ese caso se le " +
                        "notificará dentro de la aplicación.",
            ),
            p(
                "Al cumplirse el periodo máximo, la aplicación deja de registrar datos y de mostrar " +
                        "intervenciones y le presenta la encuesta de cierre. Al completarla, envía los datos " +
                        "descritos en la sección 6 y elimina los datos almacenados en su dispositivo, como se " +
                        "describe en la sección 10.",
            ),
        ),
    ),
    // 10
    TermsSection(
        "Retiro y eliminación de datos",
        listOf(
            p(
                "Usted puede retirarse en cualquier momento, sin necesidad de justificarlo y sin penalización, " +
                        "desde la aplicación o comunicándolo directamente al investigador. Al retirarse desde la " +
                        "aplicación:",
            ),
            bullets(
                "Se detienen el registro de datos y las intervenciones visuales.",
                "Se le presenta la encuesta de cierre.",
                "Al completarla, se envían sus respuestas y el registro de consumo de batería y memoria RAM.",
                "Se eliminan los datos almacenados en su dispositivo, incluido el historial previo, excepto " +
                        "el registro de consumo de batería y memoria RAM, ya enviado, que se elimina al " +
                        "desinstalar la aplicación. Se muestra únicamente una pantalla final con la opción de " +
                        "desinstalar la aplicación y los datos de contacto del investigador.",
            ),
            p(
                "Desinstalar la aplicación sin solicitar el retiro detiene el registro de datos y las " +
                        "intervenciones, y elimina de inmediato todos los datos almacenados en su dispositivo, " +
                        "incluidos el registro de consumo de batería y memoria RAM y las respuestas a la encuesta de " +
                        "cierre que aún no se hayan enviado. Los " +
                        "datos enviados al registrarse se conservan en el servidor.",
            ),
            p(
                "Puede solicitar la eliminación de los datos ya enviados al servidor (los del registro, el " +
                        "consumo de batería y memoria RAM, y la encuesta de cierre) comunicándose con el " +
                        "investigador (sección 15). La solicitud se atiende si se presenta hasta 48 horas después " +
                        "de finalizar su participación o hasta el 4 de noviembre de 2026, lo que ocurra después. " +
                        "Pasado ese plazo, los datos forman parte del análisis agregado del estudio.",
            ),
            p(
                "Independientemente de lo anterior, todos los datos en bruto se eliminan de forma definitiva " +
                        "en una fecha posterior a la aprobación formal del trabajo de graduación. Las estadísticas " +
                        "agregadas que no permiten identificar a ningún participante permanecen como parte del " +
                        "documento final.",
            ),
        ),
    ),
    // 11
    TermsSection(
        "Incentivo",
        listOf(
            p(
                "La participación se reconoce con 20 horas de beca, avaladas por la dirección de la carrera de " +
                        "CCTI, exclusivamente para participantes de dicha carrera. El incentivo se otorga a quienes " +
                        "cumplen los criterios de elegibilidad (sección 2), firman el consentimiento informado e " +
                        "instalan la aplicación. Para registrar la acreditación se solicita una captura de la " +
                        "pantalla principal de la aplicación que muestre su ID de participante. El incentivo no " +
                        "depende de un número mínimo de días de participación y no se ve afectado por el retiro, " +
                        "por no autorizar el historial previo, por cerrar las intervenciones ni por la revocación " +
                        "de permisos. Estudiantes de otras carreras pueden participar en igualdad de condiciones, " +
                        "sin este incentivo.",
            ),
        ),
    ),
    // 12
    TermsSection(
        "Funciones disponibles para usted",
        listOf(
            p(
                "Mientras su participación está activa, dentro de la aplicación puede consultar un resumen " +
                        "agregado de los datos registrados sobre su uso, consultar estos términos y solicitar su " +
                        "retiro. Después del retiro, la aplicación elimina sus datos locales y muestra únicamente " +
                        "la pantalla final descrita en la sección 10; el resumen y estos términos dejan de estar " +
                        "disponibles en la aplicación.",
            ),
        ),
    ),
    // 13
    TermsSection(
        "Uso de recursos del dispositivo y limitaciones",
        listOf(
            p(
                "La aplicación funciona en segundo plano, registra eventos de pantalla y ejecuta el modelo " +
                        "predictivo en su dispositivo, lo que consume una cantidad de batería, memoria y, en los " +
                        "envíos al servidor, datos móviles o Wi-Fi. Al estar excluida de la optimización de " +
                        "batería, sus tareas se ejecutan sin las restricciones de ahorro de batería de Android, " +
                        "por lo que su consumo de batería puede ser mayor que el de una aplicación sujeta a dicha " +
                        "optimización. Al ser un prototipo de investigación, puede presentar fallas de " +
                        "funcionamiento; el modelo también puede equivocarse al decidir cuándo mostrar una " +
                        "intervención. Si la aplicación afecta el uso normal de su dispositivo, puede " +
                        "desinstalarla en cualquier momento y comunicarlo al investigador.",
            ),
        ),
    ),
    // 14
    TermsSection(
        "Modificaciones a estos términos",
        listOf(
            p(
                "Cualquier modificación a estos términos se notificará dentro de la aplicación antes de su " +
                        "entrada en vigor. Las modificaciones no pueden ampliar el uso de sus datos más allá de lo " +
                        "establecido en el consentimiento informado y en el protocolo revisado por el CEIFI.",
            ),
        ),
    ),
    // 15
    TermsSection(
        "Contacto",
        listOf(
            p(
                "Para cualquier pregunta sobre el estudio, sus datos o su participación: José Rodrigo " +
                        "Marchena Molina, investigador principal — ${StudyContact.EMAIL} — ${StudyContact.PHONE}",
            ),
        ),
    ),
)

/** The full terms, unscrolled — callers own the scroll container. */
@Composable
fun TermsDocument(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(TERMS_TITLE, style = MaterialTheme.typography.titleMedium)
            Text(TERMS_SUBTITLE, style = MaterialTheme.typography.bodySmall)
        }
        TERMS_SECTIONS.forEachIndexed { index, section ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${index + 1}. ${section.title}", style = MaterialTheme.typography.titleSmall)
                section.blocks.forEach { block ->
                    when (block) {
                        is TermsBlock.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyMedium)
                        is TermsBlock.Bullets -> block.items.forEach { BulletItem(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BulletItem(text: String) {
    Row(Modifier.padding(start = 8.dp)) {
        Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
