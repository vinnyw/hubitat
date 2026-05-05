/**
 *  --------------------------------------------------------------------------------------------------------------
 *  Candeo C-ZB-SR5BR Scene Remote
 *  --------------------------------------------------------------------------------------------------------------
 *
 *  Author      : Vinny Wadding
 *  Namespace   : vinnyw
 *  Version     : 1.1.13
 *  Date        : 2026-05-04
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

private @Field final String DRIVER_VERSION = '1.1.15'
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
    catch (Exception ex) {
        logError("zigbee.parseDescriptionAsMap failed: ${ex.message}")
        return null
    }

    if (!descriptionMap) {
        logWarn('no descriptionMap available')
        return null
    }

    if (descriptionMap == [:]) {
        logWarn('descriptionMap is empty')
        return null
    }

    List<Map<String, ?>> events = processEvents(descriptionMap, [])
    if (events && !events.isEmpty()) {
        logDebug("parse returning ${events.size()} event(s)")
        return events
    }

    logDebug("unhandled descriptionMap: ${descriptionMap}")
    return null
}

private List<Map<String, ?>> processEvents(Map descriptionMap, List<Map<String, ?>> events) {
    if (descriptionMap.profileId == '0000') {
        logTrace('skipping ZDP profile message')
        return events
    }

    if (descriptionMap.profileId && descriptionMap.profileId != '0104') {
        logDebug("skipping unsupported profileId ${descriptionMap.profileId}")
        return events
    }

    if (descriptionMap.cluster == '0001' || descriptionMap.clusterId == '0001' || descriptionMap.clusterInt == 1) {
        processPowerConfigurationCluster(descriptionMap, events)
    }
    else if (descriptionMap.cluster == 'FF03' || descriptionMap.clusterId == 'FF03' || descriptionMap.clusterInt == 65283) {
        processManufacturerSpecificCluster(descriptionMap, events)
    }
    else {
        logDebug("skipped cluster ${descriptionMap.cluster ?: descriptionMap.clusterId ?: descriptionMap.clusterInt ?: 'unknown'}")
    }

    if (descriptionMap.additionalAttrs instanceof List) {
        descriptionMap.additionalAttrs.each { Map attribute ->
            attribute.clusterInt = descriptionMap.clusterInt
            attribute.cluster = descriptionMap.cluster
            attribute.clusterId = descriptionMap.clusterId
            attribute.command = descriptionMap.command
            processEvents(attribute, events)
        }
    }

    return events
}

private void processManufacturerSpecificCluster(Map descriptionMap, List<Map<String, ?>> events) {
    if (descriptionMap.command != '01') {
        logDebug("manufacturer specific command skipped: ${descriptionMap.command}")
        return
    }

    List<String> commandData = descriptionMap.data
    if (!(commandData instanceof List) || commandData.size() < 4) {
        logWarn("manufacturer specific payload is incomplete: ${commandData}")
        return
    }

    if (commandData[0] == '01') {
        Integer buttonNumber = BUTTON_NUMBERS[commandData[2]] as Integer
        String buttonEvent = BUTTON_EVENTS[commandData[3]] as String

        if (buttonNumber && buttonEvent) {
            events.add(createButtonEvent(buttonEvent, buttonNumber))
        }
        else {
            logDebug("unknown button payload: ${commandData}")
        }
        return
    }

    if (commandData[0] == '03') {
        processRingRotation(commandData)
        return
    }

    logDebug("unknown manufacturer payload type: ${commandData[0]}")
}

private void processPowerConfigurationCluster(Map descriptionMap, List<Map<String, ?>> events) {
    switch (descriptionMap.command) {
        case '0A':
        case '01':
            if (descriptionMap.attrId == '0021' || descriptionMap.attrInt == 33) {
                if (!descriptionMap.value) {
                    logWarn('battery report missing value')
                    return
                }

                Integer batteryValue = zigbee.convertHexToInt(descriptionMap.value).intdiv(2)
                String descriptionText = "${device.displayName} battery percent is ${batteryValue}%"
                logEvent(descriptionText)

                events.add(createEvent(
                    name: 'battery',
                    value: batteryValue,
                    unit: '%',
                    descriptionText: descriptionText
                ))
            }
            break
        default:
            logDebug("power configuration cluster command skipped: ${descriptionMap.command}")
            break
    }
}

private void processRingRotation(List<String> commandData) {
    Long cooldownUntil = (state.rotationCooldownUntil ?: 0L) as Long
    Long nowMs = now() as Long

    if (cooldownUntil > nowMs) {
        logDebug("ring rotation ignored during cooldown for ${cooldownUntil - nowMs}ms")
        return
    }

    String commandEvent = commandData[2]
    if (commandEvent != '01') {
        logDebug('ring release or stop event ignored for sampled rotation mode')
        return
    }

    Integer buttonNumber = RING_BUTTONS[commandData[1]] as Integer
    if (!buttonNumber) {
        logDebug("unknown ring direction payload: ${commandData}")
        return
    }

    Integer clickCount = 1
    try {
        clickCount = zigbee.convertHexToInt(commandData[3])
        if (clickCount < 1) {
            clickCount = 1
        }
    }
    catch (Exception ex) {
        logWarn("invalid ring click payload ${commandData[3]}: ${ex.message}")
        clickCount = 1
    }

    if (!(state.rotationActive)) {
        state.rotationActive = true
        state.rotationButton = buttonNumber
        state.rotationClickCount = 0

        Integer sampleTime = getRotationWindow()
        logDebug("starting rotation sample window of ${sampleTime}ms")
        runInMillis(sampleTime, 'emitRingRotationEvent', [overwrite: true])
    }
    else if ((state.rotationButton as Integer) != buttonNumber) {
        logDebug("ring direction changed from button ${state.rotationButton} to ${buttonNumber} inside sample window")
        state.rotationButton = buttonNumber
    }

    state.rotationClickCount = ((state.rotationClickCount ?: 0) as Integer) + clickCount
    logDebug("rotationClickCount is now ${state.rotationClickCount}")
}

//
//    BUTTON EVENT HELPERS
//

private Map createButtonEvent(String action, Integer button) {
    String descriptionText = "${device.displayName} button ${button} is ${action}"
    logEvent(descriptionText)

    return createEvent(
        name: action,
        value: button,
        descriptionText: descriptionText,
        isStateChange: true
    )
}

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
        device.updateSetting(name, [value: newValue, type: 'bool'])
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
