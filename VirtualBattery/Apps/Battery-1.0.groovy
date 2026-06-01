definition(
    name: 'Battery-1.0',
    namespace: 'vinnyw',
    author: 'Vinny Wadding',
    description: 'Virtual Battery child app',
    parent: 'vinnyw:Virtual Battery',
    category: 'Convenience',
    importUrl: 'https://raw.githubusercontent.com/vinnyw/hubitat/master/VirtualBattery/Apps/VirtualBattery-1.0.groovy',
    documentationLink: 'https://github.com/vinnyw/hubitat/blob/master/README.md',
    iconUrl: 'https://raw.githubusercontent.com/hubitat/HubitatPublic/master/resources/images/App%20Icons/Convenience.png',
    iconX2Url: 'https://raw.githubusercontent.com/hubitat/HubitatPublic/master/resources/images/App%20Icons/Convenience.png',
    iconX3Url: 'https://raw.githubusercontent.com/hubitat/HubitatPublic/master/resources/images/App%20Icons/Convenience.png'
)

import groovy.transform.Field

@Field static final Integer DEFAULT_RUNTIME_DISCHARGE_SECONDS = 3600

preferences {
    page(name: 'mainPage')
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


def mainPage() {
    applyDefaultSettings()
    normalizeDurationInputs()
    updateRuntimeStateFromInputs()
    ensureManagedChildDevice()
    syncChildSettings()
    publishRuntimeState()

    dynamicPage(name: 'mainPage', install: true, uninstall: true) {
        if (app?.getInstallationState() != 'COMPLETE') {
            section('Important') {
                paragraph '⚠️ Setup is not complete yet. Press <b>Done</b> to create or update the virtual device.'
            }
        }

        section() {
            label title: 'Virtual Battery Name ', submitOnChange: true, required: true
        }

        section(hideable: true, hidden: false, title: 'Runtime') {
            paragraph 'Current accumulated runtime for this virtual battery.'
            paragraph "<b>Runtime Display:</b> ${formatDuration(getRuntimeSeconds())}"

            input name: 'runtimeHours', type: 'number',
                  title: 'Hours',
                  width: 2,
                  defaultValue: 0,
                  submitOnChange: true

            input name: 'runtimeMinutes', type: 'number',
                  title: 'Minutes',
                  width: 2,
                  range: '0..59',
                  defaultValue: 0,
                  submitOnChange: true

            input name: 'runtimeSecondsPart', type: 'number',
                  title: 'Seconds',
                  width: 2,
                  range: '0..59',
                  defaultValue: 0,
                  submitOnChange: true


            paragraph 'Accumulated runtime required to discharge the virtual battery from 100% to 0%.'
            paragraph "<b>Runtime Discharge Display:</b> ${formatDuration(getRuntimeDischargeSeconds())}"
            
            input name: 'runtimeDischargeHours', type: 'number',
                  title: 'Hours',
                  width: 2,
                  defaultValue: 1,
                  submitOnChange: true

            input name: 'runtimeDischargeMinutes', type: 'number',
                  title: 'Minutes',
                  width: 2,
                  range: '0..59',
                  defaultValue: 0,
                  submitOnChange: true

            input name: 'runtimeDischargeSecondsPart', type: 'number',
                  title: 'Seconds',
                  width: 2,
                  range: '0..59',
                  defaultValue: 0,
                  submitOnChange: true

        }

        section(hideable: true, hidden: false, title: 'Logging') {
            paragraph "Debug logging automatically turns off after ${getDebugAutoDisableMinutes()} minutes."

            input name: 'txtEnable', type: 'bool',
                  title: 'Enable descriptionText logging',
                  defaultValue: true,
                  submitOnChange: true

            input name: 'debugEnable', type: 'bool',
                  title: 'Enable debug logging',
                  defaultValue: false,
                  submitOnChange: true
        }

        section() {
            String versionLabel = getDisplayVersionValue(getVersion())
            paragraph "<div style='font-size: 10px; color: #888; width: 100%; text-align: right;'>${htmlEncode(versionLabel)}</div>"
        }
    }
}


//
//    UI DEFAULTS & VALIDATION HELPERS
//

private void applyDefaultSettings() {
    ensureNumberSetting('runtimeHours', 0)
    ensureNumberSetting('runtimeMinutes', 0)
    ensureNumberSetting('runtimeSecondsPart', 0)

    if (settings?.runtimeDischargeHours == null &&
        settings?.runtimeDischargeMinutes == null &&
        settings?.runtimeDischargeSecondsPart == null) {
        Integer seconds = DEFAULT_RUNTIME_DISCHARGE_SECONDS
        app?.updateSetting('runtimeDischargeHours', [type: 'number', value: (int)(seconds / 3600)])
        app?.updateSetting('runtimeDischargeMinutes', [type: 'number', value: (int)((seconds % 3600) / 60)])
        app?.updateSetting('runtimeDischargeSecondsPart', [type: 'number', value: (int)(seconds % 60)])
    }

    ensureNumberSetting('runtimeDischargeHours', 1)
    ensureNumberSetting('runtimeDischargeMinutes', 0)
    ensureNumberSetting('runtimeDischargeSecondsPart', 0)
    ensureBooleanSetting('txtEnable', true)
    ensureBooleanSetting('debugEnable', false)
}

private void clampNumberSetting(String name, Integer minValue, Integer maxValue, Integer defaultValue) {
    Integer value = normalizeInteger(settings?."${name}", defaultValue)
    Integer clamped = Math.max(minValue, value)
    if (maxValue != null) clamped = Math.min(maxValue, clamped)

    if (settings?."${name}" == null || value != clamped) {
        app?.updateSetting(name, [type: 'number', value: clamped])
    }
}

private Integer durationInputsToSeconds(String hoursName, String minutesName, String secondsName) {
    Integer hours = normalizeInteger(settings?."${hoursName}", 0)
    Integer minutes = normalizeInteger(settings?."${minutesName}", 0)
    Integer seconds = normalizeInteger(settings?."${secondsName}", 0)

    return Math.max(0, (hours * 3600) + (minutes * 60) + seconds)
}

private void ensureBooleanSetting(String name, Boolean defaultValue) {
    if (settings?."${name}" == null) {
        app?.updateSetting(name, [type: 'bool', value: defaultValue])
    }
}

private void ensureNumberSetting(String name, Integer defaultValue) {
    if (settings?."${name}" == null) {
        app?.updateSetting(name, [type: 'number', value: defaultValue])
    }
}

private String htmlEncode(Object value) {
    String s = value?.toString() ?: ''
    return s
        .replace('&', '&amp;')
        .replace('<', '&lt;')
        .replace('>', '&gt;')
        .replace('"', '&quot;')
        .replace("'", '&#39;')
}

private void normalizeDurationInputs() {
    clampNumberSetting('runtimeHours', 0, null, 0)
    clampNumberSetting('runtimeMinutes', 0, 59, 0)
    clampNumberSetting('runtimeSecondsPart', 0, 59, 0)

    clampNumberSetting('runtimeDischargeHours', 0, null, 1)
    clampNumberSetting('runtimeDischargeMinutes', 0, 59, 0)
    clampNumberSetting('runtimeDischargeSecondsPart', 0, 59, 0)
}

private Integer normalizeInteger(value, Integer defaultValue = 0) {
    if (value == null || value.toString().trim() == '') return defaultValue
    try {
        return value as Integer
    } catch (Exception ignored) {
        return defaultValue
    }
}

private void secondsToDurationSettings(Integer totalSeconds, String hoursName, String minutesName, String secondsName) {
    Integer safe = Math.max(0, normalizeInteger(totalSeconds, 0))
    app?.updateSetting(hoursName, [type: 'number', value: (int)(safe / 3600)])
    app?.updateSetting(minutesName, [type: 'number', value: (int)((safe % 3600) / 60)])
    app?.updateSetting(secondsName, [type: 'number', value: (int)(safe % 60)])
}

private void updateRuntimeStateFromInputs() {
    Integer configuredRuntime = durationInputsToSeconds('runtimeHours', 'runtimeMinutes', 'runtimeSecondsPart')
    Integer configuredDischarge = durationInputsToSeconds('runtimeDischargeHours', 'runtimeDischargeMinutes', 'runtimeDischargeSecondsPart')

    if (configuredDischarge <= 0) {
        configuredDischarge = DEFAULT_RUNTIME_DISCHARGE_SECONDS
        secondsToDurationSettings(configuredDischarge, 'runtimeDischargeHours', 'runtimeDischargeMinutes', 'runtimeDischargeSecondsPart')
    }

    state.runtime = configuredRuntime
    state.runtimeDischarge = configuredDischarge
    state.lastActivity = state.lastActivity ?: now()
}


//
//    LIFECYCLE
//

def initialize() {
    if (!parent) {
        log.warn 'Parent app is not available. Cleaning up managed child device.'
        deleteManagedChildDevice()
        return
    }

    applyDefaultSettings()
    normalizeDurationInputs()
    updateRuntimeStateFromInputs()

    if (!ensureManagedChildDevice()) {
        log.warn "Unable to create managed child device for ${app?.label ?: childDni()}"
        return
    }

    syncChildSettings()
    scheduleDebugAutoDisableIfNeeded()
    publishRuntimeState()
}

def installed() {
    initialize()
}

def uninstalled() {
    deleteManagedChildDevice()
}

def updated() {
    unsubscribe()
    unschedule()
    initialize()
}


//
//    CHILD DEVICE COMMAND HANDLERS
//

def childCaptureRuntimeDischarge(String dni = null) {
    if (!deviceMatchesManagedChild(dni)) return

    Integer captured = Math.max(1, getCurrentRuntimeSeconds())
    state.runtimeDischarge = captured
    state.lastActivity = now()
    secondsToDurationSettings(captured, 'runtimeDischargeHours', 'runtimeDischargeMinutes', 'runtimeDischargeSecondsPart')

    publishRuntimeState()
}

def childConfigureRequest(String dni = null) {
    if (!deviceMatchesManagedChild(dni)) return
    publishRuntimeState()
}

def childOff(String dni = null) {
    if (!deviceMatchesManagedChild(dni)) return

    Long epoch = now()
    Integer elapsed = getActiveSessionSeconds(epoch)

    if (state.startEpoch) {
        state.runtime = getStoredRuntimeSeconds() + elapsed
        state.startEpoch = null
        state.lastStopEpoch = epoch
        state.lastActivity = epoch
        syncRuntimeInputsFromState()
    }

    publishRuntimeState('off')
}

def childOn(String dni = null) {
    if (!deviceMatchesManagedChild(dni)) return

    Long epoch = now()

    if (!state.startEpoch) {
        state.startEpoch = epoch
        state.lastStartEpoch = epoch
        state.lastActivity = epoch
    }

    publishRuntimeState('on')
}

def childRefreshRequest(String dni = null) {
    if (!deviceMatchesManagedChild(dni)) return
    publishRuntimeState()
}

def childResetRuntime(String dni = null) {
    if (!deviceMatchesManagedChild(dni)) return

    Long epoch = now()
    state.runtime = 0
    state.startEpoch = null
    state.lastStartEpoch = null
    state.lastStopEpoch = null
    state.lastActivity = epoch
    syncRuntimeInputsFromState()

    publishRuntimeState('off')
}


//
//    DEVICE MANAGEMENT HELPERS
//

private String childDni() {
    String stable = getStableChildDni()
    state.managedChildDni = stable
    return stable
}

def deleteManagedChildDevice() {
    try {
        def child = getManagedChildDevice()
        if (child) deleteChildDevice(child.deviceNetworkId)
    } catch (Exception e) {
        log.warn "Unable to delete managed child device ${childDni()}: ${e.message}"
    }

    state.remove('managedChildDni')
}

private Boolean deviceMatchesManagedChild(String dni) {
    return !dni || dni == childDni()
}

private Boolean ensureManagedChildDevice() {
    String dni = childDni()
    String driverType = getChildDriverType()
    String desiredLabel = app?.label?.toString()?.trim()

    def child = getChildDevice(dni)

    if (!child) {
        try {
            addChildDevice(
                'vinnyw',
                driverType,
                dni,
                [
                    name       : driverType,
                    label      : desiredLabel ?: driverType,
                    isComponent: false
                ]
            )
        } catch (Exception e) {
            log.warn "Unable to create managed child device. Verify driver ${driverType} (namespace 'vinnyw') is installed. ${e.message}"
            return false
        }
        child = getChildDevice(dni)
    }

    if (child && desiredLabel && child.label != desiredLabel) {
        child.setLabel(desiredLabel)
    }

    return child != null
}

private String getChildDriverType() {
    return "Battery-${extractShortVersion(getVersion())}"
}

private def getManagedChildDevice() {
    return getChildDevice(childDni())
}

private String getStableChildDni() {
    return "VirtualBattery-${app?.id ?: 'pending'}"
}

//
//    RUNTIME STATE HELPERS
//

private Integer calculateBatteryPercent(Integer runtime, Integer runtimeDischarge) {
    Integer discharge = Math.max(1, runtimeDischarge ?: DEFAULT_RUNTIME_DISCHARGE_SECONDS)
    BigDecimal remaining = 100G - ((runtime as BigDecimal) * 100G / (discharge as BigDecimal))
    Integer pct = remaining.setScale(0, BigDecimal.ROUND_HALF_UP) as Integer
    return Math.max(0, Math.min(100, pct))
}

private String formatDuration(value) {
    Integer total = Math.max(0, normalizeInteger(value, 0))
    Integer hours = (int)(total / 3600)
    Integer minutes = (int)((total % 3600) / 60)
    Integer seconds = (int)(total % 60)
    return String.format('%02d:%02d:%02d', hours, minutes, seconds)
}

private Integer getActiveSessionSeconds(Long epoch = now()) {
    if (!state.startEpoch) return 0

    Long started = normalizeLong(state.startEpoch, epoch)
    Long elapsedMs = Math.max(0L, epoch - started)
    return (int)(elapsedMs / 1000L)
}

private Integer getCurrentRuntimeSeconds(Long epoch = now()) {
    return Math.max(0, getStoredRuntimeSeconds() + getActiveSessionSeconds(epoch))
}

private Integer getRuntimeDischargeSeconds() {
    Integer discharge = normalizeInteger(state.runtimeDischarge, durationInputsToSeconds('runtimeDischargeHours', 'runtimeDischargeMinutes', 'runtimeDischargeSecondsPart'))
    return Math.max(1, discharge)
}

private Integer getRuntimeSeconds() {
    return getCurrentRuntimeSeconds()
}

private Integer getStoredRuntimeSeconds() {
    return Math.max(0, normalizeInteger(state.runtime, 0))
}

private Long normalizeLong(value, Long defaultValue) {
    if (value == null || value.toString().trim() == '') return defaultValue
    try {
        return value as Long
    } catch (Exception ignored) {
        return defaultValue
    }
}

private void syncRuntimeInputsFromState() {
    secondsToDurationSettings(getStoredRuntimeSeconds(), 'runtimeHours', 'runtimeMinutes', 'runtimeSecondsPart')
}


//
//    RUNTIME PUBLISHING HELPERS
//

private void publishRuntimeState(String switchValue = null) {
    def child = getManagedChildDevice()
    if (!child) return

    Integer runtime = getCurrentRuntimeSeconds()
    Integer runtimeDischarge = getRuntimeDischargeSeconds()
    Integer batteryPct = calculateBatteryPercent(runtime, runtimeDischarge)
    Long activityEpoch = normalizeLong(state.lastActivity, now())

    if (switchValue != null) {
        child.publishFromApp('switch', switchValue, null)
    }

    child.publishFromApp('battery', batteryPct, '%')
    child.publishFromApp('runtime', runtime, 's')
    child.publishFromApp('runtimeDisplay', formatDuration(runtime), null)
    child.publishFromApp('runtimeDischarge', runtimeDischarge, 's')
    child.publishFromApp('runtimeDischargeDisplay', formatDuration(runtimeDischarge), null)
    child.publishFromApp('lastActivity', activityEpoch, null)

    logDebug("Published runtime=${runtime}, runtimeDischarge=${runtimeDischarge}, battery=${batteryPct}, lastActivity=${activityEpoch}")
}



//
//    LOGGING CONFIGURATION & SYNC
//

def getChildDriverLoggingConfig() {
    return [
        txtEnable              : descriptionTextLoggingEnabled(),
        debugEnable            : debugLoggingEnabled(),
        debugAutoDisableSeconds: getDebugAutoDisableSeconds(),
        debugAutoDisableMinutes: getDebugAutoDisableMinutes()
    ]
}

def syncChildSettings() {
    def child = getManagedChildDevice()
    if (!child) return

    try {
        child.configure()
    } catch (Exception e) {
        log.warn "Unable to configure child device from app settings: ${e.message}"
    }
}

def updateLoggingFromDriver(txtEnableValue, debugEnableValue) {
    Boolean descEnabled = normalizeBoolean(txtEnableValue, true)
    Boolean debugEnabled = normalizeBoolean(debugEnableValue, false)

    app?.updateSetting('txtEnable', [value: descEnabled, type: 'bool'])
    app?.updateSetting('debugEnable', [value: debugEnabled, type: 'bool'])

    syncChildSettings()
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

private Integer getDebugAutoDisableMinutes() {
    return (int) (getDebugAutoDisableSeconds() / 60)
}

private Integer getDebugAutoDisableSeconds() {
    try {
        Integer seconds = parent?.getDebugAutoDisableSeconds() as Integer
        return seconds > 0 ? seconds : 1800
    } catch (Exception ignored) {
        return 1800
    }
}

private void logDebug(String msg) {
    if (debugLoggingEnabled()) log.debug "${app.label}: ${msg}"
}

def logsOff() {
    app?.updateSetting('debugEnable', [value: false, type: 'bool'])
    syncChildSettings()
    log.warn "${app.label}: Debug logging disabled automatically after ${getDebugAutoDisableMinutes()} minutes"
}

private Boolean normalizeBoolean(value, Boolean defaultValue) {
    if (value == null) return defaultValue
    if (value instanceof Boolean) return value
    String s = value.toString().trim().toLowerCase()
    if (['true', '1', 'yes', 'on'].contains(s)) return true
    if (['false', '0', 'no', 'off'].contains(s)) return false
    return defaultValue
}

private void scheduleDebugAutoDisableIfNeeded() {
    unschedule('logsOff')

    if (debugLoggingEnabled()) {
        runIn(getDebugAutoDisableSeconds(), 'logsOff')
        logDebug("Debug logging will automatically turn off in ${getDebugAutoDisableMinutes()} minutes")
    }
}


//
//    VERSION HELPERS
//

private String extractShortVersion(String version) {
    String raw = version?.toString()?.trim() ?: 'unknown'
    def matcher = raw =~ /(\d+\.\d+)/
    return matcher.find() ? matcher.group(1) : raw
}

private String getDisplayVersionValue(Object versionValue) {
    String version = versionValue?.toString()?.trim()
    return version ? "v${version}" : 'unknown'
}
