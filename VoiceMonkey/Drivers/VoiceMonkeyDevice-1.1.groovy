/**
 *  --------------------------------------------------------------------------------------------------------------
 *  VoiceMonkey Device
 *  --------------------------------------------------------------------------------------------------------------
 *
 *  Author      : Vinny Wadding
 *  Namespace   : vinnyw
 *  Version     : 1.1.32
 *  Date        : 2026-05-01
 *
 *  Description :
 *      VoiceMonkey child driver for queued speech dispatch.
 *
 *      Attributes:
 *          queueStatus   (enum)    : status
 *          queueSize     (number)  : queued item count
 *          lastActivity  (string)  : formatted timestamp
 *
 *      Capabilities:
 *          Sensor
 *          Actuator
 *          SpeechSynthesis
 *          Configuration
 *
 *  --------------------------------------------------------------------------------------------------------------
 */

metadata {
    definition(
        name: 'VoiceMonkeyDevice-1.1',
        namespace: 'vinnyw',
        author: 'Vinny Wadding',
        importUrl: 'https://raw.githubusercontent.com/vinnyw/hubitat/master/VoiceMonkey/Drivers/VoiceMonkeyDevice-1.1.groovy'
    ) {
        capability 'Actuator'
        capability 'Sensor'
        capability 'SpeechSynthesis'
        capability 'Configuration'

        attribute 'queueStatus', 'enum', ['idle', 'enqueueing', 'waiting', 'dispatching', 'confirmed', 'failed']
        attribute 'queueSize', 'number'
        attribute 'lastActivity', 'string'

        command 'speak', [
            [name: 'Text*', type: 'STRING', description: 'Text to speak']
        ]
        command 'clearQueue'
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
    String previousVersion = state?.driverVersion?.toString()

    state.driverVersion = currentVersion

    if (!previousVersion) {
        log.info "driver version: ${formatDisplayVersion(currentVersion)}"
    } else if (previousVersion != currentVersion) {
        log.info "driver version: ${formatDisplayVersion(currentVersion)}"
    }
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
    removeObsoleteAttributes()
    synchronizeDriverVersion()
    initializeQueueStateIfNeeded()

    logDebug("Configure completed with txtEnable=${settings?.txtEnable}, debugEnable=${settings?.debugEnable}, version=${getVersion()}")
}

def installed() {
    configure()
}

def updated() {
    parent?.updateLoggingFromDriver(settings?.txtEnable, settings?.debugEnable)
    configure()
}

//
//    ATTRIBUTE CLEANUP & INITIALIZATION
//

private void deleteCurrentStateIfPresent(String attributeName) {
    try {
        if (device.currentValue(attributeName) != null) {
            device.deleteCurrentState(attributeName)
        }
    } catch (Exception ignored) {
    }
}

private void initializeQueueStateIfNeeded() {
    if (device.currentValue('queueSize') == null) {
        sendEvent(name: 'queueSize', value: 0, isStateChange: false, type: 'digital')
    }

    if (device.currentValue('queueStatus') == null) {
        sendEvent(name: 'queueStatus', value: 'idle', isStateChange: false, type: 'digital')
    }

    if (device.currentValue('lastActivity') == null) {
        sendEvent(name: 'lastActivity', value: formatEpochMillis(now()), isStateChange: false, type: 'digital')
    }
}

private void removeObsoleteAttributes() {
    deleteCurrentStateIfPresent('lastMessage')
}

//
//    USER COMMANDS
//

def clearQueue() {
    logInfo('Queue clear requested')
    parent?.clearQueueFromDevice(device.deviceNetworkId)
}

def speak(String text) {
    enqueueFromDriver(text, null)
}

def speak(String text, Object volume) {
    enqueueFromDriver(text, null)
}

def speak(String text, Object volume, Object voice) {
    String cleanVoice = voice?.toString()?.trim() ?: null
    enqueueFromDriver(text, cleanVoice)
}

//
//    CHILD APP CALLBACKS
//

def queueMessageEnqueuedFromChild(String text, Object queueSizeValue = null) {
    String cleanText = normalizeMessageText(text)
    Integer queueSize = safeWholeNumber(queueSizeValue, currentQueueSize())

    if (cleanText && descriptionTextLoggingEnabled()) {
        log.info "message enqueued: ${cleanText}"
    }

    Boolean sizeChanged = updateQueueSize(queueSize, true)
    Boolean statusChanged = maybeSetEnqueueingStatus(queueSize)

    if (cleanText || sizeChanged == true || statusChanged == true) {
        touchActivity()
    }
}

def queueStateFromChild(Object queueSizeValue, String queueStatusValue) {
    Integer queueSize = safeWholeNumber(queueSizeValue, currentQueueSize())
    String queueStatus = normalizeQueueStatus(queueStatusValue, 'idle')

    Boolean sizeChanged = updateQueueSize(queueSize, true)
    Boolean statusChanged = updateQueueStatus(queueStatus, true)

    if (sizeChanged == true || statusChanged == true) {
        touchActivity()
    }
}

def queueStatusFromChild(String queueStatusValue) {
    String queueStatus = normalizeQueueStatus(queueStatusValue, 'idle')

    if (updateQueueStatus(queueStatus, true) == true) {
        touchActivity()
    }
}

def touchActivityFromChild() {
    touchActivity()
}

//
//    INTERNAL ROUTING
//

private void enqueueFromDriver(String text, String voice = null) {
    String cleanText = normalizeMessageText(text)
    if (!cleanText) {
        logDebug('Ignoring empty TTS request from device command')
        return
    }

    String cleanVoice = voice?.toString()?.trim() ?: null

    logDebug('Forwarding speak request to parent app')

    parent?.enqueueSpeakFromDevice(
        device.deviceNetworkId,
        cleanText,
        cleanVoice,
        null
    )
}

//
//    LOGGING CONFIGURATION & SYNC
//

private void applyParentLogging(txtEnableValue, debugEnableValue, debugAutoDisableSecondsValue) {
    Boolean descEnabled = normalizeBoolean(txtEnableValue, true)
    Boolean debugEnabled = normalizeBoolean(debugEnableValue, false)

    updateBooleanSettingIfChanged('txtEnable', descEnabled)
    updateBooleanSettingIfChanged('debugEnable', debugEnabled)
}

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

private void updateBooleanSettingIfChanged(String name, Boolean newValue) {
    Boolean currentValue = normalizeBoolean(settings?."${name}", newValue)
    if (currentValue != newValue) {
        device.updateSetting(name, [value: newValue, type: 'bool'])
    }
}

//
//    QUEUE STATE MANAGEMENT
//

private Integer currentQueueSize() {
    return safeWholeNumber(device.currentValue('queueSize'), 0)
}

private String currentQueueStatus() {
    return normalizeQueueStatus(device.currentValue('queueStatus'), 'idle')
}

private Boolean maybeSetEnqueueingStatus(Integer queueSize) {
    if (queueSize == null || queueSize.intValue() != 1) return false

    String currentStatus = currentQueueStatus()
    if (!(currentStatus in ['idle', 'failed'])) return false

    return updateQueueStatus('enqueueing', true)
}

private void touchActivity() {
    sendIfChanged('lastActivity', formatEpochMillis(now()))
}

private Boolean updateQueueSize(Integer queueSize, Boolean logWhenChanged) {
    Boolean changed = sendIfChanged('queueSize', queueSize)
    if (changed == true && logWhenChanged == true && descriptionTextLoggingEnabled()) {
        log.info "queue size: ${queueSize}"
    }

    return changed
}

private Boolean updateQueueStatus(String queueStatus, Boolean logWhenChanged) {
    Boolean changed = sendIfChanged('queueStatus', queueStatus)
    if (changed == true && logWhenChanged == true && descriptionTextLoggingEnabled()) {
        log.info "queue status: ${queueStatus}"
    }

    return changed
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

private void logDebug(String message) {
    if (debugLoggingEnabled()) {
        log.debug "${device.displayName}: ${message}"
    }
}

private void logInfo(String message) {
    if (descriptionTextLoggingEnabled()) {
        log.info "${device.displayName}: ${message}"
    }
}

//
//    GENERAL UTILITIES
//

private String formatEpochMillis(Object epochMillis) {
    return epochMillis == null ? '' : epochMillis.toString()
}

private Boolean normalizeBoolean(value, Boolean defaultValue) {
    if (value == null) return defaultValue
    if (value instanceof Boolean) return value

    String s = value.toString().trim().toLowerCase()
    if (s == 'true') return true
    if (s == 'false') return false

    return defaultValue
}

private String normalizeMessageText(Object value) {
    String text = value?.toString()
    if (text == null) return null

    String sanitized = text
        .replaceAll(/[\r\n\t\f]+/, ' ')
        .replaceAll(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/, ' ')
        .replaceAll(/\p{Cf}+/, '')
        .replaceAll(/[\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]+/, ' ')
        .replaceAll(/\s+/, ' ')
        .trim()

    return sanitized ? sanitized : null
}

private String normalizeQueueStatus(Object value, String fallbackValue) {
    String text = value?.toString()?.trim()?.toLowerCase()
    if (!text) return fallbackValue

    switch (text) {
        case 'idle':
            return 'idle'
        case 'queued':
        case 'enqueueing':
            return 'enqueueing'
        case 'waiting':
            return 'waiting'
        case 'dispatching':
        case 'submitted':
            return 'dispatching'
        case 'accepted':
        case 'confirmed':
            return 'confirmed'
        case 'completed':
            return 'idle'
        case 'failed':
            return 'failed'
        default:
            return fallbackValue
    }
}

private Integer safeWholeNumber(Object value, Integer fallbackValue) {
    try {
        if (value == null) return fallbackValue
        if (value instanceof Number) return ((Number) value).intValue()
        String text = value.toString().trim()
        if (!text) return fallbackValue
        return text.toBigDecimal().intValue()
    } catch (Exception ignored) {
        return fallbackValue
    }
}

private Boolean sendIfChanged(String name, value) {
    if (device.currentValue(name)?.toString() != value?.toString()) {
        sendEvent(name: name, value: value)
        return true
    }

    return false
}
