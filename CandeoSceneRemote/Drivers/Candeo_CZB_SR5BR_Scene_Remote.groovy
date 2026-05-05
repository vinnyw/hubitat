/**
 *  --------------------------------------------------------------------------------------------------------------
 *  Candeo C-ZB-SR5BR Scene Remote
 *  --------------------------------------------------------------------------------------------------------------
 *
 *  Author      : Vinny Wadding
 *  Namespace   : vinnyw
 *  Version     : 1.1.14
 *  Date        : 2026-05-05
 *
 *  Description :
 *      Zigbee driver for the Candeo C-ZB-SR5BR scene remote.
 *
 *      Supports five front buttons plus rotary ring actions exposed as button events.
 *      Rotation is sampled over a configurable time window and emitted as a single
 *      button event, with button 6 used for clockwise rotation and button 7 used
 *      for anticlockwise rotation.
 *
 *      Attributes:
 *          rotationClickCount  (number) : signed click count for the last emitted rotation
 *
 *      Capabilities:
 *          PushableButton
 *          DoubleTapableButton
 *          ReleasableButton
 *          HoldableButton
 *          Battery
 *          Sensor
 *          Configuration
 *
 *      Notes:
 *          Buttons 1 through 5 are mapped from the remote's front buttons.
 *          Button 6 represents clockwise ring rotation.
 *          Button 7 represents anticlockwise ring rotation.
 *
 *          Battery reporting, rotation window, and rotation cooldown are configurable
 *          in driver preferences.
 *
 *      Attribution and Disclaimer:
 *          I am in no way claiming this driver to be wholly my original work.
 *          This version is based heavily on the brilliant work of the Candeo
 *          Engineering and Development teams.
 *
 *          The original driver can be found in the Candeo repository:
 *          https://github.com/candeosmart/hubitat-zigbee/blob/main/Candeo%20C-ZB-SR5BR%20Smart%20Scene%20Remote.groovy
 *
 *  --------------------------------------------------------------------------------------------------------------
 */
import groovy.transform.Field

metadata {
    definition(
        name: 'Candeo C-ZB-SR5BR Scene Remote',
        namespace: 'vinnyw',
        author: 'Vinny Wadding',
        singleThreaded: true
    ) {
        capability 'PushableButton'
        capability 'DoubleTapableButton'
        capability 'ReleasableButton'
        capability 'HoldableButton'
        capability 'Battery'
        capability 'Sensor'
        capability 'Configuration'

        attribute 'rotationClickCount', 'number'

        fingerprint profileId: '0104', endpointId: '01', inClusters: '0000,0001,0003,0B05,1000', outClusters: '0003,0004,0005,0006,0008,0019,1000', manufacturer: 'Candeo', model: 'C-ZB-SR5BR', deviceJoinName: 'Candeo C-ZB-SR5BR Smart Scene Remote'
    }

    preferences {
        input name: 'txtEnable', type: 'bool',
              title: 'Enable descriptionText logging',
              defaultValue: true

        input name: 'debugEnable', type: 'bool',
              title: 'Enable debug logging',
              defaultValue: false

        input name: 'rotationWindow', type: 'enum',
              title: 'Rotation Window',
              description: '<small>Counts dial clicks for this period, then emits one button event. Button 6 is clockwise/positive and button 7 is anticlockwise/negative.</small><br><br>',
              options: ROTATIONWINDOW,
              defaultValue: DEFAULT_ROTATIONWINDOW

        input name: 'rotationCooldown', type: 'enum',
              title: 'Rotation Cooldown',
              description: '<small>After rotation event is emitted, ignore additional dial rotation commands for this period. This helps prevent overlapping events when reversing direction immediately.</small><br><br>',
              options: ROTATIONCOOLDOWN,
              defaultValue: DEFAULT_ROTATIONCOOLDOWN

        input name: 'batteryReporting', type: 'enum',
              title: 'Battery Reporting',
              description: '<small>Adjust the period that the battery percentage is reported to suit your requirements.</small><br><br>',
              options: BATTERYREPORT,
              defaultValue: DEFAULT_BATTERYREPORT
    }
}

//
//    DRIVER CONSTANTS
//

private @Field final String DRIVER_VERSION = '1.1.14'
private @Field final String DRIVER_NAME = 'Candeo C-ZB-SR5BR'

private @Field final String DEFAULT_BATTERYREPORT = '28800'
private @Field final String DEFAULT_ROTATIONCOOLDOWN = '250'
private @Field final String DEFAULT_ROTATIONWINDOW = '500'

private @Field final Integer LOGSOFF = 1800
private @Field final Integer ZIGBEEDELAY = 1000

//
//    PREFERENCE OPTIONS
//

private @Field final Map BATTERYREPORT = [
    '3600': '1h',
    '5400': '1.5h',
    '7200': '2h',
    '10800': '3h',
    '21600': '6h',
    '28800': '8h',
    '43200': '12h',
    '64800': '18h'
]

private @Field final Map ROTATIONCOOLDOWN = [
    '0': 'Disabled',
    '50': '50ms',
    '100': '100ms',
    '150': '150ms',
    '200': '200ms',
    '250': '250ms',
    '300': '300ms',
    '350': '350ms',
    '400': '400ms',
    '450': '450ms',
    '500': '500ms'
]

private @Field final Map ROTATIONWINDOW = [
    '250': '250ms',
    '500': '500ms',
    '750': '750ms',
    '1000': '1000ms'
]

//
//    BUTTON AND RING MAPPINGS
//

private @Field final Map BUTTON_EVENTS = [
    '01': 'pushed',
    '02': 'doubleTapped',
    '03': 'held',
    '04': 'released'
]

private @Field final Map BUTTON_NUMBERS = [
    '01': 1,
    '02': 2,
    '04': 3,
    '08': 4,
    '10': 5
]

private @Field final Map RING_BUTTONS = [
    '01': 6,
    '02': 7
]

//
//    VERSION
//

String getVersion() {
    return DRIVER_VERSION
}

//
//    UI / PREFERENCES
//

// Preferences are declared in metadata { preferences { ... } } above.

//
//    LIFECYCLE
//

List<String> configure() {
    ensureDriverVersionState(false)
    logDebug('configure called')
    logDebug('battery powered device requires manual wakeup to accept configuration commands')

    Integer batteryTime = getBatteryReport()
    List<String> cmds = [
        "zdo bind 0x${device.deviceNetworkId} 0x${device.endpointId} 0x01 0x0001 {${device.zigbeeId}} {}",
        "delay ${ZIGBEEDELAY}",
        "he cr 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0001 0x0021 ${DataType.UINT8} 3600 ${batteryTime} {${intTo16bitUnsignedHex(2)}}",
        "delay ${ZIGBEEDELAY}",
        "he raw 0x${device.deviceNetworkId} 0x01 0x${device.endpointId} 0x0001 {10 00 08 00 2100}",
        "delay ${ZIGBEEDELAY}",
        "he rattr 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0001 0x0021 {}"
    ]

    if (!isZigbee30()) {
        logDebug('older zigbee version detected, binding manufacturer cluster')
        cmds += [
            "zdo bind 0x${device.deviceNetworkId} 0x${device.endpointId} 0x01 0xFF03 {${device.zigbeeId}} {}",
            "delay ${ZIGBEEDELAY}"
        ]
    }

    logDebug("configure returning ${cmds.size()} command entries")
    return cmds
}

private void initializeLifecycle(Boolean newInstall) {
    unschedule('logsOff')
    unschedule('emitRingRotationEvent')

    resetRotationState(true)
    initializeAttributes()
    updateDriverVersionState()
    scheduleDebugAutoDisableIfNeeded()

    if (newInstall) {
        logDebug('driver installed')
    }
    else {
        logDebug('preferences updated')
    }

    logDebug("batteryReporting setting is: ${BATTERYREPORT[(settings?.batteryReporting ?: DEFAULT_BATTERYREPORT)]}")
    logDebug("rotationWindow setting is: ${ROTATIONWINDOW[(settings?.rotationWindow ?: DEFAULT_ROTATIONWINDOW)]}")
    logDebug("rotationCooldown setting is: ${ROTATIONCOOLDOWN[(settings?.rotationCooldown ?: DEFAULT_ROTATIONCOOLDOWN)]}")
    logDebug('if you changed Battery Reporting, wake the remote if needed and press Configure')
}

void installed() {
    initializeLifecycle(true)
}

void uninstalled() {
    unschedule()
    state.clear()
}

void updated() {
    initializeLifecycle(false)
}

//
//    COMMANDS
//

void doubleTap(BigDecimal button) {
    sendButtonEvent('doubleTapped', button)
}

void hold(BigDecimal button) {
    sendButtonEvent('held', button)
}

void push(BigDecimal button) {
    sendButtonEvent('pushed', button)
}

void release(BigDecimal button) {
    sendButtonEvent('released', button)
}

//
//    PARSING
//

List<Map<String, ?>> parse(String description) {
    if (!description) {
        logWarn('empty description received')
        return null
    }

    ensureDriverVersionState(false)

    logTrace('parse called')
    logDebug("parse description: ${description}")

    Map<String, ?> descriptionMap = null
    try {
        descriptionMap = zigbee.parseDescriptionAsMap(description)
    }
    catch (Exception e) {
        logError("failed to parse Zigbee description: ${e}")
        return null
    }

    if (!descriptionMap) {
        logDebug('parsed descriptionMap was empty')
        return null
    }

    String cluster = safeHex(descriptionMap.cluster)
    String attrId = safeHex(descriptionMap.attrId)
    String command = safeHex(descriptionMap.command)
    String value = safeHex(descriptionMap.value)
    String data = safeHex(descriptionMap.data)
    String clusterInt = safeHex(descriptionMap.clusterInt)
    String profileId = safeHex(descriptionMap.profileId)

    if (isBatteryReport(cluster, attrId, command)) {
        Map event = getBatteryEvent(descriptionMap)
        return event ? [event] : null
    }

    if (isButtonCommand(cluster, command, data)) {
        Map event = getButtonEvent(descriptionMap)
        return event ? [event] : null
    }

    if (isRingCommand(cluster, command, data)) {
        handleRingRotation(descriptionMap)
        return null
    }

    if (profileId == '0000' || clusterInt == '0013') {
        logDebug('ignoring device announcement or leave indication')
        return null
    }

    logDebug("unhandled message: ${descriptionMap}")
    return null
}

private Map<String, ?> getBatteryEvent(Map<String, ?> descriptionMap) {
    String rawValue = safeHex(descriptionMap.value)
    if (!rawValue) {
        return null
    }

    Integer rawBattery
    try {
        rawBattery = Integer.parseInt(rawValue, 16)
    }
    catch (Exception ignored) {
        return null
    }

    if (rawBattery == null || rawBattery <= 0 || rawBattery == 255) {
        logDebug("ignoring invalid battery value ${rawBattery}")
        return null
    }

    Integer batteryPct = Math.min(100, Math.max(1, rawBattery))
    String descriptionText = "${device.displayName} battery is ${batteryPct}%"

    logEvent(descriptionText)

    return [
        name           : 'battery',
        value          : batteryPct,
        unit           : '%',
        descriptionText: descriptionText,
        isStateChange  : false
    ]
}

private Map<String, ?> getButtonEvent(Map<String, ?> descriptionMap) {
    String buttonData = firstDataByte(descriptionMap)
    String action = BUTTON_EVENTS[buttonData]
    Integer buttonNumber = BUTTON_NUMBERS[safeHex(descriptionMap.command)]

    if (!action || !buttonNumber) {
        return null
    }

    String descriptionText = "${device.displayName} button ${buttonNumber} is ${action}"
    logEvent(descriptionText)

    return [
        name           : action,
        value          : buttonNumber,
        descriptionText: descriptionText,
        isStateChange  : true
    ]
}

private void handleRingRotation(Map<String, ?> descriptionMap) {
    String directionKey = firstDataByte(descriptionMap)
    Integer buttonNumber = RING_BUTTONS[directionKey]
    Integer clickCount = parseUnsignedByte(safeHex(descriptionMap.value))

    if (!buttonNumber || !clickCount || clickCount <= 0) {
        logDebug("ignoring ring rotation with direction ${directionKey} and clickCount ${clickCount}")
        return
    }

    Long nowMs = now() as Long
    Long cooldownUntil = (state.rotationCooldownUntil ?: 0L) as Long
    if (cooldownUntil > nowMs) {
        logDebug("ignoring ring rotation during cooldown until ${cooldownUntil}")
        return
    }

    Integer activeButton = (state.rotationButton ?: 0) as Integer
    Integer currentClicks = (state.rotationClickCount ?: 0) as Integer
    Boolean active = (state.rotationActive ?: false) as Boolean

    if (!active || activeButton == 0) {
        state.rotationActive = true
        state.rotationButton = buttonNumber
        state.rotationClickCount = clickCount
        runInMillis(getRotationWindow(), 'emitRingRotationEvent', [overwrite: true])
        logDebug("started rotation window for button ${buttonNumber} with ${clickCount} clicks")
        return
    }

    if (activeButton == buttonNumber) {
        state.rotationClickCount = currentClicks + clickCount
        runInMillis(getRotationWindow(), 'emitRingRotationEvent', [overwrite: true])
        logDebug("extended rotation window for button ${buttonNumber}; total clicks ${state.rotationClickCount}")
        return
    }

    emitRingRotationEvent()

    state.rotationActive = true
    state.rotationButton = buttonNumber
    state.rotationClickCount = clickCount
    runInMillis(getRotationWindow(), 'emitRingRotationEvent', [overwrite: true])
    logDebug("started new rotation window after direction change for button ${buttonNumber} with ${clickCount} clicks")
}

//
//    MESSAGE HELPERS
//

private Boolean isBatteryReport(String cluster, String attrId, String command) {
    return (cluster == '0001' && attrId == '0021') || (cluster == '0001' && command == '0A')
}

private Boolean isButtonCommand(String cluster, String command, String data) {
    return cluster == '0006' && BUTTON_NUMBERS.containsKey(command) && BUTTON_EVENTS.containsKey(firstDataByte(data))
}

private Boolean isRingCommand(String cluster, String command, String data) {
    return cluster == '0008' && command == 'FD' && RING_BUTTONS.containsKey(firstDataByte(data))
}

private String safeHex(value) {
    if (value == null) {
        return null
    }
    return value.toString().trim().toUpperCase()
}

private String firstDataByte(Map<String, ?> descriptionMap) {
    return firstDataByte(safeHex(descriptionMap.data))
}

private String firstDataByte(String data) {
    if (!data) {
        return null
    }

    String normalized = data.replace(' ', '')
    if (normalized.length() < 2) {
        return null
    }

    return normalized.substring(0, 2).toUpperCase()
}

private Integer parseUnsignedByte(String hex) {
    if (!hex) {
        return null
    }

    try {
        return Integer.parseInt(hex, 16)
    }
    catch (Exception ignored) {
        return null
    }
}

//
//    COMMAND HELPERS
//

private void sendButtonEvent(String action, BigDecimal button) {
    if (button == null) {
        return
    }

    sendButtonEvent(action, button.intValue())
}

private void sendButtonEvent(String action, Integer button) {
    if (button == null || button < 1 || button > 7) {
        return
    }

    String descriptionText = "${device.displayName} button ${button} is ${action}"
    logEvent(descriptionText)

    sendEvent(
        name: action,
        value: button,
        descriptionText: descriptionText,
        isStateChange: true
    )
}

//
//    ROTATION EVENT HELPERS
//

void emitRingRotationEvent() {
    Integer clickCount = (state.rotationClickCount ?: 0) as Integer
    Integer buttonNumber = (state.rotationButton ?: 0) as Integer

    resetRotationState(false)

    if (buttonNumber == 0 || clickCount <= 0) {
        logDebug('no rotation event emitted because no direction or clicks were recorded')
        return
    }

    Integer signedClickCount = (buttonNumber == 7) ? -clickCount : clickCount
    String descriptionText = "${device.displayName} rotation click count is ${signedClickCount}"

    sendEvent(
        name: 'rotationClickCount',
        value: signedClickCount,
        descriptionText: descriptionText,
        display: false,
        isStateChange: false
    )

    sendButtonEvent('pushed', buttonNumber)

    Integer cooldownTime = getRotationCooldown()
    state.rotationCooldownUntil = (cooldownTime > 0) ? ((now() as Long) + cooldownTime) : 0L

    if (cooldownTime > 0) {
        logDebug("starting rotation cooldown of ${cooldownTime}ms")
    }
}

private void resetRotationState(Boolean clearCooldown) {
    state.rotationActive = false
    state.rotationButton = 0
    state.rotationClickCount = 0

    if (clearCooldown) {
        state.rotationCooldownUntil = 0L
    }
    else if (state.rotationCooldownUntil == null) {
        state.rotationCooldownUntil = 0L
    }
}

//
//    CONFIGURATION HELPERS
//

private void ensureDriverVersionState(Boolean logChanges) {
    String previousVersion = state.driverVersion
    String currentVersion = getVersion()

    if (previousVersion != currentVersion) {
        state.driverVersion = currentVersion

        if (logChanges) {
            if (!previousVersion) {
                logDescriptionText("Driver installed (v${currentVersion})")
            }
            else {
                logDescriptionText("Driver upgraded from v${previousVersion} to v${currentVersion}")
            }
        }
    }
    else if (!state.driverVersion) {
        state.driverVersion = currentVersion
    }
}

private Integer getBatteryReport() {
    try {
        return (settings?.batteryReporting ?: DEFAULT_BATTERYREPORT) as Integer
    }
    catch (Exception ignored) {
        return DEFAULT_BATTERYREPORT as Integer
    }
}

private Integer getRotationCooldown() {
    try {
        return (settings?.rotationCooldown ?: DEFAULT_ROTATIONCOOLDOWN) as Integer
    }
    catch (Exception ignored) {
        return DEFAULT_ROTATIONCOOLDOWN as Integer
    }
}

private Integer getRotationWindow() {
    try {
        return (settings?.rotationWindow ?: DEFAULT_ROTATIONWINDOW) as Integer
    }
    catch (Exception ignored) {
        return DEFAULT_ROTATIONWINDOW as Integer
    }
}

private String getHubVersion() {
    return location?.hubs?.first()?.firmwareVersionString ?: '8'
}

private void initializeAttributes() {
    if ((device.currentValue('numberOfButtons') as Integer) != 7) {
        sendEvent(name: 'numberOfButtons', value: 7, display: false, isStateChange: false)
    }

    if (device.currentValue('rotationClickCount') == null) {
        sendEvent(name: 'rotationClickCount', value: 0, display: false, isStateChange: false)
    }
}

private String intTo16bitUnsignedHex(Integer value, Boolean reverse = true) {
    String hexStr = zigbee.convertToHexString(value.toInteger(), 4)
    if (reverse) {
        return hexStr.substring(2, 4) + hexStr.substring(0, 2)
    }
    return hexStr
}

private boolean isZigbee30() {
    String model = getHubVersion()
    String revision = model.split('-').last()
    revision = revision.contains('Pro') ? '9' : revision
    return (Integer.parseInt(revision) >= 8)
}

private void updateBooleanSettingIfChanged(String name, Boolean newValue) {
    Boolean currentValue = normalizeBoolean(settings?."${name}", !newValue)
    if (currentValue != newValue) {
        device.updateSetting(name, newValue)
    }
}

private void updateDriverVersionState() {
    ensureDriverVersionState(true)
}

//
//    LOGGING CONFIGURATION
//

private Integer debugAutoDisable() {
    return LOGSOFF.intdiv(60)
}

private Boolean debugLoggingEnabled() {
    return normalizeBoolean(settings?.debugEnable, false)
}

private Boolean descriptionTextLoggingEnabled() {
    return normalizeBoolean(settings?.txtEnable, true)
}

private Boolean normalizeBoolean(value, Boolean defaultValue) {
    if (value == null) {
        return defaultValue
    }
    if (value instanceof Boolean) {
        return value
    }

    String s = value.toString().trim().toLowerCase()
    if (s == 'true') {
        return true
    }
    if (s == 'false') {
        return false
    }

    return defaultValue
}

//
//    LOGGING SCHEDULER
//

void logsOff() {
    if (!debugLoggingEnabled()) {
        return
    }

    updateBooleanSettingIfChanged('debugEnable', false)
    log.warn "${device.displayName}: Debug logging disabled automatically after ${debugAutoDisable()} minutes"
}

private void scheduleDebugAutoDisableIfNeeded() {
    unschedule('logsOff')

    if (debugLoggingEnabled()) {
        runIn(LOGSOFF, 'logsOff', [overwrite: true])
        logDebug("debug logging will automatically turn off in ${debugAutoDisable()} minutes")
    }
}

//
//    LOGGING HELPERS
//

private void logDebug(String msg) {
    if (debugLoggingEnabled()) {
        log.debug(logMsg(msg))
    }
}

private void logDescriptionText(String msg) {
    if (descriptionTextLoggingEnabled()) {
        log.info "${device.displayName}: ${msg}"
    }
}

private void logError(String msg) {
    log.error(logMsg(msg))
}

private void logEvent(String msg) {
    if (descriptionTextLoggingEnabled()) {
        log.info(logMsg(msg))
    }
}

private String logMsg(String msg) {
    return "${DRIVER_NAME} (v${getVersion()}): ${msg}"
}

private void logTrace(String msg) {
    if (debugLoggingEnabled()) {
        log.trace(logMsg(msg))
    }
}

private void logWarn(String msg) {
    log.warn(logMsg(msg))
}