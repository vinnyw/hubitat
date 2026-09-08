/**
 *  --------------------------------------------------------------------------------------------------------------
 *  AirQuality Child Device
 *  --------------------------------------------------------------------------------------------------------------
 *
 *  Author      : Vinny Wadding
 *  Namespace   : vinnyw
 *  Version     : Parent-managed (via child app -> parent app)
 *  Date        : 2026-07-29
 *
 *  Description :
 *      Virtual airQualityIndex child device managed by the AirQuality child app.
 *
 *      Attributes:
 *          airQualityIndex        (number) : airQualityIndex value
 *          airQualityDisplay (string) : formatted airQualityIndex value
 *          trend              (string) : trend
 *          trendDisplay       (string) : formatted trend
 *          lastActivity       (number) : epoch time (Long)
 *
 *      Capabilities:
 *          Sensor
 *          AirQuality
 *          Refresh
 *          Configuration
 *
 *      Note:
 *          AirQuality is declared so Rule Machine 5.1 can use this virtual output
 *          device as a standard AirQuality device. The child app rejects this suite's own
 *          averaged output devices at runtime if they are accidentally selected as inputs.
 *          The app publishes a whole-number airQualityIndex and a formatted airQualityDisplay.
 *
 *  --------------------------------------------------------------------------------------------------------------
 */

import groovy.transform.Field

metadata {
    definition(
        name: 'AirQuality-1.0',
        namespace: 'vinnyw',
        author: 'Vinny Wadding'
    ) {
        capability 'Sensor'
        capability 'AirQuality'
        capability 'Refresh'
        capability 'Configuration'

        attribute 'airQualityDisplay', 'string'
        attribute 'airQualityLevel', 'enum', ['Good', 'Moderate', 'Unhealthy for Sensitive Groups', 'Unhealthy', 'Very Unhealthy', 'Hazardous', 'Unknown']
        attribute 'trend', 'string'
        attribute 'trendDisplay', 'string'
        attribute 'lastActivity', 'number'

        command 'clearTrend'
    }

    preferences {
        input name: 'txtEnable', type: 'bool',
              title: 'Enable descriptionText logging',
              defaultValue: true

        input name: 'debugEnable', type: 'bool',
              title: 'Enable debug logging',
              defaultValue: false
    }
}

//
//    VERSION
//

def getVersion() {
    return parent?.getVersion() ?: 'unknown'
}

//
//    UI / PREFERENCES
//

// Preferences are declared in metadata { preferences { ... } } above.

//
//    LIFECYCLE
//

def configure() {
    ensureAverageAirQualityOutputMarker()

    Map cfg = parent?.getChildDriverLoggingConfig()
    if (cfg instanceof Map) {
        applyParentLogging(cfg.txtEnable, cfg.debugEnable, cfg.debugAutoDisableSeconds)
    }

    scheduleDebugAutoDisableIfNeeded()

    String previousVersion = state.driverVersion
    String currentVersion = getVersion()
    state.driverVersion = currentVersion

    String prevDisplay = previousVersion == 'unknown' ? 'unknown' : "v${previousVersion}"
    String currDisplay = currentVersion == 'unknown' ? 'unknown' : "v${currentVersion}"

    if (!previousVersion) {
        log.info "${device.displayName}: Driver installed (${currDisplay})"
    }
    else if (previousVersion != currentVersion) {
        log.info "${device.displayName}: Driver upgraded from ${prevDisplay} to ${currDisplay}"
    }

    if (device.currentValue('airQualityDisplay') == null) {
        sendEvent(name: 'airQualityDisplay', value: '', isStateChange: false, type: 'digital')
    }

    if (device.currentValue('airQualityLevel') == null) {
        sendEvent(name: 'airQualityLevel', value: 'Unknown', isStateChange: false, type: 'digital')
    }

    if (device.currentValue('trend') == null) {
        sendEvent(name: 'trend', value: '', isStateChange: false, type: 'digital')
    }

    if (device.currentValue('trendDisplay') == null) {
        sendEvent(name: 'trendDisplay', value: '', isStateChange: false, type: 'digital')
    }

    if (device.currentValue('lastActivity') == null) {
        sendEvent(
            name: 'lastActivity',
            value: now().intdiv(1000L),
            isStateChange: false,
            type: 'digital'
        )
    }

    logDebug("Configure completed with txtEnable=${settings?.txtEnable}, debugEnable=${settings?.debugEnable}, version=${currentVersion}")
    parent?.childRefreshRequest()
}

def installed() {
    configure()
}

def updated() {
    unschedule()
    parent?.updateLoggingFromDriver(settings?.txtEnable, settings?.debugEnable)
    configure()
}

//
//    COMMANDS
//

def clearTrend() {
    boolean changed = false
    changed = updateSingleTrendAttribute('trend', '') || changed
    changed = updateSingleTrendAttribute('trendDisplay', '') || changed

    if (changed) {
        logDebug('Trend cleared')
    }
}

def refresh() {
    logDebug('Refresh requested; delegating sensor pull/recalculation to child app')
    parent?.childRefreshRequest()
}

def presentCalculatedValues(
    airQualityIndexValue,
    airQualityDisplayValue,
    trendValue,
    trendDisplayValue,
    activityTimestamp
) {
    if (airQualityIndexValue == null) {
        logWarn('presentCalculatedValues called with a null airQualityIndex value')
        return
    }

    String canonicalAirQualityIndex = airQualityIndexValue.toString().trim()
    if (!canonicalAirQualityIndex) {
        logWarn('presentCalculatedValues called with an empty airQualityIndex value')
        return
    }

    Integer parsedAirQualityIndex
    try {
        parsedAirQualityIndex = Integer.valueOf(canonicalAirQualityIndex)
    } catch (Exception e) {
        logError("Invalid app-supplied integer airQualityIndex '${airQualityIndexValue}': ${e.message}")
        return
    }

    canonicalAirQualityIndex = parsedAirQualityIndex.toString()

    String display = airQualityDisplayValue == null
        ? canonicalAirQualityIndex
        : airQualityDisplayValue.toString()

    Long suppliedActivity
    try {
        suppliedActivity = activityTimestamp as Long
    } catch (Exception e) {
        logError("Invalid app-supplied activity timestamp '${activityTimestamp}': ${e.message}")
        return
    }

    boolean changed = false
    String currentRaw = device.currentValue('airQualityIndex')?.toString()

    // airQualityIndex is always published as a whole-number value.
    if (currentRaw != canonicalAirQualityIndex) {
        sendEvent(
            name: 'airQualityIndex',
            value: parsedAirQualityIndex,
            isStateChange: true,
            type: 'digital'
        )
        changed = true
    }

    String calculatedLevel = airQualityIndexToLevel(parsedAirQualityIndex)
    if ((device.currentValue('airQualityLevel') ?: '').toString() != calculatedLevel) {
        sendEvent(
            name: 'airQualityLevel',
            value: calculatedLevel,
            isStateChange: true,
            type: 'digital'
        )
        changed = true
    }

    if ((device.currentValue('airQualityDisplay') ?: '').toString() != display) {
        sendEvent(
            name: 'airQualityDisplay',
            value: display,
            isStateChange: true,
            type: 'digital'
        )
        changed = true
    }

    changed = updateTrendAttributes(trendValue, trendDisplayValue) || changed

    if (changed || device.currentValue('lastActivity')?.toString() != suppliedActivity.toString()) {
        sendEvent(
            name: 'lastActivity',
            value: suppliedActivity,
            isStateChange: true,
            type: 'digital'
        )
    }

    if (changed) {
        if (descriptionTextLoggingEnabled()) {
            log.info "${device.displayName} airQualityIndex is ${display}"
        }
        logDebug(
            "Published app-supplied airQualityIndex=${canonicalAirQualityIndex}, " +
            "display=${display}, trend=${trendValue}, trendDisplay=${trendDisplayValue}"
        )
    } else {
        logDebug("No attribute changes required for app-supplied airQualityIndex=${canonicalAirQualityIndex}")
    }
}

private void ensureAverageAirQualityOutputMarker() {
    try {
        if (getDataValue('averageAirQualityVirtualDevice') != 'true') {
            updateDataValue('averageAirQualityVirtualDevice', 'true')
        }
    } catch (Exception e) {
        logDebug("Unable to set Average Air Quality output marker: ${e.message}")
    }
}

//
//    AQARA TVOC AIR QUALITY LEVEL MAPPING
//

private String airQualityIndexToLevel(final Integer index) {
    if (index == null || index < 0) return 'Unknown'
    if (index <= 300) return 'Good'
    if (index <= 500) return 'Moderate'
    if (index <= 1000) return 'Unhealthy for Sensitive Groups'
    if (index <= 3000) return 'Unhealthy'
    if (index <= 5000) return 'Very Unhealthy'
    return 'Hazardous'
}

//
//    LOGGING CONFIGURATION & SYNC
//

private void applyParentLogging(txtEnableValue, debugEnableValue, debugAutoDisableSecondsValue) {
    Boolean descEnabled = normalizeBoolean(txtEnableValue, true)
    Boolean debugEnabled = normalizeBoolean(debugEnableValue, false)

    updateBooleanSettingIfChanged('txtEnable', descEnabled)
    updateBooleanSettingIfChanged('debugEnable', debugEnabled)

// Parent app is the source of truth for debug auto-disable timeout.
// The driver does not persist this value in state.
}

private void updateBooleanSettingIfChanged(String name, Boolean newValue) {
    Boolean currentValue = normalizeBoolean(settings?."${name}", newValue)
    if (currentValue != newValue) {
        device.updateSetting(name, [value: newValue, type: 'bool'])
    }
}

//
//    LOGGING SCHEDULER
//

private Integer debugAutoDisableMinutes() {
    return (int) (debugAutoDisableSeconds() / 60)
}

private Integer debugAutoDisableSeconds() {
    return getParentDebugAutoDisableSeconds()
}

def logsOff() {
    if (!debugLoggingEnabled()) return

    updateBooleanSettingIfChanged('debugEnable', false)

    try {
        parent?.updateLoggingFromDriver(settings?.txtEnable, false)
    } catch (Exception ignored) {
    }

    log.warn "${device.displayName}: Debug logging disabled automatically after ${debugAutoDisableMinutes()} minutes"
}

private Integer getParentDebugAutoDisableSeconds() {
    try {
        return normalizeDebugAutoDisableSeconds(parent?.getDebugAutoDisableSeconds())
    } catch (Exception ignored) {
        return 1800
    }
}

private Integer normalizeDebugAutoDisableSeconds(value) {
    try {
        Integer seconds = value as Integer
        return seconds > 0 ? seconds : 1800
    } catch (Exception ignored) {
        return 1800
    }
}

private void scheduleDebugAutoDisableIfNeeded() {
    unschedule('logsOff')

    if (debugLoggingEnabled()) {
        runIn(debugAutoDisableSeconds(), 'logsOff')
        logDebug("Debug logging will automatically turn off in ${debugAutoDisableMinutes()} minutes")
    }
}

//
//    LOGGING HELPERS
//

private Boolean debugLoggingEnabled() {
    return normalizeBoolean(settings?.debugEnable, false)
}

private Boolean descriptionTextLoggingEnabled() {
    return normalizeBoolean(settings?.txtEnable, true)
}

private void logDebug(String msg) {
    if (debugLoggingEnabled()) log.debug "${device.displayName}: ${msg}"
}

private void logError(String msg) {
    log.error "${device.displayName}: ${msg}"
}

private void logWarn(String msg) {
    log.warn "${device.displayName}: ${msg}"
}

private Boolean normalizeBoolean(value, Boolean defaultValue) {
    if (value == null) return defaultValue
    if (value instanceof Boolean) return value
    String s = value.toString().trim().toLowerCase()
    if (s == 'true') return true
    if (s == 'false') return false
    return defaultValue
}

//
//    TREND HELPERS
//

private boolean updateSingleTrendAttribute(String name, String value) {
    String normalized = value == null ? '' : value.toString()
    String currentValue = device.currentValue(name)?.toString() ?: ''

    if (currentValue != normalized) {
        sendEvent(name: name, value: normalized, isStateChange: false, type: 'digital')
        return true
    }

    return false
}

private boolean updateTrendAttributes(String trend, String trendDisplay) {
    boolean changed = false

    if (trend == null && trendDisplay == null) {
        changed = updateSingleTrendAttribute('trend', '') || changed
        changed = updateSingleTrendAttribute('trendDisplay', '') || changed
        return changed
    }

    changed = updateSingleTrendAttribute('trend', trend) || changed
    changed = updateSingleTrendAttribute('trendDisplay', trendDisplay) || changed
    return changed
}
