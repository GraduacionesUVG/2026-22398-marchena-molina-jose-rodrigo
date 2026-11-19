package com.example.smartuse_frontend.notification

class FakeStudyNotifier : StudyNotifier {
    val shown = mutableListOf<StudyNotice>()
    var clears = 0
        private set

    override fun show(notice: StudyNotice) {
        shown += notice
    }

    override fun clear() {
        clears++
    }
}
