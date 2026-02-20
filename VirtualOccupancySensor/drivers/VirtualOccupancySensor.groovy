/**
 *  --------------------------------------------------------------------------------------------------------------
 *  Virtual Occupancy Sensor
 *  --------------------------------------------------------------------------------------------------------------
 *
 *  Author      : vinny wadding
 *  Namespace   : vinnyw
 *  Version     : 3.6.9
 *  Date        : 2026-02-20
 *
 *  Description :
 *      Virtual occupancy device using custom attribute model.
 *
 *      Attributes:
 *          occupancy     (string)  : occupied / unoccupied
 *          switch        (string)  : on / Off  (attribute only)
 *          lastActivity  (number)  : epoch time (Long)
 *
 *      Capabilities:
 *          Sensor
 *          Configuration
 *          Refresh
 *
 *  --------------------------------------------------------------------------------------------------------------
 */

import groovy.transform.Field

/* ===================== CONSTANTS ===================== */

@Field static final String DRIVER_VERSION = "3.6.9"
@Field static final Integer Debug_AUTO_DISABLE_SECONDS = 1800

/* ===================== METADATA ===================== */

metadata {
    definition(
        name: "Virtual Occupancy Sensor",
        namespace: "vinnyw",
        author: "vinny wadding",
        importUrl: "https://raw.githubusercontent.com/vinnyw/hubitat/refs/heads/master/VirtualOccupancySensor/drivers/VirtualOccupancySensor.groovy"
    ) {
        capability "Sensor"
        capability "Configuration"
        capability "Refresh"

        attribute "occupancy", "string"
        attribute "switch", "string"
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

/* ===================== LIFECYCLE METHODS ===================== */

def installed() { configure() }
def updated() { configure() }

def configure() {

    // Validate logLevel enum (one-time correction if invalid)
    List __allowedLevels = ["OFF","ERROR","WARN","INFO","DEBUG","TRACE"]
    String __rawLevel = logLevel
    String __normalized = (__rawLevel ?: "OFF").toUpperCase()

    if (!__allowedLevels.contains(__normalized)) {
        device.updateSetting("logLevel", [value: "Off", type: "enum"])
        logWarn("Invalid logLevel '${__rawLevel}' detected. Auto-corrected to Off.", true)
    }


    String previousVersion = state.driverVersion
    state.driverVersion = DRIVER_VERSION

    if (!previousVersion) {
        logInfo("Driver installed (v${DRIVER_VERSION})", true)
    } else if (previousVersion != DRIVER_VERSION) {
        logInfo("Driver upgraded from v${previousVersion} to v${DRIVER_VERSION}", true)
    }

    scheduleDebugAutoDisable()

    if (!device.currentValue("occupancy")) {
        sendEvent(name: "occupancy", value: "unoccupied", isStateChange: false)
    }

    if (!device.currentValue("switch")) {
        sendEvent(name: "switch", value: "Off", displayed: false, isStateChange: false)
    }

    if (!device.currentValue("lastActivity")) {
        updateLastActivity()
    }
}

/* ===================== CAPABILITY COMMANDS ===================== */

def refresh() {

    sendEvent(name: "occupancy",
              value: device.currentValue("occupancy"),
              isStateChange: false)

    sendEvent(name: "switch",
              value: device.currentValue("switch"),
              displayed: false,
              isStateChange: false)

    sendEvent(name: "lastActivity",
              value: device.currentValue("lastActivity"),
              displayed: false,
              isStateChange: false)

    if (txtEnable) {
        String descriptionText = "${device.displayName} was refreshed"
        log.info descriptionText
    }
}

/* ===================== OCCUPANCY COMMANDS ===================== */

def occupied() {
    changeOccupancyState("occupied", "on")
}

def unoccupied() {
    changeOccupancyState("unoccupied", "off")
}

def setOccupied() { occupied() }
def setUnoccupied() { unoccupied() }

def toggleOccupancy() {
    if (device.currentValue("occupancy") == "occupied") {
        unoccupied()
    } else {
        occupied()
    }
}

/* ===================== STATE MANAGEMENT ===================== */

private void changeOccupancyState(String occupancyValue, String switchValue) {

    if (device.currentValue("occupancy") == occupancyValue) {
        logDebug("Duplicate state prevented (${occupancyValue})")
        return
    }

    sendEvent(name: "occupancy", value: occupancyValue)
    sendEvent(name: "switch", value: switchValue, displayed: false)

    updateLastActivity()

    if (txtEnable) {
        String descriptionText = "${device.displayName} occupancy is ${occupancyValue}"
        log.info descriptionText
    }

    logDebug("Occupancy set to ${occupancyValue}")
}

private void updateLastActivity() {
    sendEvent(name: "lastActivity", value: now(), displayed: false)
}

/* ===================== Debug AUTO-DISABLE ===================== */

private void scheduleDebugAutoDisable() {

    unschedule("disableDebugLogging")

    if (logLevel in ["Debug","Trace"]) {
        runIn(Debug_AUTO_DISABLE_SECONDS, "disableDebugLogging")
        logWarn("Debug/Trace logging will automatically disable in 30 minutes")
    }
}

def disableDebugLogging() {
    device.updateSetting("logLevel", [value: "Off", type: "enum"])
    logWarn("Debug/Trace logging automatically disabled")
}

/* ===================== LOGGING WRAPPERS ===================== */

private String normalizedLogLevel() {
    List allowed = ["OFF","ERROR","WARN","INFO","DEBUG","TRACE"]
    String current = (logLevel ?: "OFF").toUpperCase()
    return allowed.contains(current) ? current : "OFF"
}

private Boolean isLevelEnabled(String level) {
    List levels = ["OFF","ERROR","WARN","INFO","DEBUG","TRACE"]
    String current = normalizedLogLevel()
    String target = level.toUpperCase()
    // If target is invalid, treat as most restrictive (OFF)
    if (!levels.contains(target)) target = "OFF"
    return levels.indexOf(current) >= levels.indexOf(target)
}


private void logTrace(String message, Boolean force=false) {
    if (force || isLevelEnabled("Trace")) {
        log.trace "${device.displayName}: ${message}"
    }
}

private void logDebug(String message, Boolean force=false) {
    if (force || isLevelEnabled("Debug")) {
        log.debug "${device.displayName}: ${message}"
    }
}

private void logInfo(String message, Boolean force=false) {
    if (force || isLevelEnabled("Info")) {
        log.info "${device.displayName}: ${message}"
    }
}

private void logWarn(String message, Boolean force=false) {
    if (force || isLevelEnabled("Warn")) {
        log.warn "${device.displayName}: ${message}"
    }
}

private void logError(String message, Boolean force=false) {
    if (force || isLevelEnabled("Error")) {
        log.error "${device.displayName}: ${message}"
    }
}
