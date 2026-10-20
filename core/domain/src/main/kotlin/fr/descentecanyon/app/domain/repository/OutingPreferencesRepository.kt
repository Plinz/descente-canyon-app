package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.GearListSettings
import fr.descentecanyon.app.domain.model.MessageAudience
import fr.descentecanyon.app.domain.model.MessageSection
import fr.descentecanyon.app.domain.model.OutingSettings
import kotlinx.coroutines.flow.Flow

/** How the user prepares outings: time coefficients, gear list and the contents of their messages. */
interface OutingPreferencesRepository {
    fun observeSettings(): Flow<OutingSettings>

    suspend fun setSettings(settings: OutingSettings)

    /** The information each kind of message carries; [MessageAudience.defaultSections] until changed. */
    fun observeMessageSections(): Flow<Map<MessageAudience, Set<MessageSection>>>

    suspend fun setMessageSections(audience: MessageAudience, sections: Set<MessageSection>)

    fun observeGearList(): Flow<GearListSettings>

    suspend fun setGearList(settings: GearListSettings)
}
