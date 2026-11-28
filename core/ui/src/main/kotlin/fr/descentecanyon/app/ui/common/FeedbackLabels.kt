package fr.descentecanyon.app.ui.common

import androidx.annotation.StringRes
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.FeedbackKind
import fr.descentecanyon.app.domain.model.FeedbackStatus

/** What became of a message of the idea box, as its author reads it. */
@StringRes
fun FeedbackStatus.labelRes(): Int = when (this) {
    FeedbackStatus.NEW -> R.string.feedback_status_new
    FeedbackStatus.READ -> R.string.feedback_status_read
    FeedbackStatus.PLANNED -> R.string.feedback_status_planned
    FeedbackStatus.DONE -> R.string.feedback_status_done
    FeedbackStatus.DECLINED -> R.string.feedback_status_declined
}

@StringRes
fun FeedbackKind.labelRes(): Int = when (this) {
    FeedbackKind.BUG -> R.string.feedback_kind_bug
    FeedbackKind.IDEA -> R.string.feedback_kind_idea
    FeedbackKind.CHANGE -> R.string.feedback_kind_change
}
