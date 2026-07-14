definition(
    name: 'Battery-1.0',
    namespace: 'vinnyw',
    author: 'Vinny Wadding',
    description: 'Virtual Battery child app',
    parent: 'vinnyw:Virtual Battery',
    category: 'Convenience',
    importUrl: 'https://raw.githubusercontent.com/vinnyw/hubitat/master/VirtualBattery/Apps/Battery-1.0.groovy',
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
            paragraph 'Runtime represents the total accumulated time that this virtual battery has been ON. It is stored internally in seconds and is calculated by adding each completed ON session to the saved total; while the device remains ON, the elapsed time since the current session started is added dynamically. The resulting runtime is displayed as hours, minutes, and seconds and is used with Runtime Discharge to calculate the remaining virtual battery percentage.'
            paragraph "<b>Runtime Display:</b> ${formatDuration(getRuntimeSeconds())}"

            input name: 'runtimeHours', type: 'text',
                  title: 'Hours',
                  width: 2,
                  defaultValue: '00',
                  submitOnChange: true

            input name: 'runtimeMinutes', type: 'text',
                  title: 'Minutes',
                  width: 2,
                  defaultValue: '00',
                  submitOnChange: true

            input name: 'runtimeSeconds', type: 'text',
                  title: 'Seconds',
                  width: 2,
                  defaultValue: '00',
                  submitOnChange: true

            paragraph 'Runtime Discharge represents the total accumulated ON-time required for the virtual battery to fall from 100% to 0%. The entered hours, minutes, and seconds are converted to one duration in seconds and used as the full-discharge reference in the calculation: battery percentage = 100 - (Runtime ÷ Runtime Discharge × 100), rounded to the nearest whole percent and limited to 0–100%. Capture replaces this value with the current runtime, while Append adds the current runtime to it and then resets Runtime.'
            paragraph "<b>Runtime Discharge Display:</b> ${formatDuration(getRuntimeDischargeSeconds())}"

            input name: 'runtimeDischargeHours', type: 'text',
                  title: 'Hours',
                  width: 2,
                  defaultValue: '01',
                  submitOnChange: true

            input name: 'runtimeDischargeMinutes', type: 'text',
                  title: 'Minutes',
                  width: 2,
                  defaultValue: '00',
                  submitOnChange: true

            input name: 'runtimeDischargeSeconds', type: 'text',
                  title: 'Seconds',
                  width: 2,
                  defaultValue: '00',
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
    ensureDurationTextSetting('runtimeHours', 0)
    ensureDurationTextSetting('runtimeMinutes', 0)
    ensureDurationTextSetting('runtimeSeconds', 0)

    if (settings?.runtimeDischargeHours == null &&
        settings?.runtimeDischargeMinutes == null &&
        settings?.runtimeDischargeSeconds == null) {
        Integer seconds = DEFAULT_RUNTIME_DISCHARGE_SECONDS
        app?.updateSetting('runtimeDischargeHours', [type: 'text', value: formatDurationInput((int)(seconds / 3600))])
        app?.updateSetting('runtimeDischargeMinutes', [type: 'text', value: formatDurationInput((int)((seconds % 3600) / 60))])
        app?.updateSetting('runtimeDischargeSeconds', [type: 'text', value: formatDurationInput((int)(seconds % 60))])
    }

    ensureDurationTextSetting('runtimeDischargeHours', 1)
    ensureDurationTextSetting('runtimeDischargeMinutes', 0)
    ensureDurationTextSetting('runtimeDischargeSeconds', 0)
    ensureBooleanSetting('txtEnable', true)
    ensureBooleanSetting('debugEnable', false)
}

private void clampDurationTextSetting(String name, Integer minValue, Integer maxValue, Integer defaultValue) {
    Integer value = normalizeInteger(settings?."${name}", defaultValue)
    Integer clamped = Math.max(minValue, value)
    if (maxValue != null) clamped = Math.min(maxValue, clamped)

    String formatted = formatDurationInput(clamped)
    if (settings?."${name}" == null || settings?."${name}"?.toString() != formatted) {
        app?.updateSetting(name, [type: 'text', value: formatted])
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

private void ensureDurationTextSetting(String name, Integer defaultValue) {
    if (settings?."${name}" == null) {
        app?.updateSetting(name, [type: 'text', value: formatDurationInput(defaultValue)])
    }
}

private String formatDurationInput(Integer value) {
    return String.format('%02d', Math.max(0, normalizeInteger(value, 0)))
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
    clampDurationTextSetting('runtimeHours', 0, null, 0)
    clampDurationTextSetting('runtimeMinutes', 0, 59, 0)
    clampDurationTextSetting('runtimeSeconds', 0, 59, 0)

    clampDurationTextSetting('runtimeDischargeHours', 0, null, 1)
    clampDurationTextSetting('runtimeDischargeMinutes', 0, 59, 0)
    clampDurationTextSetting('runtimeDischargeSeconds', 0, 59, 0)
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
    updateDurationSettingIfChanged(hoursName, (int)(safe / 3600))
    updateDurationSettingIfChanged(minutesName, (int)((safe % 3600) / 60))
    updateDurationSettingIfChanged(secondsName, (int)(safe % 60))
}

private void updateDurationSettingIfChanged(String name, Integer value) {
    String formatted = formatDurationInput(value)
    if (settings?."${name}"?.toString() != formatted) {
        app?.updateSetting(name, [type: 'text', value: formatted])
    }
}

private void updateRuntimeStateFromInputs() {
    Integer configuredRuntime = durationInputsToSeconds('runtimeHours', 'runtimeMinutes', 'runtimeSeconds')
    Integer configuredDischarge = durationInputsToSeconds('runtimeDischargeHours', 'runtimeDischargeMinutes', 'runtimeDischargeSeconds')

    if (configuredDischarge <= 0) {
        configuredDischarge = DEFAULT_RUNTIME_DISCHARGE_SECONDS
        secondsToDurationSettings(configuredDischarge, 'runtimeDischargeHours', 'runtimeDischargeMinutes', 'runtimeDischargeSeconds')
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
    if (getManagedChildDevice()?.currentValue('switch') != 'off') return

    Integer captured = Math.max(1, getCurrentRuntimeSeconds())
    state.runtimeDischarge = captured
    state.lastActivity = now()
    secondsToDurationSettings(captured, 'runtimeDischargeHours', 'runtimeDischargeMinutes', 'runtimeDischargeSeconds')

    publishRuntimeState()
}

def childAppendRuntimeDischarge(String dni = null) {
    if (!deviceMatchesManagedChild(dni)) return
    if (getManagedChildDevice()?.currentValue('switch') != 'off') return

    Integer runtime = Math.max(0, getCurrentRuntimeSeconds())
    Integer discharge = getRuntimeDischargeSeconds()

    Integer updatedDischarge = discharge + runtime
    state.runtimeDischarge = updatedDischarge
    secondsToDurationSettings(updatedDischarge, 'runtimeDischargeHours', 'runtimeDischargeMinutes', 'runtimeDischargeSeconds')

    state.runtime = 0
    state.startEpoch = null
    state.lastStartEpoch = null
    state.lastStopEpoch = null
    state.lastActivity = now()
    syncRuntimeInputsFromState()

    publishRuntimeState('off')
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

def childResetRuntimeDischarge(String dni = null) {
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

def childResetRuntime(String dni = null) {
    childResetRuntimeDischarge(dni)
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
    Integer discharge = normalizeInteger(state.runtimeDischarge, durationInputsToSeconds('runtimeDischargeHours', 'runtimeDischargeMinutes', 'runtimeDischargeSeconds'))
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
    secondsToDurationSettings(getStoredRuntimeSeconds(), 'runtimeHours', 'runtimeMinutes', 'runtimeSeconds')
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
