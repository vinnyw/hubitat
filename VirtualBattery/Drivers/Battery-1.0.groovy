/**
 *  --------------------------------------------------------------------------------------------------------------
 *  Virtual Battery Child Device
 *  --------------------------------------------------------------------------------------------------------------
 *
 *  Author      : Vinny Wadding
 *  Namespace   : vinnyw
 *  Version     : Parent-managed via child app
 *
 *  Description :
 *      Presentation and command surface for a time-based virtual battery.
 *      All settings and runtime state are owned by the child app.
 *
 *      Driver attributes:
 *          switch                  (enum)   : on/off runtime session state
 *          battery                 (number) : virtual battery percentage
 *          runtime                 (number) : app-owned runtime in seconds
 *          runtimeDisplay          (string) : runtime formatted as HH:MM:SS
 *          runtimeDischarge        (number) : app-owned discharge runtime in seconds
 *          runtimeDischargeDisplay (string) : discharge runtime formatted as HH:MM:SS
 *          lastActivity            (number) : epoch time in milliseconds
 *
 *  --------------------------------------------------------------------------------------------------------------
 */

metadata {
    definition(
        name: 'Battery-1.0',
        namespace: 'vinnyw',
        author: 'Vinny Wadding'
    ) {
        capability 'Actuator'
        capability 'Battery'
        capability 'Switch'
        capability 'Refresh'
        capability 'Configuration'

        attribute 'runtime', 'number'
        attribute 'runtimeDisplay', 'string'
        attribute 'runtimeDischarge', 'number'
        attribute 'runtimeDischargeDisplay', 'string'
        attribute 'lastActivity', 'number'

        command 'resetRuntime', [
            [   name: 'Reset Runtime', 
                type: 'STRING', 
                description: '<small>Resets the accumulated runtime counter.</small><br><br>']
            ]
        command 'captureRuntimeDischarge', [
            [   name: 'Capture Runtime Discharge', 
                type: 'STRING', 
                description: '<small>Adjust the period that the battery percentage is reported to suit your requirements.</small><br><br>']
            ]
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
//    LIFECYCLE
//

def configure() {
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

    parent?.childConfigureRequest(device.deviceNetworkId)

    logDebug("Configure completed with txtEnable=${settings?.txtEnable}, debugEnable=${settings?.debugEnable}, version=${currentVersion}")
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
//    DEVICE COMMAND HANDLERS
//

def captureRuntimeDischarge() {
    parent?.childCaptureRuntimeDischarge(device.deviceNetworkId)
}

def off() {
    parent?.childOff(device.deviceNetworkId)
}

def on() {
    parent?.childOn(device.deviceNetworkId)
}

def refresh() {
    parent?.childRefreshRequest(device.deviceNetworkId)
}

def resetRuntime() {
    parent?.childResetRuntime(device.deviceNetworkId)
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

private Boolean normalizeBoolean(value, Boolean defaultValue) {
    if (value == null) return defaultValue
    if (value instanceof Boolean) return value
    String s = value.toString().trim().toLowerCase()
    if (['true', '1', 'yes', 'on'].contains(s)) return true
    if (['false', '0', 'no', 'off'].contains(s)) return false
    return defaultValue
}


//
//    APP PUBLISHING HELPERS
//

def publishFromApp(String attributeName, value, String unitValue = null) {
    if (!attributeName) return

    Map event = [
        name : attributeName,
        value: value,
        type : 'digital'
    ]

    if (unitValue != null) {
        event.unit = unitValue
    }

    def current = device.currentValue(attributeName)
    if ((current?.toString() ?: '') != (value?.toString() ?: '')) {
        event.isStateChange = true
    } else {
        event.isStateChange = false
    }

    sendEvent(event)

    if (event.isStateChange == true && descriptionTextLoggingEnabled()) {
        String displayValue = unitValue != null ? "${value}${unitValue}" : "${value}"
        log.info "${device.displayName} ${attributeName} is ${displayValue}"
    }
}
