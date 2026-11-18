package com.example.smartuse_frontend.notification

/** Posts or removes the app's one study notification. Implementations never throw. */
interface StudyNotifier {
    fun show(notice: StudyNotice)
    fun clear()
}
