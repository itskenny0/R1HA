package com.github.itskenny0.r1ha.core.ambient

import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.feature.energy.EnergyTemplates
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonPrimitive

/**
 * Fetches the small at-a-glance set the ambient idle face shows: weather,
 * lights-on count, persons-home count, total power draw, persistent-alert
 * count, and the soonest active timer. Each piece is fetched in parallel and
 * tolerates its own failure (the field stays null), so the idle face never
 * blanks wholesale on a flaky network.
 */
class AmbientSummaryUseCase(private val haRepository: HaRepository) {

    suspend fun fetch(settings: AppSettings): AmbientSummary = coroutineScope {
        val a = settings.ambient
        val weatherJob = async { if (a.showWeather) haRepository.listRawEntitiesByDomain("weather").getOrNull() else null }
        val personJob = async { if (a.showPersons) haRepository.listRawEntitiesByDomain("person").getOrNull() else null }
        val timerJob = async { if (a.showAlerts) haRepository.listRawEntitiesByDomain("timer").getOrNull() else null }
        val notifJob = async { if (a.showAlerts) haRepository.listPersistentNotifications().getOrNull() else null }
        val lightsJob = async {
            if (a.showLights) {
                haRepository.renderTemplate(
                    "{{ states.light | selectattr('state','eq','on') | list | count }}",
                ).getOrNull()
            } else {
                null
            }
        }
        val powerJob = async {
            if (a.showPower) {
                haRepository.renderTemplate(
                    EnergyTemplates.sumPowerDraw(settings.energyExcludedSensors),
                ).getOrNull()
            } else {
                null
            }
        }
        awaitAll(weatherJob, personJob, timerJob, notifJob, lightsJob, powerJob)

        val weatherRow = weatherJob.await()
            ?.firstOrNull { it.state !in setOf("unavailable", "unknown") }
            ?: weatherJob.await()?.firstOrNull()
        val persons = personJob.await()
        val timers = timerJob.await()
        val notifs = notifJob.await()

        AmbientSummary(
            weatherName = weatherRow?.friendlyName,
            condition = weatherRow?.state,
            temperature = (weatherRow?.attributes?.get("temperature") as? JsonPrimitive)?.content?.toDoubleOrNull(),
            temperatureUnit = (weatherRow?.attributes?.get("temperature_unit") as? JsonPrimitive)?.content,
            apparentTemperature = (weatherRow?.attributes?.get("apparent_temperature") as? JsonPrimitive)
                ?.content?.toDoubleOrNull(),
            lightsOn = AmbientParse.firstInt(lightsJob.await()),
            personsHome = persons?.count { it.state == "home" },
            powerWatts = AmbientParse.firstDouble(powerJob.await()),
            alertCount = notifs?.size ?: 0,
            activeTimerLabel = timers
                ?.firstOrNull { it.state == "active" }
                ?.friendlyName,
        )
    }
}
