/**
 *  --------------------------------------------------------------------------------------------------------------
 *  Virtual Room Occupancy Sensor (Pure Custom Hidden Switch)
 *  --------------------------------------------------------------------------------------------------------------
 *
 *  Author      : Custom
 *  Namespace   : hubitat
 *  Version     : 3.7.0
 *  Date        : 2026-03-02
 *
 *  Description :
 *      Virtual occupancy device
 *
 *      Attributes:
 *          occupancy     (enum)    : occupied / unoccupied
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

@Field static final String DRIVER_VERSION = "3.7.0"
@Field static final Integer DEBUG_AUTO_DISABLE_SECONDS = 1800

metadata {
    definition(
        name: "Virtual Occupancy Sensor",
        namespace: "vinnyw",
        author: "vinny wadding",
        importUrl: "https://raw.githubusercontent.com/vinnyw/hubitat/refs/heads/master/VirtualOccupancySensor/drivers/VirtualOccupancySensor.groovy"
    ) {
        capability "Sensor"
        capability "Actuator"
        capability "Configuration"
        capability "Refresh"

        attribute "occupancy", "enum", ["occupied", "unoccupied"]
        attribute "switch", "enum", ["on", "off"]
        attribute "lastActivity", "number"

        command "occupied"
        command "unoccupied"
        command "toggleOccupancy"
    }

    preferences {
        input name: "txtEnable", type: "bool",
              title: "Enable descriptionText logging",
              defaultValue: true

        input name: "logLevel", type: "enum",
              title: "Logging Level",
              options: ["Off","Error","Warn","Info","Debug","Trace"],
              defaultValue: "Off"
    }
}

def installed() { configure() }
def updated() { configure() }

def configure() {

    List allowed = ["OFF","ERROR","WARN","INFO","DEBUG","TRACE"]
    String raw = logLevel
    String normalized = (raw ?: "OFF").toUpperCase()

    if (!allowed.contains(normalized)) {
        device.updateSetting("logLevel", [value: "Off", type: "enum"])
        log.warn "${device.displayName}: Invalid logLevel '${raw}' detected. Auto-corrected to Off."
    }

    String previousVersion = state.driverVersion
    state.driverVersion = DRIVER_VERSION

    if (!previousVersion) {
        log.info "${device.displayName}: Driver installed (v${DRIVER_VERSION})"
    } else if (previousVersion != DRIVER_VERSION) {
        log.info "${device.displayName}: Driver upgraded from v${previousVersion} to v${DRIVER_VERSION}"
    }

    if (!device.currentValue("occupancy")) {
        sendEvent(name: "occupancy", value: "unoccupied", isStateChange: false)
    }

    if (!device.currentValue("switch")) {
        sendEvent(name: "switch", value: "off", displayed: false, isStateChange: false)
    }

    if (!device.currentValue("lastActivity")) {
        sendEvent(name: "lastActivity", value: now(), displayed: false)
    }
}

def refresh() {
    sendEvent(name: "occupancy", value: device.currentValue("occupancy"), isStateChange: false)
    sendEvent(name: "switch", value: device.currentValue("switch"), displayed: false, isStateChange: false)
    sendEvent(name: "lastActivity", value: device.currentValue("lastActivity"), displayed: false, isStateChange: false)

    if (txtEnable) {
        log.info "${device.displayName} was refreshed"
    }
}

def occupied() { changeOccupancyState("occupied", "on") }
def unoccupied() { changeOccupancyState("unoccupied", "off") }

def on()  { occupied() }
def off() { unoccupied() }

def toggleOccupancy() {
    if (device.currentValue("occupancy") == "occupied") {
        unoccupied()
    } else {
        occupied()
    }
}

private void changeOccupancyState(String occ, String sw) {

    if (device.currentValue("occupancy") == occ) return

    sendEvent(name: "occupancy", value: occ)
    sendEvent(name: "switch", value: sw, displayed: false)
    sendEvent(name: "lastActivity", value: now(), displayed: false)

    if (txtEnable) {
        log.info "${device.displayName} occupancy is ${occ}"
    }
}
