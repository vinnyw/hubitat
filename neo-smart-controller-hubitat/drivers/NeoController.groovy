
import groovy.transform.Field

@Field static final String DRIVER_NAME = 'Neo Controller'
@Field static final String DRIVER_NS   = 'vinny'
@Field static final String DRIVER_AUTHOR = 'Vinny'

metadata {
    definition(name: DRIVER_NAME, namespace: DRIVER_NS, author: DRIVER_AUTHOR) {
        capability "Initialize"
        capability "Configuration"

        command "discoverDevices"
    }

    preferences {
        input name: "controllerIP", type: "string", title: "Controller IP", required: true
        input name: "controllerPort", type: "number", title: "Port", defaultValue: 8839
        input name: "controllerPrefix", type: "string", title: "Controller Prefix (example 036)", required: true

        input name: "logLevel", type: "enum", title: "Log Level",
            options: ["0":"Off","1":"Error","2":"Warn","3":"Info","4":"Debug","5":"Trace"],
            defaultValue: "3"
    }
}

def initialize() {
    logInfo("Initialized")
}

def discoverDevices() {
    logInfo("Discovery invoked (example implementation placeholder)")
}

def sendBlindCommand(String code, String cmd) {
    String command = "${cmd} ${code}\n"
    try {
        interfaces.rawSocket.connect(settings.controllerIP, settings.controllerPort)
        interfaces.rawSocket.sendMessage(command)
    } finally {
        try { interfaces.rawSocket.close() } catch(e) {}
    }
}

private void logError(msg) { if(settings.logLevel.toInteger() >= 1) log.error "${device.displayName}: ${msg}" }
private void logWarn(msg)  { if(settings.logLevel.toInteger() >= 2) log.warn  "${device.displayName}: ${msg}" }
private void logInfo(msg)  { if(settings.logLevel.toInteger() >= 3) log.info  "${device.displayName}: ${msg}" }
private void logDebug(msg) { if(settings.logLevel.toInteger() >= 4) log.debug "${device.displayName}: ${msg}" }
private void logTrace(msg) { if(settings.logLevel.toInteger() >= 5) log.trace "${device.displayName}: ${msg}" }
