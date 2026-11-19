package com.example.smartuse_frontend.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smartuse_frontend.jobs.JobName
import com.example.smartuse_frontend.notification.StudyNotice
import com.example.smartuse_frontend.notification.studyNoticeContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class NotificationTextTest {

    @get:Rule(order = 0) val device = DeviceStateRule()
    @get:Rule(order = 1) val backend = FakeBackend()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aRecordingParticipantSeesThePlainRecordingText() {
        Seed.collecting(context, backend)
        val run = WorkJobs.forceCapture(context)
        WorkJobs.awaitCompletedAfter(context, JobName.CAPTURE, run)
        StudyNotification.awaitText(context, studyNoticeContent(StudyNotice.Recording(emptySet())).text)
    }

    @Test
    fun aPublishedCutoffInsideTheWindowIsAnnounced() {
        val cutoff = LocalDate.now().plusDays(10) // before signup + 21 days: announced
        backend.cutoff = cutoff.toString()
        Seed.collecting(context, backend) // the first capture run fetches and stores it
        val run = WorkJobs.forceCapture(context)
        WorkJobs.awaitCompletedAfter(context, JobName.CAPTURE, run)
        StudyNotification.awaitText(context, studyNoticeContent(StudyNotice.Recording(emptySet(), cutoff)).text)
    }
}
