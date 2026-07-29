/**
 *  --------------------------------------------------------------------------------------------------------------
 *  Temperature Child Device
 *  --------------------------------------------------------------------------------------------------------------
 *
 *  Author      : Vinny Wadding
 *  Namespace   : vinnyw
 *  Version     : Parent-managed (via child app -> parent app)
 *  Date        : 2026-07-29
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
 *          This local patch also ensures warning/error logs are not hidden by the debug toggle
 *          and clears driver schedules before reconfiguration.
 *          Version 1.4.17 publishes the exact canonical decimal value supplied by the app.
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
    temperatureValue,
    temperatureDisplayValue,
    temperatureUnit,
    trendValue,
    trendDisplayValue,
    activityTimestamp
) {
    if (temperatureValue == null) {
        logWarn('presentCalculatedValues called with a null temperature value')
        return
    }

    String canonicalTemperature = temperatureValue.toString().trim()
    if (!canonicalTemperature) {
        logWarn('presentCalculatedValues called with an empty temperature value')
        return
    }

    try {
        new BigDecimal(canonicalTemperature)
    } catch (Exception e) {
        logError("Invalid app-supplied temperature value '${temperatureValue}': ${e.message}")
        return
    }

    String display = temperatureDisplayValue == null
        ? canonicalTemperature
        : temperatureDisplayValue.toString()

    String eventUnit = temperatureUnit == null
        ? ''
        : temperatureUnit.toString()

    Long suppliedActivity
    try {
        suppliedActivity = activityTimestamp as Long
    } catch (Exception e) {
        logError("Invalid app-supplied activity timestamp '${activityTimestamp}': ${e.message}")
        return
    }

    boolean changed = false
    String currentRaw = device.currentValue('temperature')?.toString()

    // Compare the raw stored representation with the app's canonical precision.
    // Do not round the existing value here: 31.83 must not be treated as equal to 31.8.
    if (currentRaw != canonicalTemperature) {
        sendEvent(
            name: 'temperature',
            value: canonicalTemperature,
            unit: eventUnit,
            isStateChange: true,
            type: 'digital'
        )
        changed = true
    }

    if ((device.currentValue('temperatureDisplay') ?: '').toString() != display) {
        sendEvent(
            name: 'temperatureDisplay',
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
            log.info "${device.displayName} temperature is ${display}"
        }
        logDebug(
            "Published app-supplied temperature=${canonicalTemperature}, " +
            "display=${display}, trend=${trendValue}, trendDisplay=${trendDisplayValue}"
        )
    } else {
        logDebug("No attribute changes required for app-supplied temperature=${canonicalTemperature}")
    }
}

def setTemperature(val, decimals = 0, unit = null, trend = null, trendDisplay = null) {
    // Backward-compatible entry point. The app remains authoritative.
    Integer places
    try {
        places = Math.max(0, Math.min(decimals as Integer, 2))
    } catch (Exception ignored) {
        places = 0
    }

    BigDecimal canonical
    try {
        canonical = new BigDecimal(val.toString()).setScale(places, RoundingMode.HALF_UP)
    } catch (Exception e) {
        logError("Invalid legacy temperature value '${val}': ${e.message}")
        return
    }

    String numericText = canonical.toPlainString()
    String normalizedUnit = normalizeDisplayUnit(unit)
    String display = normalizedUnit == 'none'
        ? numericText
        : "${numericText}${normalizedUnit}"

    presentCalculatedValues(
        numericText,
        display,
        eventTemperatureUnit(),
        trend,
        trendDisplay,
        now().intdiv(1000L)
    )
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
