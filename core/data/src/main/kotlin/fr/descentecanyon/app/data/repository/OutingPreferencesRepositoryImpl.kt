package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.user.dao.KeyValueDao
import fr.descentecanyon.app.data.local.user.entity.KeyValueEntity
import fr.descentecanyon.app.domain.model.GearGroup
import fr.descentecanyon.app.domain.model.GearItem
import fr.descentecanyon.app.domain.model.GearKey
import fr.descentecanyon.app.domain.model.GearListSettings
import fr.descentecanyon.app.domain.model.GroupLevel
import fr.descentecanyon.app.domain.model.MessageAudience
import fr.descentecanyon.app.domain.model.MessageSection
import fr.descentecanyon.app.domain.model.OutingSettings
import fr.descentecanyon.app.domain.repository.OutingPreferencesRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Outing preferences kept as JSON values in the user database, so they follow its backup. */
@Singleton
class OutingPreferencesRepositoryImpl @Inject constructor(private val keyValueDao: KeyValueDao) : OutingPreferencesRepository {

    override fun observeSettings(): Flow<OutingSettings> = observe(KEY_SETTINGS, StoredSettings.serializer())
        .map { (it ?: StoredSettings()).toDomain() }
        .distinctUntilChanged()

    override suspend fun setSettings(settings: OutingSettings) = put(KEY_SETTINGS, StoredSettings.serializer(), StoredSettings.of(settings))

    override fun observeMessageSections(): Flow<Map<MessageAudience, Set<MessageSection>>> =
        observe(KEY_MESSAGE_SECTIONS, StoredSections.serializer())
            .map { stored -> MessageAudience.entries.associateWith { audience -> stored.sectionsOf(audience) } }
            .distinctUntilChanged()

    override suspend fun setMessageSections(audience: MessageAudience, sections: Set<MessageSection>) {
        val stored = keyValueDao.get(KEY_MESSAGE_SECTIONS)?.let { decode(it, StoredSections.serializer()) } ?: StoredSections()
        val updated = stored.copy(byAudience = stored.byAudience + (audience.name to sections.map { it.name }.sorted()))
        put(KEY_MESSAGE_SECTIONS, StoredSections.serializer(), updated)
    }

    override fun observeGearList(): Flow<GearListSettings> = observe(KEY_GEAR, StoredGear.serializer())
        .map { (it ?: StoredGear()).toDomain() }
        .distinctUntilChanged()

    override suspend fun setGearList(settings: GearListSettings) = put(KEY_GEAR, StoredGear.serializer(), StoredGear.of(settings))

    private fun <T> observe(key: String, serializer: KSerializer<T>): Flow<T?> = keyValueDao.observe(key).map { payload ->
        payload?.let { decode(it, serializer) }
    }

    private fun <T> decode(payload: String, serializer: KSerializer<T>): T? =
        payload.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

    private suspend fun <T> put(key: String, serializer: KSerializer<T>, value: T) {
        keyValueDao.put(KeyValueEntity(key, json.encodeToString(serializer, value)))
    }

    private fun StoredSections?.sectionsOf(audience: MessageAudience): Set<MessageSection> = this?.byAudience?.get(audience.name)
        ?.mapNotNull { name -> MessageSection.entries.firstOrNull { it.name == name } }
        ?.toSet()
        ?: audience.defaultSections

    /** Every field has the default of [OutingSettings]: values added later read as their default. */
    @Serializable
    private data class StoredSettings(
        val prepMinutes: Int = OutingSettings.DEFAULT.prepMinutes,
        val packUpMinutes: Int = OutingSettings.DEFAULT.packUpMinutes,
        val shuttleHandlingMinutes: Int = OutingSettings.DEFAULT.shuttleHandlingMinutes,
        val mediumGroupFactor: Double = OutingSettings.DEFAULT.mediumGroupFactor,
        val largeGroupFactor: Double = OutingSettings.DEFAULT.largeGroupFactor,
        val hugeGroupFactor: Double = OutingSettings.DEFAULT.hugeGroupFactor,
        val beginnersFactor: Double = OutingSettings.DEFAULT.beginnersFactor,
        val discoveryFactor: Double = OutingSettings.DEFAULT.discoveryFactor,
        val beginnersPrepFactor: Double = OutingSettings.DEFAULT.beginnersPrepFactor,
        val earlierPercent: Int = OutingSettings.DEFAULT.earlierPercent,
        val laterPercent: Int = OutingSettings.DEFAULT.laterPercent,
        val usePersonalPace: Boolean = OutingSettings.DEFAULT.usePersonalPace,
        val defaultGroupSize: Int = OutingSettings.DEFAULT.defaultGroupSize,
        val defaultGroupLevel: String = OutingSettings.DEFAULT.defaultGroupLevel.name,
    ) {
        fun toDomain() = OutingSettings(
            prepMinutes = prepMinutes,
            packUpMinutes = packUpMinutes,
            shuttleHandlingMinutes = shuttleHandlingMinutes,
            mediumGroupFactor = mediumGroupFactor,
            largeGroupFactor = largeGroupFactor,
            hugeGroupFactor = hugeGroupFactor,
            beginnersFactor = beginnersFactor,
            discoveryFactor = discoveryFactor,
            beginnersPrepFactor = beginnersPrepFactor,
            earlierPercent = earlierPercent,
            laterPercent = laterPercent,
            usePersonalPace = usePersonalPace,
            defaultGroupSize = defaultGroupSize,
            defaultGroupLevel = GroupLevel.entries.firstOrNull { it.name == defaultGroupLevel } ?: GroupLevel.AUTONOMOUS,
        ).sanitized()

        companion object {
            fun of(settings: OutingSettings) = with(settings.sanitized()) {
                StoredSettings(
                    prepMinutes = prepMinutes,
                    packUpMinutes = packUpMinutes,
                    shuttleHandlingMinutes = shuttleHandlingMinutes,
                    mediumGroupFactor = mediumGroupFactor,
                    largeGroupFactor = largeGroupFactor,
                    hugeGroupFactor = hugeGroupFactor,
                    beginnersFactor = beginnersFactor,
                    discoveryFactor = discoveryFactor,
                    beginnersPrepFactor = beginnersPrepFactor,
                    earlierPercent = earlierPercent,
                    laterPercent = laterPercent,
                    usePersonalPace = usePersonalPace,
                    defaultGroupSize = defaultGroupSize,
                    defaultGroupLevel = defaultGroupLevel.name,
                )
            }
        }
    }

    @Serializable
    private data class StoredSections(val byAudience: Map<String, List<String>> = emptyMap())

    @Serializable
    private data class StoredCustomGear(val id: String, val group: String, val label: String)

    @Serializable
    private data class StoredGear(val hidden: List<String> = emptyList(), val custom: List<StoredCustomGear> = emptyList()) {
        fun toDomain() = GearListSettings(
            hidden = hidden.mapNotNull { name -> GearKey.entries.firstOrNull { it.name == name } }.toSet(),
            custom = custom.mapNotNull { item ->
                GearGroup.entries.firstOrNull { it.name == item.group }?.let { group -> GearItem.Custom(item.id, group, item.label) }
            },
        )

        companion object {
            fun of(settings: GearListSettings) = StoredGear(
                hidden = settings.hidden.map { it.name }.sorted(),
                custom = settings.custom.map { StoredCustomGear(it.id, it.group.name, it.label) },
            )
        }
    }

    private companion object {
        const val KEY_SETTINGS = "outing.settings"
        const val KEY_MESSAGE_SECTIONS = "outing.message_sections"
        const val KEY_GEAR = "outing.gear"
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
