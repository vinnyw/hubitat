/**
 *  --------------------------------------------------------------------------------------------------------------
 *  Virtual Presence Sensor
 *  --------------------------------------------------------------------------------------------------------------
 *
 *  Author      : vinny wadding
 *  Namespace   : vinnyw
 *  Version     : 1.2.0
 *  Date        : 2026-03-11
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

@Field static final String DRIVER_VERSION = '1.2.0'
@Field static final Integer DEBUG_AUTO_DISABLE_SECONDS = 1800

metadata {
    definition(
        name: 'Virtual Presence Sensor',
        namespace: 'vinnyw',
        author: 'Vinny Wadding',
        importUrl: 'https://raw.githubusercontent.com/vinnyw/hubitat/master/VirtualPresenceSensor/Drivers/VirtualPresenceSensor.groovy'
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

        input name: 'logLevel', type: 'enum',
              title: 'Logging Level',
              options: ['Off', 'Error', 'Warn', 'Info', 'Debug', 'Trace'],
              defaultValue: 'Off'
    }
}

def installed() { configure() }
def updated() { configure() }

def configure() {
    List allowed = ['OFF', 'ERROR', 'WARN', 'INFO', 'DEBUG', 'TRACE']
    String raw = logLevel
    String normalized = (raw ?: 'OFF').toUpperCase()

    if (!allowed.contains(normalized)) {
        device.updateSetting('logLevel', [value: 'Off', type: 'enum'])
        log.warn "${device.displayName}: Invalid logLevel '${raw}' detected. Auto-corrected to Off."
    }

    String previousVersion = state.driverVersion
    state.driverVersion = DRIVER_VERSION

    if (!previousVersion) {
        log.info "${device.displayName}: Driver installed (v${DRIVER_VERSION})"
    } else if (previousVersion != DRIVER_VERSION) {
        log.info "${device.displayName}: Driver upgraded from v${previousVersion} to v${DRIVER_VERSION}"
    }

    if (!device.currentValue('presence')) {
        sendEvent(name: 'presence', value: 'not present', isStateChange: false, type: 'digital')
    }

    if (!device.currentValue('switch')) {
        sendEvent(name: 'switch', value: 'off', displayed: false, isStateChange: false, type: 'digital')
    }

    if (!device.currentValue('lastActivity')) {
        sendEvent(name: 'lastActivity', value: now(), displayed: false, type: 'digital')
    }
}

def refresh() {
    sendEvent(name: 'presence', value: device.currentValue('presence'), isStateChange: false, type: 'digital')
    sendEvent(name: 'switch', value: device.currentValue('switch'), displayed: false, isStateChange: false, type: 'digital')
    sendEvent(name: 'lastActivity', value: device.currentValue('lastActivity'), displayed: false, isStateChange: false, type: 'digital')

    if (txtEnable) {
        log.info "${device.displayName} was refreshed"
    }
}

def present() { changePresenceState('present', 'on') }
def notPresent() { changePresenceState('not present', 'off') }

def on()  { present() }
def off() { notPresent() }

def togglePresence() {
    if (device.currentValue('presence') == 'present') {
        notPresent()
    } else {
        present()
    }
}

private void changePresenceState(String pres, String sw) {
    if (device.currentValue('presence') == pres) return

    sendEvent(name: 'presence', value: pres, type: 'digital')
    sendEvent(name: 'switch', value: sw, displayed: false, type: 'digital')
    sendEvent(name: 'lastActivity', value: now(), displayed: false, type: 'digital')

    if (txtEnable) {
        log.info "${device.displayName} presence is ${pres}"
    }
}
