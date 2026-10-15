package fr.descentecanyon.app.domain.model

/** Name and e-mail typed by a contributor who is not logged in, offered again in the next report. */
data class ContributorIdentity(val name: String, val email: String?)

/** Colour scheme chosen in the settings. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}
