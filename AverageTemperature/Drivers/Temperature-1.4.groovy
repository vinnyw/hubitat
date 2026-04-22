/**
 *  --------------------------------------------------------------------------------------------------------------
 *  Temperature Child Device
 *  --------------------------------------------------------------------------------------------------------------
 *
 *  Author      : Vinny Wadding
 *  Namespace   : vinnyw
 *  Version     : Parent-managed (via child app -> parent app)
 *  Date        : 2026-04-13
 *
 *  Description :
 *      Virtual temperature child device managed by the Temperature child app.
 *
 *      Attributes:
 *          temperature        (number) : temperature value
 *          temperatureDisplay (string) : formatted temperature value
 *          trend              (string) : trend 
 *          trendDisplay       (string) : formatted trend
 *          lastActivity       (number) : epoch time (Long)
 *
 *      Capabilities:
 *          Sensor
 *          TemperatureMeasurement
 *          Refresh
 *          Configuration
 *
 *      Note:
 *          TemperatureMeasurement is declared so Rule Machine 5.1 can use this virtual output
 *          device as a standard Temperature device. The child app rejects this suite's own
 *          averaged output devices at runtime if they are accidentally selected as inputs.
 *
 *  --------------------------------------------------------------------------------------------------------------
 */

import groovy.transform.Field
import java.math.RoundingMode


metadata {
    definition(
        name: 'Temperature-1.4',
        namespace: 'vinnyw',
        author: 'Vinny Wadding'
    ) {
        capability 'Sensor'
        capability 'TemperatureMeasurement'
        capability 'Refresh'
        capability 'Configuration'

        attribute 'temperatureDisplay', 'string'
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
    ensureAverageTemperatureOutputMarker()

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

    if (device.currentValue('temperatureDisplay') == null) {
        sendEvent(name: 'temperatureDisplay', value: '', isStateChange: false, type: 'digital')
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
            value: now(),
            isStateChange: false,
            type: 'digital'
        )
    }

    logDebug("Configure completed with txtEnable=${settings?.txtEnable}, debugEnable=${settings?.debugEnable}, version=${currentVersion}")
}

def installed() {
    configure()
}

def updated() {
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

def setTemperature(val, decimals = 0, unit = null, trend = null, trendDisplay = null) {
    if (val == null) {
        logWarn('setTemperature called with null value')
        return
    }

    Integer places = 0
    try {
        places = decimals as Integer
    } catch (Exception ignored) {
        places = 0
    }

    BigDecimal newValue
    try {
        newValue = new BigDecimal(val.toString()).setScale(places, RoundingMode.HALF_UP)
    } catch (Exception e) {
        logError("Invalid temperature value '${val}': ${e.message}")
        return
    }

    BigDecimal currentValue = null
    def currentRaw = device.currentValue('temperature')
    if (currentRaw != null && currentRaw.toString() != '') {
        try {
            currentValue = new BigDecimal(currentRaw.toString()).setScale(places, RoundingMode.HALF_UP)
        } catch (Exception ignored) {
            currentValue = null
        }
    }

    String normalizedUnit = normalizeDisplayUnit(unit)
    String display = normalizedUnit == 'none' ? "${newValue}" : "${newValue}${normalizedUnit}"
    boolean changed = false

    if (currentValue == null || currentValue.compareTo(newValue) != 0) {
        sendEvent(name: 'temperature', value: newValue, unit: eventTemperatureUnit(), isStateChange: true, type: 'digital')
        sendEvent(name: 'lastActivity', value: now(), isStateChange: false, type: 'digital')
        changed = true
    }

    if ((device.currentValue('temperatureDisplay') ?: '') != display) {
        sendEvent(name: 'temperatureDisplay', value: display, isStateChange: false, type: 'digital')
        changed = true
    }

    changed = updateTrendAttributes(trend, trendDisplay) || changed

    if (changed) {
        if (descriptionTextLoggingEnabled()) {
            log.info "${device.displayName} temperature is ${display}"
        }
        logDebug("Updated temperature=${newValue}, display=${display}, trend=${trend}, trendDisplay=${trendDisplay}")
    } else {
        logDebug("No attribute changes required for temperature=${newValue}")
    }
}



private String normalizeDisplayUnit(unit) {
    String resolved = unit == null ? eventTemperatureUnitSymbol() : unit.toString()
    return ['none', '°C', '°F'].contains(resolved) ? resolved : eventTemperatureUnitSymbol()
}

private String eventTemperatureUnit() {
    return location?.temperatureScale == 'C' ? '°C' : '°F'
}

private String eventTemperatureUnitSymbol() {
    return eventTemperatureUnit()
}

private void ensureAverageTemperatureOutputMarker() {
    try {
        if (getDataValue('averageTemperatureVirtualDevice') != 'true') {
            updateDataValue('averageTemperatureVirtualDevice', 'true')
        }
    } catch (Exception e) {
        logDebug("Unable to set Average Temperature output marker: ${e.message}")
    }
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
    if (debugLoggingEnabled()) log.error "${device.displayName}: ${msg}"
}

private void logWarn(String msg) {
    if (debugLoggingEnabled()) log.warn "${device.displayName}: ${msg}"
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
