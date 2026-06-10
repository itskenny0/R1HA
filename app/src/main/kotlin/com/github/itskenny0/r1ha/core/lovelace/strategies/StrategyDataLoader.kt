package com.github.itskenny0.r1ha.core.lovelace.strategies

import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Builds a [StrategyData] snapshot from the live [HaRepository] for the
 * [StrategyEngine] to expand against. The registry fetches run in parallel; any
 * that fail degrade to empty so the engine still produces a (reduced) layout
 * rather than a blank.
 *
 * The usage-prediction call is fired only when the config actually references a
 * `common-controls` section, so a dashboard that never uses it doesn't pay for
 * the round trip (the WS command 404s on servers without the integration).
 */
class StrategyDataLoader(private val repo: HaRepository) {

    suspend fun load(needsUsagePrediction: Boolean): StrategyData = coroutineScope {
        val statesD = async { repo.listAllEntities().getOrElse { emptyList() } }
        val areasD = async { repo.listAreas().getOrElse { emptyList() } }
        val devicesD = async { repo.listDevices().getOrElse { emptyList() } }
        val registryD = async { repo.listEntityRegistry().getOrElse { emptyList() } }
        val floorsD = async { repo.listFloors().getOrElse { emptyList() } }
        val energyD = async {
            // A non-empty energy prefs map means energy is configured; the engine
            // only needs the boolean "is a grid source set". get_prefs returns
            // custom names, so a populated map is a strong proxy; an empty map is
            // treated as "no energy card" (conservative, never a false card).
            repo.getEnergyPrefs().getOrElse { emptyMap() }.isNotEmpty()
        }
        val predictionD = async {
            if (!needsUsagePrediction) {
                null
            } else {
                repo.predictCommonControls().fold(
                    onSuccess = { it },
                    onFailure = {
                        R1Log.w("StrategyData", "usage prediction unavailable: ${it.message}")
                        null
                    },
                )
            }
        }

        val states = statesD.await()
        val areas = areasD.await()
        val devices = devicesD.await()
        val registry = registryD.await()
        val floors = floorsD.await()

        StrategyData(
            states = states.associate { it.id.value to it.toStrategyEntity() },
            areas = areas.associate {
                it.areaId to StrategyArea(
                    areaId = it.areaId,
                    name = it.name,
                    floorId = it.floorId,
                    icon = it.icon,
                    temperatureEntityId = it.temperatureEntityId,
                    humidityEntityId = it.humidityEntityId,
                )
            },
            devices = devices.associate {
                it.id to StrategyDevice(id = it.id, displayName = it.displayName, areaId = it.areaId)
            },
            entities = registry.associate {
                it.entityId to StrategyRegistryEntity(
                    entityId = it.entityId,
                    areaId = it.areaId,
                    deviceId = it.deviceId,
                    platform = it.platform,
                    entityCategory = it.entityCategory,
                    hiddenBy = it.hiddenBy,
                    disabledBy = it.disabledBy,
                )
            },
            floors = floors.associate {
                it.floorId to StrategyFloor(it.floorId, it.name, it.level, it.icon)
            },
            hasEnergyGrid = energyD.await(),
            commonControls = predictionD.await(),
        )
    }
}

private fun EntityState.toStrategyEntity(): StrategyEntity {
    val attrs = attributesJson
    val picture = (attrs?.get("entity_picture") as? JsonPrimitive)?.content?.takeUnless { it.isBlank() }
    val hvacModes = (attrs?.get("hvac_modes") as? JsonArray)?.size ?: 0
    return StrategyEntity(
        entityId = id.value,
        friendlyName = friendlyName,
        state = (rawState ?: "").lowercase(),
        lastChangedMs = lastChanged.toEpochMilli(),
        hasEntityPicture = picture != null,
        hvacModesCount = hvacModes,
        deviceClass = deviceClass?.lowercase(),
    )
}
