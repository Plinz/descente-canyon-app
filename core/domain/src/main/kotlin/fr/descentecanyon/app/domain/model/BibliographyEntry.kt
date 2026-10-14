package fr.descentecanyon.app.domain.model

data class BibliographyEntry(
    val id: String,
    val kind: BibliographyKind,
    val resourceType: ResourceType? = null,
    val title: String,
    val authors: List<String> = emptyList(),
    val publicationYear: Int? = null,
    val reference: String? = null,
    val editor: String? = null,
    val status: String? = null,
    val scale: String? = null,
    val detailUrl: String? = null,
    val url: String? = null,
)

enum class BibliographyKind {
    TOPOGUIDE,
    MAP,
    RESOURCE,
}

enum class ResourceType {
    WEBSITE,
}
