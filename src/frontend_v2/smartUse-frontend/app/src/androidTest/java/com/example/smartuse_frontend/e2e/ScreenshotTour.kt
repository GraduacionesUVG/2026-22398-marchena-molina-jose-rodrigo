package com.example.smartuse_frontend.e2e

import android.content.ContentValues
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smartuse_frontend.MainActivity
import com.example.smartuse_frontend.data.db.CanonicalDbHelper
import com.example.smartuse_frontend.data.db.RAW_LOG_EXPORT_FILE
import com.example.smartuse_frontend.data.db.RawCaptureDbHelper
import com.example.smartuse_frontend.forms.FormDefinitions
import com.example.smartuse_frontend.forms.QuestionKind
import com.example.smartuse_frontend.intervention.InterventionRuntime
import com.example.smartuse_frontend.intervention.OverlayCue
import com.example.smartuse_frontend.jobs.BackgroundJobScheduler
import com.example.smartuse_frontend.jobs.JobRuntime
import com.example.smartuse_frontend.notification.StudyNotice
import com.example.smartuse_frontend.notification.studyNoticeContent
import com.example.smartuse_frontend.study.SENDING_TITLE
import com.example.smartuse_frontend.study.THANKS_TITLE
import com.example.smartuse_frontend.study.OPTED_OUT_TEXT
import com.example.smartuse_frontend.data.AppContainer
import com.example.smartuse_frontend.ui.common.DebugUi
import com.example.smartuse_frontend.ui.endforms.END_FORMS_CONFIRM
import com.example.smartuse_frontend.ui.endforms.END_FORMS_NEXT
import com.example.smartuse_frontend.ui.endforms.END_FORMS_SEND
import com.example.smartuse_frontend.ui.endforms.choiceTag
import com.example.smartuse_frontend.ui.endforms.textTag
import com.example.smartuse_frontend.ui.setup.EARLIER_USAGE_REJECT
import com.example.smartuse_frontend.ui.setup.EARLIER_USAGE_TITLE
import com.example.smartuse_frontend.ui.terms.TERMS_ACCEPTANCE
import com.example.smartuse_frontend.ui.welcome.INTERVENTIONS_ACTIVE
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.random.Random

/**
 * Not a test: a walk through every page that saves a PNG of each to
 * [DIR] on the device, for the thesis figures. Skipped unless the
 * instrumentation argument `screenshots=true` is set
 * (`python testlab/run_screenshots.py` sets it and pulls the folder).
 * Each method is a fresh install (orchestrator); file names sort in the
 * order a participant meets the pages.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTour {

    @get:Rule(order = 0) val device = DeviceStateRule()
    @get:Rule(order = 1) val backend = FakeBackend()
    @get:Rule(order = 2) val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before fun setUp() {
        assumeTrue("screenshot tour: run with screenshots=true", InstrumentationRegistry.getArguments().getString("screenshots") == "true")
        DebugUi.showTools = false
        DeviceShell.run("mkdir -p $DIR")
        // The study's zone (Test Lab devices run on UTC): dates and the day's chart as a participant sees them.
        DeviceShell.run("cmd alarm set-timezone $ZONE")
        Thread.sleep(1_000)
        TimeZone.setDefault(null) // re-read the system zone in this process
    }

    @After fun tearDown() {
        DebugUi.showTools = true
        DebugUi.assumeEarlierUsage = false
    }

    // --- helpers -------------------------------------------------------------

    /** Settles the UI, then saves the whole screen (overlays and system UI included). */
    private fun shot(name: String, settleMs: Long = 600, compose: Boolean = true) {
        if (compose) runCatching { this.compose.waitForIdle() }
        Thread.sleep(settleMs)
        DeviceShell.run("screencap -p $DIR/$name.png")
    }

    private fun waitForSubstring(text: String, timeoutMs: Long = 15_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun back() = compose.onNodeWithContentDescription("Atrás").performClick()

    private fun grantAll() {
        DeviceShell.wakeAndUnlock()
        DeviceShell.grantUsageAccess()
        DeviceShell.grantNotifications()
        DeviceShell.exemptFromBatteryOptimization()
        DeviceShell.grantOverlay()
    }

    private fun launchWithServiceRunning(): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            compose.waitUntil(15_000) { StudyNotification.isForegroundService(context) }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("the study service never reached the foreground", e)
        }
        return scenario
    }

    private fun intervene(cue: OverlayCue): Boolean {
        var shown = false
        instrumentation.runOnMainSync { shown = InterventionRuntime.coordinator(context).onIntervention(cue) }
        return shown
    }

    /** A zone where it is now about 21:00, so "today" is a whole day whenever the tour runs. */
    private fun evening() {
        val utcHour = Instant.now().atZone(ZoneOffset.UTC).hour
        val offset = Math.floorMod(21 - utcHour + 12, 24) - 12 // -12..+11
        DeviceShell.run("cmd alarm set-timezone Etc/GMT${if (offset > 0) "-" else "+"}${abs(offset)}") // POSIX sign
        Thread.sleep(1_000)
        TimeZone.setDefault(null)
    }

    /**
     * Made-up usage from midnight [days] days ago to a couple of minutes ago,
     * written as capture would have left it, without UsageStatsManager: the
     * raw log (screen on → apps → screen off, what the CSV export reads) and
     * the canonical epochs built from the same sessions (what the charts read).
     * Nights are one long no-use epoch; days alternate 2–25 min of use with
     * 8–70 min without. App hashes come from a fixed pool of fake ones.
     */
    private fun seedUsage(subject: String, days: Int) {
        val zone = ZoneId.systemDefault()
        val first = LocalDate.now(zone).minusDays(days.toLong()).atStartOfDay(zone)
        val end = System.currentTimeMillis() - 2 * 60_000
        val random = Random(7)
        val minute = 60_000L
        val sha = MessageDigest.getInstance("SHA-256")
        val pool = List(14) { n -> sha.digest("app-$n".toByteArray()).joinToString("") { "%02x".format(it) } }
        val raw = RawCaptureDbHelper(context).writableDatabase
        val canonical = CanonicalDbHelper(context).writableDatabase
        var i = 0
        fun epoch(start: Long, use: Boolean, apps: Int) = canonical.insert(
            "epochs", null,
            ContentValues().apply {
                put("epoch_id", "shot-${i++}"); put("subject_id", subject); put("start_ts", start)
                put("is_use", if (use) 1 else 0); put("distinct_app_count", apps); put("source", "device_capture")
            },
        )
        fun event(ts: Long, type: String, hash: String? = null) = raw.insert(
            "raw_capture_log", null,
            ContentValues().apply {
                put("event_ts", ts); put("event_type", type)
                if (hash != null) put("app_hash", hash) else putNull("app_hash")
            },
        )
        raw.beginTransaction(); canonical.beginTransaction()
        try {
            var t = first.toInstant().toEpochMilli()
            epoch(t, use = false, apps = 0)
            while (true) {
                val local = Instant.ofEpochMilli(t).atZone(zone)
                fun sevenAm(day: LocalDate) = day.atTime(7, 0).atZone(zone).toInstant().toEpochMilli() + random.nextLong(0, 60) * minute
                // The gap without use: the night until ~07:00, else 8–70 min.
                t = when {
                    local.hour < 7 -> sevenAm(local.toLocalDate())
                    local.hour >= 23 -> sevenAm(local.toLocalDate().plusDays(1))
                    else -> t + random.nextLong(8, 70) * minute
                }
                val stop = t + random.nextLong(2, 25) * minute
                if (stop >= end) break
                // One use session: screen on, 1–6 apps, screen off.
                val apps = pool.shuffled(random).take(random.nextInt(1, 7))
                event(t, "SCREEN_INTERACTIVE")
                apps.forEachIndexed { k, hash -> event(t + (k + 1) * (stop - t) / (apps.size + 1), "ACTIVITY_RESUMED", hash) }
                event(stop, "SCREEN_NON_INTERACTIVE")
                epoch(t, use = true, apps = apps.size)
                epoch(stop, use = false, apps = 0) // opened by the screen-off, open until the next one
                t = stop
            }
            raw.setTransactionSuccessful(); canonical.setTransactionSuccessful()
        } finally {
            raw.endTransaction(); canonical.endTransaction()
            raw.close(); canonical.close()
        }
    }

    /** Copies a file from the app's own storage into [DIR] through the shell, so the run's results carry it. */
    private fun pullOut(file: File, name: String) {
        val (stdout, stdin) = instrumentation.uiAutomation.executeShellCommandRw("tee $DIR/$name")
        ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { out -> file.inputStream().use { it.copyTo(out) } }
        ParcelFileDescriptor.AutoCloseInputStream(stdout).use { it.readBytes() } // until tee is done
    }

    // --- the tour --------------------------------------------------------------

    /** Setup from the terms to the home screen, then every page reachable from home. */
    @Test
    fun a_setupAndHome() {
        grantAll()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForText("Acepto y continúo")
            shot("01_terms_top")
            compose.onNode(hasText(TERMS_ACCEPTANCE)).performScrollTo()
            shot("02_terms_end")
            compose.tapText("Acepto y continúo")

            compose.waitForText("Datos del participante")
            shot("03_profile_empty")
            compose.onNode(hasSetTextAction() and hasText("Edad")).performTextInput("21")
            compose.onNode(hasSetTextAction() and hasText("Género")).performTextInput("Femenino")
            compose.onNode(hasSetTextAction() and hasText("Carrera universitaria")).performTextInput("Ingeniería en Sistemas")
            val score = compose.onNode(hasSetTextAction() and hasText("Puntaje SAS-SV"))
            score.performTextInput("9")
            compose.waitForText("Debe estar entre 10 y 60")
            shot("04_profile_score_error")
            score.performTextClearance()
            score.performTextInput("37")
            shot("05_profile_filled")
            compose.scrollToAndTapText("Continuar")

            compose.waitForText("Permiso de acceso al uso"); shot("06_perm_usage_access"); compose.tapText("Continuar")
            compose.waitForText("Permiso de notificaciones"); shot("07_perm_notifications"); compose.tapText("Continuar")
            compose.waitForText("Ejecución en segundo plano"); shot("08_perm_battery"); compose.tapText("Continuar")
            compose.waitForText("Mostrar sobre otras apps"); shot("09_perm_overlay"); compose.tapText("Continuar")
            runCatching { compose.waitForText("Inicio automático", 3_000) }.onSuccess { shot("10_perm_autostart"); compose.tapText("Continuar") }
            runCatching { compose.waitForText(EARLIER_USAGE_TITLE, 5_000) }.onSuccess { shot("11_earlier_usage"); compose.tapText(EARLIER_USAGE_REJECT) }

            compose.waitForText("Estado", 30_000)
            runCatching { waitForSubstring("comenzarán a más tardar", 10_000) }
            shot("20_home", settleMs = 1_500)

            DeviceShell.run("cmd statusbar expand-notifications")
            shot("21_study_notification", settleMs = 1_500, compose = false)
            DeviceShell.run("cmd statusbar collapse")
            Thread.sleep(800)

            compose.tapText("Estado")
            compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription("Atrás")).fetchSemanticsNodes().isNotEmpty() }
            shot("22_status")
            back()

            compose.tapText("Resumen de datos")
            compose.waitForText("Tu perfil")
            shot("23_data_profile")
            back()

            compose.tapText("Términos y condiciones")
            Thread.sleep(500)
            shot("25_terms_page")
            back()

            compose.tapText("Retirarse del estudio")
            compose.waitForText("Retirarme")
            shot("26_optout")
            compose.tapText("Retirarme")
            compose.waitForText("Confirmar retiro")
            shot("27_optout_confirm")
            compose.tapText("Cancelar")
        }
    }

    /** Data → Uso with a seeded day, so the charts have something to show. */
    @Test
    fun b_dataUsage() {
        evening()
        Seed.registeredSession(context, backend.token, signupHoursAgo = 0)
        grantAll()
        DeviceShell.revokeUsageAccess() // only the seeded day shows, never the device's own usage
        seedUsage("u-e2e", days = 0)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForText("Resumen de datos", 30_000)
            compose.tapText("Resumen de datos")
            compose.waitForText("Uso")
            compose.tapText("Uso")
            compose.waitForText("Épocas de Uso/No-Uso")
            shot("24_data_usage", settleMs = 1_500) // both charts fit on one screen
        }
    }

    /** The overlay over the launcher, for each cue. */
    @Test
    fun c_interventions() {
        Seed.registeredSession(context, backend.token)
        grantAll()
        launchWithServiceRunning().use {
            DeviceShell.goHome()
            Thread.sleep(1_500)
            listOf(OverlayCue.F to "40_intervention_frequent", OverlayCue.S to "41_intervention_sustained", OverlayCue.BOTH to "42_intervention_both")
                .forEach { (cue, name) ->
                    DeviceShell.wakeAndUnlock()
                    await("the $cue overlay shown", 20_000, pollMs = 1_000) { true.takeIf { intervene(cue) } }
                    await("the overlay on screen") { true.takeIf { OverlayOnScreen.isUp() } }
                    shot(name, settleMs = 1_200, compose = false)
                    DeviceShell.screenOff()
                    await("the overlay gone") { true.takeIf { OverlayOnScreen.isGone() } }
                    Thread.sleep(1_000)
                }
        }
    }

    /** The end-forms reminder card at an unlock after the study ended. */
    @Test
    fun d_reminderCard() {
        Seed.registeredSession(context, backend.token)
        grantAll()
        launchWithServiceRunning().use {
            runBlocking { JobRuntime.studyEnder(context, BackgroundJobScheduler(context)).end() }
            StudyNotification.awaitText(context, studyNoticeContent(StudyNotice.FormsPending).text)
            DeviceShell.goHome()
            // An unlock is sometimes missed on the emulator: try a few.
            val up = (1..3).any {
                DeviceShell.screenOff()
                Thread.sleep(1_500)
                DeviceShell.wakeAndUnlock()
                runCatching { await("the reminder card at unlock", 15_000) { true.takeIf { OverlayOnScreen.isReminderCardUp() } } }.isSuccess
            }
            check(up) { "the reminder card never showed after 3 unlocks" }
            shot("50_reminder_card", settleMs = 1_200, compose = false)
        }
    }

    /** Day 21: every end-form section, answered, then the confirmation and the thanks. */
    @Test
    fun e_endForms() {
        Seed.registeredSession(context, backend.token, signupHoursAgo = 21L * 24 + 1)
        Seed.canonicalAndRawDatabases(context)
        grantAll()
        val texts = mapOf(
            "d8_3" to "Ahora reviso menos el teléfono antes de dormir.",
            "d12_1" to "Gracias por el estudio.",
        )
        ActivityScenario.launch(MainActivity::class.java).use {
            val sections = FormDefinitions.USABILITY.sections
            compose.waitForText(FormDefinitions.USABILITY.title)
            sections.forEachIndexed { i, section ->
                compose.waitForText(section.title)
                section.questions.forEachIndexed { j, q ->
                    when (val kind = q.kind) {
                        is QuestionKind.SingleChoice -> {
                            val option = kind.options[(i + j + 2) % kind.options.size]
                            compose.onNodeWithTag(choiceTag(q.id, option.value)).performScrollTo().performClick()
                        }
                        is QuestionKind.OpenText -> texts[q.id]?.let {
                            compose.onNodeWithTag(textTag(q.id)).performScrollTo().performTextInput(it)
                        }
                    }
                }
                // Back to the top of the page for the picture.
                runCatching { compose.onNode(hasText(section.title)).performScrollTo() }
                shot("60_form_section_%02d".format(i + 1))
                compose.scrollToAndTapText(if (i == sections.lastIndex) END_FORMS_SEND else END_FORMS_NEXT)
            }
            compose.waitForText(END_FORMS_CONFIRM)
            shot("61_form_send_confirm")
            compose.tapText(END_FORMS_CONFIRM)
            compose.waitForText(THANKS_TITLE, timeoutMs = 60_000)
            shot("63_end_thanks_completed", settleMs = 1_000)
        }
    }

    /** The "sending" screen, held by a backend that refuses. */
    @Test
    fun f_sending() {
        Seed.endedStudy(context, backend.token)
        Seed.filledEndForms(context)
        backend.respondAlways("POST /final", 500)
        DeviceShell.wakeAndUnlock()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.submitDraftedEndForms()
            compose.waitForText(SENDING_TITLE)
            shot("62_end_sending", settleMs = 1_000)
        }
    }

    /** The thanks screen after an opt-out. */
    @Test
    fun g_optOutThanks() {
        Seed.registeredSession(context, backend.token)
        Seed.filledEndForms(context)
        DeviceShell.grantNotifications()
        DeviceShell.wakeAndUnlock()
        runBlocking { AppContainer(context).optOutRepository.optOut() }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.submitDraftedEndForms()
            compose.waitForText(THANKS_TITLE, timeoutMs = 60_000)
            compose.waitForText(OPTED_OUT_TEXT)
            shot("64_end_thanks_optout", settleMs = 1_000)
        }
    }

    /**
     * The Data → Uso flow over a few backfilled days (no UsageStatsManager):
     * today's charts, the date picker, an earlier day, the CSV export (share
     * sheet), and the exported CSV itself, saved next to the screenshots.
     */
    @Test
    fun h_dataDaysAndExport() {
        evening()
        val days = 3
        Seed.registeredSession(context, backend.token, signupHoursAgo = days * 24L + 21)
        grantAll()
        DeviceShell.revokeUsageAccess() // capture and decision points skip: only the seeded data shows
        seedUsage("u-e2e", days)
        val today = LocalDate.now(ZoneId.systemDefault())
        val dayLabel = DateTimeFormatter.ofPattern("EEEE d MMM yyyy", Locale.forLanguageTag("es"))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForText("Resumen de datos", 30_000)
            compose.tapText("Resumen de datos")
            compose.waitForText("Uso")
            compose.tapText("Uso")
            compose.waitForText("Épocas de Uso/No-Uso")
            shot("70_data_today", settleMs = 1_500)

            compose.tapText(today.format(dayLabel))
            compose.waitForText("Aceptar")
            shot("71_date_picker")
            // An earlier day of this month (the picker opens on this month); today if there is none.
            val pick = (2 downTo 1).map { today.minusDays(it.toLong()) }.firstOrNull { it.month == today.month } ?: today
            // A day cell's semantics is its full date in the device's language ("viernes, 6 de noviembre de 2026").
            val dayCell = SemanticsMatcher("the day cell for $pick") { node ->
                val label = (node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()).joinToString(" ")
                Regex("\\b${pick.dayOfMonth}\\b").containsMatchIn(label) && label.contains(pick.year.toString())
            }
            compose.onAllNodes(dayCell and hasClickAction()).onFirst().performClick()
            shot("72_date_picker_selected")
            compose.tapText("Aceptar")
            compose.waitForText(pick.format(dayLabel))
            shot("73_data_earlier_day", settleMs = 1_500)

            compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(4)
            compose.tapText("Exportar registro sin procesar (CSV)")
            shot("74_csv_share_sheet", settleMs = 2_500, compose = false)
            pullOut(File(context.cacheDir, RAW_LOG_EXPORT_FILE), "75_raw_log_export.csv")
            DeviceShell.run("input keyevent KEYCODE_BACK")
        }
    }

    /**
     * The earlier-usage consent step (setup acts as if the phone had usage in
     * the 3 days before today), then home and its help dialog.
     */
    @Test
    fun i_earlierUsageAndHelp() {
        grantAll()
        DebugUi.assumeEarlierUsage = true
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForText("Acepto y continúo")
            compose.onNode(hasText(TERMS_ACCEPTANCE)).performScrollTo()
            compose.tapText("Acepto y continúo")
            compose.waitForText("Datos del participante")
            compose.onNode(hasSetTextAction() and hasText("Edad")).performTextInput("21")
            compose.onNode(hasSetTextAction() and hasText("Género")).performTextInput("Femenino")
            compose.onNode(hasSetTextAction() and hasText("Carrera universitaria")).performTextInput("Ingeniería en Sistemas")
            compose.onNode(hasSetTextAction() and hasText("Puntaje SAS-SV")).performTextInput("37")
            compose.scrollToAndTapText("Continuar")
            compose.waitForText("Permiso de acceso al uso"); compose.tapText("Continuar")
            compose.waitForText("Permiso de notificaciones"); compose.tapText("Continuar")
            compose.waitForText("Ejecución en segundo plano"); compose.tapText("Continuar")
            compose.waitForText("Mostrar sobre otras apps"); compose.tapText("Continuar")
            runCatching { compose.waitForText("Inicio automático", 3_000) }.onSuccess { compose.tapText("Continuar") }

            compose.waitForText(EARLIER_USAGE_TITLE, 15_000)
            shot("11_earlier_usage")
            compose.tapText(EARLIER_USAGE_REJECT)

            compose.waitForText("Estado", 30_000)
            compose.onNodeWithContentDescription("Ayuda").performClick()
            compose.waitForText("Cerrar")
            shot("28_help_dialog")
            compose.tapText("Cerrar")
        }
    }

    /** Home once both profiles are frozen: the "interventions active" badge instead of the start-by box. */
    @Test
    fun j_homeInterventionsActive() {
        Seed.registeredSession(context, backend.token, signupHoursAgo = 80)
        Seed.frozenProfiles(context)
        grantAll()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForText(INTERVENTIONS_ACTIVE, 30_000)
            shot("20b_home_interventions_active", settleMs = 1_500)
        }
    }

    companion object {
        /** Under /sdcard so Test Lab can pull it (--directories-to-pull); the shell writes it. */
        const val DIR = "/sdcard/Download/smartuse_screens"
        const val ZONE = "America/Guatemala"
    }
}
