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
 * The study's terms (data collection group), shared by the setup consent step
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
private const val TERMS_SUBTITLE = "Grupo de recolección de datos · Versión 1.0 — 24/09/2026"

/** Shown only where the participant actually accepts (setup), not on the read-only page. */
const val TERMS_ACCEPTANCE =
    "He leído estos términos y condiciones, son consistentes con el consentimiento informado " +
            "que firmé, y acepto el uso de la aplicación bajo estas condiciones."

private val TERMS_SECTIONS = listOf(
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
                        "ni una herramienta de diagnóstico. El uso de la aplicación está sujeto al consentimiento " +
                        "informado firmado por usted; en caso de discrepancia entre estos términos y dicho " +
                        "consentimiento, prevalece el consentimiento informado.",
            ),
        ),
    ),
    TermsSection(
        "Requisitos y permisos",
        listOf(
            p("Para utilizar esta aplicación como participante del estudio se requiere:"),
            bullets(
                "Ser estudiante activo de la Universidad del Valle de Guatemala, entre 18 y 24 años de edad.",
                "Contar con un dispositivo Android 13 o superior.",
                "Haber firmado el consentimiento informado del grupo de recolección de datos.",
            ),
            p(
                "Durante el registro dentro de la aplicación, se le solicitará otorgar tres permisos del " +
                        "sistema, necesarios para el funcionamiento del estudio:",
            ),
            bullets(
                "Acceso a datos de uso (PACKAGE_USAGE_STATS): permite a la aplicación leer el registro de uso " +
                        "que Android mantiene en su dispositivo, descrito en la sección 3. Sin este permiso, la " +
                        "aplicación no puede obtener esos datos.",
                "Notificaciones: permite mostrar una notificación que indica que su participación en el " +
                        "estudio está activa, así como avisos del estudio (por ejemplo, el fin de su periodo de " +
                        "participación, la confirmación de eliminación de datos o cambios a estos términos). En " +
                        "Android 14 o superior puede descartar esta notificación; la aplicación la vuelve a " +
                        "mostrar periódicamente.",
                "Exclusión de la optimización de batería: Android puede retrasar o detener las tareas en " +
                        "segundo plano de las aplicaciones para ahorrar batería. Esta exclusión permite que las " +
                        "lecturas y envíos programados de la aplicación se realicen a tiempo. La aplicación le " +
                        "muestra directamente el diálogo del sistema para activarla o, si no está disponible, la " +
                        "lista correspondiente en los ajustes. No otorga a la aplicación acceso a ningún dato " +
                        "adicional. Algunos fabricantes (por ejemplo, Xiaomi o Samsung) aplican restricciones " +
                        "propias que esta exclusión no evita; por ello, según el fabricante de su dispositivo, el " +
                        "registro puede incluir configuraciones adicionales del sistema (por ejemplo, el inicio " +
                        "automático de la aplicación).",
            ),
            p("Puede revocar cualquiera de estos permisos en cualquier momento desde los ajustes de su dispositivo:"),
            bullets(
                "Revocar el acceso a datos de uso detiene la lectura de datos por parte de la aplicación. La " +
                        "revocación no extiende el periodo de participación (sección 7).",
                "Revocar el permiso de notificaciones impide que la aplicación le muestre la notificación del " +
                        "estudio y sus avisos.",
                "Revocar la exclusión de la optimización de batería puede retrasar las lecturas y envíos de " +
                        "datos.",
            ),
            p(
                "Se recomienda mantener los tres permisos activos durante todo el periodo de participación. " +
                        "Dado que estos permisos son necesarios para el funcionamiento de la aplicación, si revoca " +
                        "uno o varios de ellos, la aplicación le mostrará un mensaje de alerta que indica cuáles " +
                        "permisos fueron revocados, qué efecto tiene y cómo volver a otorgarlos. La alerta se " +
                        "muestra dentro de la aplicación cada vez que la abre y, si el permiso de notificaciones " +
                        "está otorgado, también en la notificación del estudio, donde puede aparecer con un " +
                        "retraso de hasta aproximadamente 24 horas. Volver a otorgar un permiso es decisión suya: " +
                        "puede ignorar la alerta o solicitar su retiro (sección 8). Revocar un permiso no tiene " +
                        "ninguna otra consecuencia sobre su participación ni sobre el incentivo.",
            ),
        ),
    ),
    TermsSection(
        "Datos que registra la aplicación",
        listOf(
            p(
                "Al registrarse, el servidor del estudio le asigna un identificador aleatorio (ID) y la " +
                        "aplicación le solicita su edad, género y carrera universitaria. La edad se ingresa como " +
                        "número; el género y la carrera, como texto libre.",
            ),
            p(
                "La aplicación no registra de forma continua. Android guarda por su cuenta, en su " +
                        "dispositivo, un registro de los momentos de encendido y apagado de pantalla y de la " +
                        "aplicación que está en primer plano. La aplicación lee ese registro aproximadamente cada " +
                        "6 horas y, a partir de él, obtiene:",
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
                        "intervalo de tiempo.",
            ),
            p(
                "Durante el estudio, el registro leído (marcas de tiempo y valores hash de las aplicaciones) " +
                        "y los intervalos de tiempo calculados a partir de él se guardan en el almacenamiento " +
                        "privado de la aplicación en su dispositivo. Al servidor se envían únicamente sus datos " +
                        "de registro (ID, edad, género y carrera) y, para cada intervalo de tiempo: un " +
                        "identificador del intervalo, su hora de inicio, si el dispositivo estuvo en uso durante " +
                        "el intervalo y el número de aplicaciones distintas utilizadas en él. Los valores hash de " +
                        "las aplicaciones y las marcas de tiempo de cada evento individual no se envían. Los " +
                        "datos almacenados en su dispositivo se eliminan " +
                        "al cumplirse el periodo máximo de participación (sección 7), al solicitar cualquiera de " +
                        "las dos modalidades de retiro (sección 8) o al desinstalar la aplicación.",
            ),
            p(
                "La aplicación no registra: el nombre de las aplicaciones que usted utiliza, su contenido, " +
                        "mensajes, el contenido de sus notificaciones, ubicación, ni su nombre, correo electrónico " +
                        "o número de teléfono. En este grupo la aplicación no incluye ningún mecanismo de " +
                        "intervención: no bloquea, interrumpe ni modifica el uso de su dispositivo.",
            ),
        ),
    ),
    TermsSection(
        "Transmisión y almacenamiento",
        listOf(
            p(
                "Los datos descritos en la sección 3 se envían al servidor después de cada lectura " +
                        "(aproximadamente cada 6 horas) mediante conexión cifrada (HTTPS/TLS). Se almacenan en " +
                        "infraestructura de Amazon Web Services (AWS), en la región us-east-1 (Virginia, Estados " +
                        "Unidos), es decir, fuera de territorio guatemalteco, cifrados en reposo (AES-256). El " +
                        "acceso a los datos está restringido exclusivamente al investigador principal, quien ha " +
                        "firmado un acuerdo de confidencialidad. El panel de monitoreo del estudio se utiliza " +
                        "únicamente para verificar participación activa y volumen de datos recibidos, no para " +
                        "inspeccionar datos individuales en bruto.",
            ),
        ),
    ),
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
    TermsSection(
        "Uso de los datos",
        listOf(
            p(
                "Los datos tienen como único propósito el entrenamiento y la evaluación del modelo predictivo " +
                        "del estudio. No se publican a nivel individual, no se comparten con terceros y no se " +
                        "utilizan para ningún otro fin. Cualquier resultado publicado o presentado se expresa " +
                        "únicamente en forma agregada, sin información que permita identificar a un participante.",
            ),
        ),
    ),
    TermsSection(
        "Duración de la participación",
        listOf(
            p(
                "La meta de participación es de 10 días, con un máximo de 21 días contados desde que completa " +
                        "su registro en la aplicación. La meta de 10 días no es obligatoria: finalizar su " +
                        "participación antes de ese plazo no afecta su permanencia en el estudio ni el incentivo. " +
                        "El periodo máximo puede acortarse por razones de cronograma del estudio; en ese caso se le " +
                        "notificará dentro de la aplicación. Al cumplirse el periodo máximo, la aplicación detiene " +
                        "automáticamente la lectura y el envío de datos, y elimina los datos almacenados en su " +
                        "dispositivo.",
            ),
        ),
    ),
    TermsSection(
        "Retiro y eliminación de datos",
        listOf(
            p(
                "Usted puede retirarse en cualquier momento, sin necesidad de justificarlo y sin penalización. " +
                        "La aplicación ofrece dos opciones de retiro, disponibles como acciones separadas:",
            ),
            bullets(
                "Retiro Parcial: la aplicación envía al servidor los datos ya leídos que aún no se habían " +
                        "enviado y, a continuación, deja de leer y enviar nuevos datos. Los datos enviados al " +
                        "servidor se conservan y forman parte del análisis.",
                "Retiro Total: la aplicación deja de leer y enviar datos, y se eliminan del servidor los " +
                        "datos ya enviados. Se le notificará cuando la eliminación se haya completado. El Retiro " +
                        "Total solo está disponible antes del inicio del ajuste fino del modelo (Fase 4 del " +
                        "estudio). A partir de ese punto sus datos forman parte de un modelo entrenado y no pueden " +
                        "extraerse de forma individual, por lo que la opción de Retiro Total se deshabilita y " +
                        "únicamente es posible el Retiro Parcial.",
            ),
            p(
                "Con cualquiera de las dos modalidades, la aplicación elimina los datos almacenados en su " +
                        "dispositivo y muestra únicamente una pantalla final con la opción de desinstalarla y los " +
                        "datos de contacto del investigador. También puede solicitar cualquiera de las dos " +
                        "modalidades comunicándolo directamente al investigador.",
            ),
            p(
                "Desinstalar la aplicación sin solicitar un retiro detiene la lectura y el envío de datos y " +
                        "elimina los datos almacenados en su dispositivo, incluidos los que aún no se habían " +
                        "enviado (hasta aproximadamente 6 horas de datos). Los datos ya enviados al servidor se " +
                        "conservan, igual que en un Retiro Parcial, pero el servidor no recibe aviso de su salida " +
                        "del estudio. Para eliminar los datos enviados debe solicitar el Retiro Total antes de " +
                        "desinstalar la aplicación, o comunicarlo al investigador.",
            ),
            p(
                "Independientemente de lo anterior, todos los datos en bruto se eliminan de forma definitiva " +
                        "en una fecha posterior a la aprobación formal del trabajo de graduación. Las estadísticas " +
                        "agregadas que no permiten identificar a ningún participante permanecen como parte del " +
                        "documento final.",
            ),
        ),
    ),
    TermsSection(
        "Incentivo",
        listOf(
            p(
                "La participación se reconoce con 20 horas de beca, avaladas por la dirección de la carrera de " +
                        "CCTI, exclusivamente para participantes de dicha carrera. El incentivo se otorga a quienes " +
                        "participan en el estudio: cumplen los criterios de elegibilidad (sección 2), firman el " +
                        "consentimiento informado e instalan la aplicación. No depende de un número mínimo de días " +
                        "de participación y no se ve afectado por el retiro, en ninguna de sus modalidades, ni por " +
                        "la revocación de permisos. Estudiantes de otras carreras pueden participar en igualdad de " +
                        "condiciones, sin este incentivo.",
            ),
        ),
    ),
    TermsSection(
        "Participación posterior en el grupo de despliegue",
        listOf(
            p(
                "Participar en este grupo no le impide integrarse en una fase posterior al grupo de despliegue " +
                        "del estudio, siempre que cumpla en ese momento los criterios adicionales de ese grupo. Esa " +
                        "participación requiere un consentimiento informado distinto.",
            ),
        ),
    ),
    TermsSection(
        "Funciones disponibles para usted",
        listOf(
            p(
                "Mientras su participación está activa, dentro de la aplicación puede consultar un resumen " +
                        "agregado de los datos registrados sobre su uso, consultar estos términos y solicitar su " +
                        "retiro. Después de cualquiera de las dos modalidades de retiro, la aplicación elimina sus " +
                        "datos locales y muestra únicamente la pantalla final descrita en la sección 8; el resumen " +
                        "y estos términos dejan de estar disponibles en la aplicación.",
            ),
        ),
    ),
    TermsSection(
        "Uso de recursos del dispositivo y limitaciones",
        listOf(
            p(
                "La aplicación realiza lecturas y envíos periódicos en segundo plano y consume una cantidad de " +
                        "batería, memoria y datos móviles o Wi-Fi. Al estar excluida de la optimización de " +
                        "batería, sus tareas programadas se ejecutan sin las restricciones de ahorro de batería de " +
                        "Android, por lo que su consumo de batería puede ser mayor que el de una aplicación sujeta " +
                        "a dicha optimización. Al ser un prototipo de investigación, puede presentar fallas de " +
                        "funcionamiento. Si la aplicación afecta el uso normal de su dispositivo, puede " +
                        "desinstalarla en cualquier momento y comunicarlo al investigador.",
            ),
        ),
    ),
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
