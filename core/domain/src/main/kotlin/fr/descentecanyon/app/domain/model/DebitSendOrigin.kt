package fr.descentecanyon.app.domain.model

/** How a flow report reached the site. */
enum class DebitSendOrigin {
    /** Sent right when the person confirmed the form. */
    FORM,

    /** Kept offline, then sent by itself once the network was back. */
    QUEUE,
}
