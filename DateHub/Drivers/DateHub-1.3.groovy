/**
 *  --------------------------------------------------------------------------------------------------------------
 *  DateHub Child Device
 *  --------------------------------------------------------------------------------------------------------------
 *
 *  Author      : Vinny Wadding
 *  Namespace   : vinnyw
 *  Version     : 1.3.25
 *  Date        : 2026-07-13
 *
 *  Description :
 *      Child device driver for DateHub.
 *
 *      Responsibilities:
 *          - Exposes values calculated and published by the DateHub Manager parent app
 *          - Provides Refresh, Configure, and Clear Cache commands
 *          - Avoids independent scheduling, polling, or external HTTP calls
 *          - Uses guarded event updates to avoid redundant device events
 *
 *      Capabilities:
 *          Actuator
 *          Configuration
 *          Refresh
 *
 *  --------------------------------------------------------------------------------------------------------------
 */

metadata {
    definition(
        name: 'DateHub-1.3',
        namespace: 'vinnyw',
        author: 'Vinny Wadding',
        importUrl: 'https://raw.githubusercontent.com/vinnyw/hubitat/master/DateHub/Drivers/DateHub-1.3.groovy'
    ) {
        capability 'Actuator'
        capability 'Configuration'
        capability 'Refresh'

        command 'clearCache'

        attribute 'isPublicHoliday', 'enum', ['true', 'false']
        attribute 'publicHolidayName', 'string'

        attribute 'nextPublicHolidayName', 'string'
        attribute 'nextPublicHolidayDate', 'string'
        attribute 'daysUntilNextPublicHoliday', 'number'

        attribute 'daylightSaving', 'enum', ['true', 'false']
        attribute 'daylightSavingPeriod', 'enum', ['Greenwich Mean Time', 'British Summer Time']
        attribute 'clockOffset', 'string'
        attribute 'nextClockChange', 'enum', ['forward', 'back']
        attribute 'nextClockChangeDate', 'string'
        attribute 'nextClockChangeTime', 'string'
        attribute 'daysUntilNextClockChange', 'number'

        attribute 'season', 'enum', ['Spring', 'Summer', 'Autumn', 'Winter']
        attribute 'nextSeasonName', 'enum', ['Spring Equinox', \
                                             'Summer Solstice', \
                                             'Autumn Equinox', \
                                             'Winter Solstice']
        attribute 'nextSeasonDate', 'string'
        attribute 'daysUntilNextSeason', 'number'
        attribute 'nextEasterDate', 'string'
        attribute 'isEaster', 'enum', ['true', 'false']
        attribute 'daysUntilNextEaster', 'number'
        attribute 'daysUntilNextHalloween', 'number'
        attribute 'isHalloween', 'enum', ['true', 'false']
        attribute 'nextHalloweenDate', 'string'

        attribute 'isLeapYear', 'enum', ['true', 'false']
        attribute 'daysUntilNextLeapYear', 'number'
        attribute 'nextLeapYear', 'number'
        attribute 'nextLeapDay', 'string'
        attribute 'daysUntilNextLeapDay', 'number'
        attribute 'isLeapDay', 'enum', ['true', 'false']

        attribute 'moonPhase', 'enum', [    'New Moon', \
                                            'Waxing Crescent', \
                                            'First Quarter', \
                                            'Waxing Gibbous', \
                                            'Full Moon', \
                                            'Waning Gibbous', \
                                            'Last Quarter', \
                                            'Waning Crescent']
        attribute 'nextMoonPhaseName', 'enum', [    'New Moon', \
                                                    'Waxing Crescent', \
                                                    'First Quarter', \
                                                    'Waxing Gibbous', \
                                                    'Full Moon', \
                                                    'Waning Gibbous', \
                                                    'Last Quarter', \
                                                    'Waning Crescent']
        attribute 'nextMoonPhaseDate', 'string'
        attribute 'daysUntilNextNewMoon', 'number'
        attribute 'daysUntilNextFullMoon', 'number'
        attribute 'isNewMoon', 'enum', ['true', 'false']
        attribute 'isFullMoon', 'enum', ['true', 'false']
        attribute 'daysUntilNextMoonPhase', 'number'
        attribute 'isBlueMoon', 'enum', ['true', 'false']
        attribute 'daysUntilNextBlueMoon', 'number'
        attribute 'nextBlueMoon', 'string'

        attribute 'lastError', 'string'
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
//    DEVICE LIFECYCLE AND COMMANDS
//

def clearCache() {
    if (!parent) {
        logWarn('clearCache requested but no parent app is available')
        return
    }

    logDebug('Clear cache requested; delegating to parent app')
    parent.deviceClearCache(device.deviceNetworkId)
}

def configure() {
    if (!parent) {
        logWarn('configure requested but no parent app is available')
        return
    }

    // The parent app is the sole source of truth for the displayed driver version.
    state.driverVersion = parent.getVersion()

    Map cfg = parent?.getChildDriverLoggingConfig()
    if (cfg instanceof Map) {
        applyParentLogging(cfg.txtEnable, cfg.debugEnable)
    }

    scheduleDebugAutoDisableIfNeeded()

    logDebug("Configure completed with txtEnable=${settings?.txtEnable}, debugEnable=${settings?.debugEnable}, version=${getVersion()}")

    parent.deviceConfigure(device.deviceNetworkId)
}

def installed() {
    configure()
}

def refresh() {
    if (!parent) {
        logWarn('refresh requested but no parent app is available')
        return
    }

    logDebug('Refresh requested; delegating to parent app')
    parent.deviceRefresh(device.deviceNetworkId)
}

def updated() {
    unschedule('logsOff')
    parent?.updateLoggingFromDriver(settings?.txtEnable, settings?.debugEnable)
    configure()
}

//
//    PARENT EVENT UPDATES
//

private void sendEventIfChanged(String name, Object value) {
    Object current = device.currentValue(name)

    if (current != value) {
        sendEvent(name: name, value: value)
        logText("${name} is ${value}")
    } else {
        logDebug("Skipping unchanged attribute ${name}=${value}")
    }
}

def updateFromParent(Map values) {
    logDebug("Received ${values?.size() ?: 0} value(s) from parent")
    values.each { String name, value ->
        sendEventIfChanged(name, value)
    }
}

//
//    LOGGING CONFIGURATION AND SYNCHRONISATION
//

private void applyParentLogging(txtEnableValue, debugEnableValue) {
    Boolean descEnabled = normalizeBoolean(txtEnableValue, true)
    Boolean debugEnabled = normalizeBoolean(debugEnableValue, false)

    updateBooleanSettingIfChanged('txtEnable', descEnabled)
    updateBooleanSettingIfChanged('debugEnable', debugEnabled)
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

private Integer getParentDebugAutoDisableSeconds() {
    try {
        return normalizeDebugAutoDisableSeconds(parent?.getDebugAutoDisableSeconds())
    } catch (Exception ignored) {
        return 1800
    }
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

private void logText(String msg) {
    if (descriptionTextLoggingEnabled()) log.info "${device.displayName}: ${msg}"
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
