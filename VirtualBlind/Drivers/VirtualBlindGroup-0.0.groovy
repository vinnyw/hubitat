/**
 *  --------------------------------------------------------------------------------------------------------------
 *  Virtual Blind Group
 *  --------------------------------------------------------------------------------------------------------------
 *
 *  Author      : Vinny Wadding
 *  Namespace   : vinnyw
 *  Version     : Parent-managed (via child app -> parent app)
 *  Date        : 2026-09-22
 *
 *  Description :
 *      Thin presentation and structural parent driver for a virtual blind group.
 *
 *      Attributes:
 *          position    (number) : aggregate blind position percentage
 *          windowShade (enum)   : standard Hubitat WindowShade state
 *          groupState  (enum)   : detailed aggregate blind group state
 *
 *      Capabilities:
 *          Actuator
 *          Configuration
 *          WindowShade
 *
 *      Note:
 *          This driver provides the WindowShade presentation for the group and acts as the
 *          structural parent for individual virtual blind devices. Suite application logic
 *          remains responsible for the governing blind and group behavior.
 *
 *  --------------------------------------------------------------------------------------------------------------
 */

metadata {
    definition(
        name: 'VirtualBlindGroup-0.0',
        namespace: 'vinnyw',
        author: 'Vinny Wadding',
    ) {
        capability 'Actuator'
        capability 'Configuration'
        capability 'WindowShade'

        attribute 'groupState', 'enum', [
            'empty',
            'allOpen',
            'allClosed',
            'allOpening',
            'allClosing',
            'someOpening',
            'someClosing',
            'someOpen',
            'someClosed',
            'someOpenSomeClosed',
            'somePartiallyOpen',
            'mixedMoving'
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

private String formatDisplayVersion(Object versionValue) {
    String version = versionValue?.toString()?.trim()
    return version ? "v${version}" : 'unknown'
}

def getVersion() {
    return parent?.getVersion() ?: 'unknown'
}

def getShortVersion() {
    return parent?.getShortVersion() ?: 'unknown'
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
    Map cfg = parent?.getChildDriverLoggingConfig()
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
    parent?.updateLoggingFromDriver(settings?.txtEnable, settings?.debugEnable)
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

Map getBlindDriverLoggingConfig(cd) {
    String blindAppId = blindIdFor(cd)
    if (!blindAppId) return null

    try {
        return parent?.getBlindDriverLoggingConfig(blindAppId)
    } catch (Exception e) {
        logWarn("Unable to read logging settings for Blind App ${blindAppId}: ${e.message}")
        return null
    }
}

void updateBlindLoggingFromDriver(cd, txtEnableValue, debugEnableValue) {
    String blindAppId = blindIdFor(cd)
    if (!blindAppId) return

    try {
        parent?.updateBlindLoggingFromDriver(
            blindAppId,
            txtEnableValue,
            debugEnableValue
        )
    } catch (Exception e) {
        logWarn("Unable to update logging for Blind App ${blindAppId}: ${e.message}")
    }
}

void applyBlindPresentationLogging(
    String blindAppId,
    txtEnableValue,
    debugEnableValue,
    debugAutoDisableSecondsValue
) {
    String id = blindAppId?.toString()
    if (!id) return

    def child = getChildDevices().find {
        it.getDataValue('blindAppId') == id
    }

    if (!child) return

    child.applyPresentationConfiguration(
        txtEnableValue,
        debugEnableValue,
        debugAutoDisableSecondsValue
    )
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
    return normalizeDebugAutoDisableSeconds(parent?.getDebugAutoDisableSeconds())
}

private Integer debugAutoDisableMinutes() {
    Integer seconds = debugAutoDisableSeconds()
    return seconds ? (int) (seconds / 60) : 0
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

/* Group device commands -> Group App */
void open() {
    logDebug('Open command')
    parent.commandGroupOpen()
}

void close() {
    logDebug('Close command')
    parent.commandGroupClose()
}

void setPosition(BigDecimal position) {
    getChildDevices().each { child ->
        componentSetPosition(child, position)
    }
}

void startPositionChange(String direction) {
    String normalized = direction?.toLowerCase()

    if (normalized == 'open') {
        parent.commandGroupOpen()
    } else if (normalized == 'close') {
        parent.commandGroupClose()
    } else {
        logWarn("Unsupported shade direction '${direction}' for group device.")
    }
}

void stopPositionChange() {
    parent.commandGroupStop()
}

void stop() {
    logDebug('Stop command')
    parent.commandGroupStop()
}

/* Blind child commands -> Group App -> owning Blind App */
void componentOpen(cd) {
    routeBlindCommand(cd, 'open')
}

void componentClose(cd) {
    routeBlindCommand(cd, 'close')
}

void componentSetPosition(cd, BigDecimal position) {
    String blindAppId = blindIdFor(cd)
    if (!blindAppId) return

    parent.commandBlindSetPosition(blindAppId, position)
}

void componentStartPositionChange(cd, String direction) {
    String blindAppId = blindAppIdFromDevice(cd)
    if (!blindAppId) {
        logWarn("Unable to resolve blind app for child device ${cd?.displayName ?: cd?.deviceNetworkId}.")
        return
    }

    parent.commandBlindStartPositionChange(blindAppId, direction)
}

void componentStopPositionChange(cd) {
    routeBlindCommand(cd, 'stop')
}

private void routeBlindCommand(cd, String commandName) {
    String blindAppId = blindIdFor(cd)
    if (!blindAppId) return

    switch (commandName) {
        case 'open':
            parent.commandBlindOpen(blindAppId)
            break

        case 'close':
            parent.commandBlindClose(blindAppId)
            break

        case 'stop':
            parent.commandBlindStopPositionChange(blindAppId)
            break
    }
}

private String blindIdFor(cd) {
    if (!cd) {
        logWarn('Ignoring command because no child device was supplied.')
        return null
    }

    /*
     * Validate membership using Hubitat's documented child-device API.
     * Do not depend on an undocumented parentDeviceId property.
     */
    def ownedChild = getChildDevice(cd.deviceNetworkId)

    if (!ownedChild) {
        logWarn("Ignoring command from '${cd.displayName}' because it is not a child of this group.")
        return null
    }

    String blindAppId = ownedChild.getDataValue('blindAppId')

    if (!blindAppId) {
        logWarn("Ignoring command from '${ownedChild.displayName}' because blindAppId is missing.")
        return null
    }

    return blindAppId
}

/* Structural child-device lifecycle only */
void ensureBlindPresentation(String blindAppId, String blindLabel) {
    String id = blindAppId?.toString()
    if (!id) return

    String desiredLabel = (blindLabel ?: 'Virtual Blind').toString().trim()
    String dni = "${device.deviceNetworkId}-vbd-${id}"
    def child = getChildDevice(dni)

    if (!child) {
        String driverName = "VirtualBlindDevice-${getShortVersion()}"

        child = addChildDevice(
            'vinnyw',
            driverName,
            dni,
            [
                name: driverName,
                label: desiredLabel,
                isComponent: true
            ]
        )

        child.updateDataValue('blindAppId', id)
        logDebug("Created blind presentation device '${desiredLabel}' for Blind App ${id}")
    } else {
        synchronizeBlindPresentationLabel(child, desiredLabel)

        if (child.getDataValue('blindAppId') != id) {
            child.updateDataValue('blindAppId', id)
        }
    }

    child.configure()

    Map cfg = getBlindDriverLoggingConfig(child)
    if (cfg instanceof Map) {
        child.applyPresentationConfiguration(
            cfg.txtEnable,
            cfg.debugEnable,
            cfg.debugAutoDisableSeconds
        )
    }
}

private void synchronizeBlindPresentationLabel(child, String desiredLabel) {
    if (!child || !desiredLabel) return

    String currentLabel = child.label?.toString()
    if (currentLabel != desiredLabel) {
        logDebug("Updating blind presentation label from '${currentLabel}' to '${desiredLabel}'")
        child.setLabel(desiredLabel)
    }
}

void removeBlindPresentation(String blindAppId) {
    String id = blindAppId?.toString()
    if (!id) return

    def child = getChildDevices().find {
        it.getDataValue('blindAppId') == id
    }

    if (child) {
        deleteChildDevice(child.deviceNetworkId)
    }
}

void removeOrphanBlindPresentations(List<String> liveBlindAppIds) {
    Set<String> live = (liveBlindAppIds ?: []).collect {
        it.toString()
    } as Set<String>

    getChildDevices().findAll { child ->
        String blindAppId = child.getDataValue('blindAppId')
        blindAppId && !live.contains(blindAppId)
    }.each { orphan ->
        deleteChildDevice(orphan.deviceNetworkId)
    }
}

/* Event presentation only */
void presentGroup(List<Map> events) {
    events?.each { Map eventData ->
        if (!eventData?.name || !eventData.containsKey('value')) {
            return
        }

        if (eventData.name == 'windowShade') {
            String aggregateState = eventData.value?.toString()

            String groupState = displayGroupState(aggregateState)
            String previousGroupState = device.currentValue('groupState')?.toString()
            String standardState = standardWindowShadeState(aggregateState)

            if (previousGroupState != groupState) {
                sendEvent(name: 'groupState', value: groupState)
            }

            sendEvent(
                name: 'windowShade',
                value: standardState
            )

            if (descriptionTextLoggingEnabled() && previousGroupState != groupState) {
                log.info "${device.displayName} group state is ${groupState}"
            }
        } else {
            sendEvent(eventData)
        }
    }
}

private String displayGroupState(String aggregateState) {
    switch (aggregateState) {
        case 'empty':         return 'empty'
        case 'allOpen':       return 'allOpen'
        case 'allClosed':     return 'allClosed'
        case 'allOpening':    return 'allOpening'
        case 'allClosing':    return 'allClosing'
        case 'someOpening':   return 'someOpening'
        case 'someClosing':   return 'someClosing'
        case 'someOpen':      return 'someOpen'
        case 'someClosed':    return 'someClosed'
        case 'someOpenSomeClosed': return 'someOpenSomeClosed'
        case 'somePartiallyOpen':  return 'somePartiallyOpen'
        case 'mixedMoving':   return 'mixedMoving'
        default:              return aggregateState ?: 'empty'
    }
}

private String standardWindowShadeState(String aggregateState) {
    switch (aggregateState) {
        case 'allOpen':
            return 'open'

        case 'someOpen':
            return 'partially open'

        case 'allClosed':
            return 'closed'

        case 'allOpening':
        case 'someOpening':
            return 'opening'

        case 'allClosing':
        case 'someClosing':
            return 'closing'

        case 'empty':
        case 'mixedMoving':
        default:
            return 'unknown'
    }
}

void presentBlind(String blindAppId, List<Map> events) {
    String id = blindAppId?.toString()

    def child = getChildDevices().find {
        it.getDataValue('blindAppId') == id
    }

    if (!child) {
        logWarn("Presentation child for Blind App ${id} was not found.")
        return
    }

    child.parse(events)
}
