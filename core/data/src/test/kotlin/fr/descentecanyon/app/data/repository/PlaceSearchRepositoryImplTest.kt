package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.network.ApiHttpClient
import fr.descentecanyon.app.data.remote.geocoding.PhotonFeatureDto
import fr.descentecanyon.app.data.remote.geocoding.PhotonGeometryDto
import fr.descentecanyon.app.data.remote.geocoding.PhotonPropertiesDto
import fr.descentecanyon.app.data.remote.geocoding.PhotonRemoteSource
import fr.descentecanyon.app.data.remote.geocoding.PhotonResponseDto
import fr.descentecanyon.app.domain.model.FoundPlace
import fr.descentecanyon.app.domain.model.FoundPlaceKind
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceSearchRepositoryImplTest {

    private class FakePhoton(private val answer: () -> PhotonResponseDto) : PhotonRemoteSource(mockk<ApiHttpClient>()) {
        val languages = mutableListOf<String?>()

        override suspend fun search(query: String, latitude: Double?, longitude: Double?, language: String?): PhotonResponseDto {
            languages += language
            return answer()
        }
    }

    private fun feature(longitude: Double, latitude: Double, properties: PhotonPropertiesDto) =
        PhotonFeatureDto(PhotonGeometryDto(listOf(longitude, latitude)), properties)

    @Test
    fun `a campsite, a hamlet and a street read as places, the pieces of one street once`() = runTest {
        val street = PhotonPropertiesDto(
            name = "Chemin d'Allières",
            osmKey = "highway",
            osmValue = "unclassified",
            postcode = "38640",
            city = "Claix",
            country = "France",
        )
        val photon = FakePhoton {
            PhotonResponseDto(
                listOf(
                    feature(
                        7.2823,
                        43.9427,
                        PhotonPropertiesDto(
                            name = "Les Merveilles",
                            osmKey = "tourism",
                            osmValue = "camp_site",
                            street = "Route de Pelasque",
                            postcode = "06450",
                            city = "Lantosque",
                            country = "France",
                        ),
                    ),
                    feature(
                        5.6633,
                        45.1088,
                        PhotonPropertiesDto(
                            name = "Allières",
                            osmKey = "place",
                            osmValue = "hamlet",
                            postcode = "38640",
                            city = "Claix",
                            country = "France",
                        ),
                    ),
                    feature(5.6620, 45.1109, street),
                    feature(5.6610, 45.1105, street),
                    // No position: left out.
                    PhotonFeatureDto(PhotonGeometryDto(emptyList()), PhotonPropertiesDto(name = "Nulle part")),
                ),
            )
        }

        val found = PlaceSearchRepositoryImpl(photon).search("allieres", 45.1, 5.6, "it").getOrThrow()

        assertEquals(
            listOf(
                FoundPlace("Les Merveilles", "Route de Pelasque, 06450 Lantosque, France", 43.9427, 7.2823, FoundPlaceKind.CAMPSITE),
                FoundPlace("Allières", "38640 Claix, France", 45.1088, 5.6633, FoundPlaceKind.LOCALITY),
                FoundPlace("Chemin d'Allières", "38640 Claix, France", 45.1109, 5.6620, FoundPlaceKind.ADDRESS),
            ),
            found,
        )
        // The service refuses the languages it does not have: local names then.
        assertEquals(listOf<String?>(null), photon.languages)
    }

    @Test
    fun `no network gives a failure, not an empty list`() = runTest {
        val photon = FakePhoton { throw IOException("offline") }

        assertTrue(PlaceSearchRepositoryImpl(photon).search("camping", null, null, "fr").isFailure)
    }
}
