/**
 *  --------------------------------------------------------------------------------------------------------------
 *  Virtual Presence Sensor
 *  --------------------------------------------------------------------------------------------------------------
 *
 *  Author      : Vinny Wadding
 *  Namespace   : vinnyw
 *  Version     : 1.2.1
 *  Date        : 2026-04-15
 *
 *  Description :
 *      Virtual Presence Sensor
 *
 *      Attributes:
 *          presence      (enum)    : present / not present
 *          switch        (string)  : on / off  (attribute only)
 *          lastActivity  (number)  : epoch time (Long)
 *
 *      Capabilities:
 *          Sensor
 *          Actuator
 *          Configuration
 *          Refresh
 *
 *  --------------------------------------------------------------------------------------------------------------
 */

import groovy.transform.Field

@Field static final String DRIVER_VERSION = '1.2.1'
@Field static final Integer DEBUG_AUTO_DISABLE_SECONDS = 1800

metadata {
    definition(
        name: 'Virtual Presence Sensor',
        namespace: 'vinnyw',
        author: 'Vinny Wadding',
        importUrl: 'https://raw.githubusercontent.com/vinnyw/hubitat/master/VirtualPresence/Drivers/VirtualPresenceSensor.groovy'
    ) {
        capability 'Sensor'
        capability 'Actuator'
        capability 'Configuration'
        capability 'Refresh'
        capability 'PresenceSensor'

        attribute 'presence', 'enum', ['present', 'not present']
        attribute 'switch', 'enum', ['on', 'off']
        attribute 'lastActivity', 'number'

        command 'present'
        command 'notPresent'
        command 'togglePresence'
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
    return DRIVER_VERSION
}


//
//    UI / PREFERENCES
//

// Preferences are declared in metadata { preferences { ... } } above.


//
//    LIFECYCLE
//

def configure() {
    unschedule('logsOff')

    String previousVersion = state.driverVersion
    String currentVersion = getVersion()
    state.driverVersion = currentVersion

    if (!device.currentValue('presence')) {
        sendEvent(
            name: 'presence',
            value: 'not present',
            isStateChange: false,
            type: 'digital'
        )
    }

    if (!device.currentValue('switch')) {
        sendEvent(
            name: 'switch',
            value: 'off',
            displayed: false,
            isStateChange: false,
            type: 'digital'
        )
    }

    if (!device.currentValue('lastActivity')) {
        sendEvent(
            name: 'lastActivity',
            value: now(),
            displayed: false,
            isStateChange: false,
            type: 'digital'        
        )
    }

    if (!previousVersion) {
        logInfo("${device.displayName}: Driver installed (v${currentVersion})")
    }
    else if (previousVersion != currentVersion) {
        logInfo("${device.displayName}: Driver upgraded from v${previousVersion} to v${currentVersion}")
    }

    scheduleDebugAutoDisableIfNeeded()
}

def installed() {
    configure()
}

def updated() {
    unschedule()
    configure()
}


//
//    COMMANDS
//

def notPresent() {
    changePresenceState('not present', 'off')
}

def off() {
    notPresent()
}

def on() {
    present()
}

def present() {
    changePresenceState('present', 'on')
}

def refresh() {
    sendEvent(
        name: 'presence',
        value: device.currentValue('presence'),
        displayed: true,
        isStateChange: false,
        type: 'digital'
    )

    sendEvent(
        name: 'switch',
        value: device.currentValue('switch'),
         displayed: false,
        isStateChange: false,
        type: 'digital'
    )

    sendEvent(
        name: 'lastActivity',
        value: device.currentValue('lastActivity'),
        displayed: false,
        isStateChange: false,
        type: 'digital'
    )

    logInfo("${device.displayName} was refreshed")
    logDebug('refresh() emitted current attribute values')
}

def togglePresence() {
    if (device.currentValue('presence') == 'present') {
        notPresent()
    }
    else {
        present()
    }
}


//
//    LOGGING SCHEDULER
//

private Integer debugAutoDisableMinutes() {
    return (int) (DEBUG_AUTO_DISABLE_SECONDS / 60)
}

private String debugAutoDisableText() {
    if (DEBUG_AUTO_DISABLE_SECONDS < 60) {
        return "${DEBUG_AUTO_DISABLE_SECONDS} seconds"
    }

    return "${debugAutoDisableMinutes()} minutes"
}

def logsOff() {
    if (!debugLoggingEnabled()) return

    device.updateSetting('debugEnable', [value: false, type: 'bool'])
    log.warn "${device.displayName}: Debug logging automatically disabled after ${debugAutoDisableText()}"
}

private void scheduleDebugAutoDisableIfNeeded() {
    unschedule('logsOff')

    if (debugLoggingEnabled()) {
        runIn(DEBUG_AUTO_DISABLE_SECONDS, 'logsOff', [overwrite: true])
        log.debug "${device.displayName}: Debug logging will automatically turn off in ${debugAutoDisableText()}"
    }
}


//
//    LOGGING HELPERS
//

private Boolean asBool(value) {
    return value in [true, 'true', 'True', 'TRUE', 1, '1']
}

private Boolean debugLoggingEnabled() {
    return asBool(settings?.debugEnable)
}

private void logDebug(String msg) {
    if (debugLoggingEnabled()) {
        log.debug "${device.displayName}: ${msg}"
    }
}

private void logInfo(String msg) {
    if (asBool(settings?.txtEnable)) {
        log.info msg
    }
}


//
//    STATE HELPERS
//

private void changePresenceState(String pres, String sw) {
    if (device.currentValue('presence') == pres) {
        logDebug("presence already ${pres}; no event sent")
        return
    }

    sendEvent(
        name: 'presence',
        value: pres,
        displayed: true,
        isStateChange: true,
        type: 'digital'
    )

    sendEvent(
        name: 'switch',
        value: sw,
        displayed: false,
        isStateChange: false,
        type: 'digital'
    )

    sendEvent(
        name: 'lastActivity',
        value: now(),
        displayed: false,
        isStateChange: false,
        type: 'digital'
    )

    logInfo("${device.displayName} presence is ${pres}")
    logDebug("switch set to ${sw} and lastActivity updated")
}
