/**
 *  --------------------------------------------------------------------------------------------------------------
 *  Virtual Blind Device
 *  --------------------------------------------------------------------------------------------------------------
 *
 *  Author      : Vinny Wadding
 *  Namespace   : vinnyw
 *  Version     : Parent-managed (via child app -> parent app)
 *  Date        : 2026-09-22
 *
 *  Description :
 *      Thin WindowShade presentation driver for one virtual blind.
 *
 *      Attributes:
 *          position     (number) : blind position percentage
 *          windowShade  (enum)   : standard Hubitat WindowShade state
 *          lastMovement (enum)   : last movement direction (up/down)
 *
 *      Capabilities:
 *          Actuator
 *          Configuration
 *          WindowShade
 *
 *      Note:
 *          This is a thin presentation driver. Movement logic and scheduling are not implemented
 *          in the driver. Commands are forwarded to the structural Group parent and events are
 *          published from values supplied by the suite apps.
 *
 *  --------------------------------------------------------------------------------------------------------------
 */

metadata {
    definition(
        name: 'VirtualBlindDevice-0.0',
        namespace: 'vinnyw',
        author: 'Vinny Wadding',
    ) {
        capability 'Actuator'
        capability 'Configuration'
        capability 'WindowShade'

        attribute 'lastMovement', 'enum', ['up', 'down']
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

private String formatDisplayVersion(Object versionValue) {
    String version = versionValue?.toString()?.trim()
    return version ? "v${version}" : 'unknown'
}

def getVersion() {
    return parent?.getVersion() ?: 'unknown'
}

private void synchronizeDriverVersion() {
    String currentVersion = getVersion()?.toString()?.trim() ?: 'unknown'
    String previousVersion = state?.DriverVersion?.toString()

    state.DriverVersion = currentVersion

    if (!previousVersion || previousVersion != currentVersion) {
        log.info "Driver Version: ${formatDisplayVersion(currentVersion)}"
    }
}

def configure() {
    Map cfg = parent?.getBlindDriverLoggingConfig(device)
    if (cfg instanceof Map) {
        applyPresentationConfiguration(
            cfg.txtEnable,
            cfg.debugEnable,
            cfg.debugAutoDisableSeconds
        )
    } else {
        scheduleDebugAutoDisableIfNeeded()
    }

    synchronizeDriverVersion()
    logDebug("Configure completed with txtEnable=${settings?.txtEnable}, debugEnable=${settings?.debugEnable}")
}

def installed() {
    configure()
}

def updated() {
    unschedule('logsOff')
    parent?.updateBlindLoggingFromDriver(
        device,
        settings?.txtEnable,
        settings?.debugEnable
    )
    configure()
}

//
//    LOGGING CONFIGURATION & SYNC
//

void applyPresentationConfiguration(
    txtEnableValue,
    debugEnableValue,
    debugAutoDisableSecondsValue = null
) {
    Boolean descEnabled = normalizeBoolean(txtEnableValue, true)
    Boolean debugEnabled = normalizeBoolean(debugEnableValue, false)

    updateBooleanSettingIfChanged('txtEnable', descEnabled)
    updateBooleanSettingIfChanged('debugEnable', debugEnabled)

    scheduleDebugAutoDisableIfNeeded(debugAutoDisableSecondsValue)
}

private void updateBooleanSettingIfChanged(String name, Boolean newValue) {
    Boolean currentValue = normalizeBoolean(settings?."${name}", newValue)
    if (currentValue != newValue) {
        device.updateSetting(name, [value: newValue, type: 'bool'])
    }
}

private Integer normalizeDebugAutoDisableSeconds(value) {
    try {
        Integer seconds = value as Integer
        return seconds > 0 ? seconds : null
    } catch (Exception ignored) {
        return null
    }
}

private Integer debugAutoDisableSeconds() {
    Map cfg = parent?.getBlindDriverLoggingConfig(device)
    return normalizeDebugAutoDisableSeconds(cfg?.debugAutoDisableSeconds)
}

private Integer debugAutoDisableMinutes() {
    Integer seconds = debugAutoDisableSeconds()
    return seconds ? (int) (seconds / 60) : 0
}

def logsOff() {
    if (!debugLoggingEnabled()) return

    updateBooleanSettingIfChanged('debugEnable', false)

    try {
        parent?.updateBlindLoggingFromDriver(
            device,
            settings?.txtEnable,
            false
        )
    } catch (Exception ignored) {
    }

    log.warn "${device.displayName}: Debug logging disabled automatically after ${debugAutoDisableMinutes()} minutes"
}

private void scheduleDebugAutoDisableIfNeeded(value = null) {
    unschedule('logsOff')

    if (debugLoggingEnabled()) {
        Integer seconds = normalizeDebugAutoDisableSeconds(
            value != null ? value : debugAutoDisableSeconds()
        )
        if (seconds) {
            runIn(seconds, 'logsOff')
            logDebug("Debug logging will automatically turn off in ${(int)(seconds / 60)} minutes")
        } else {
            logWarn('Debug logging timeout unavailable from parent app; auto-disable was not scheduled.')
        }
    }
}

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

void open() {
    logDebug('Open command')
    parent.componentOpen(device)
}

void close() {
    logDebug('Close command')
    parent.componentClose(device)
}

void setPosition(BigDecimal position) {
    logDebug("Set position command=${position}")
    parent.componentSetPosition(device, position)
}

void startPositionChange(String direction) {
    parent.componentStartPositionChange(device, direction)
}

void stopPositionChange() {
    logDebug('Stop position change command')
    parent.componentStopPositionChange(device)
}

void parse(List<Map> events) {
    events?.each { Map eventData ->
        if (eventData?.name && eventData.containsKey('value')) {
            String eventName = eventData.name?.toString()
            String newValue = eventData.value?.toString()
            String oldValue = device.currentValue(eventName)?.toString()

            sendEvent(eventData)

            if (descriptionTextLoggingEnabled() && oldValue != newValue) {
                log.info "${device.displayName} ${eventName} is ${newValue}"
            }

            logDebug("Published ${eventName}=${newValue}")
        }
    }
}
