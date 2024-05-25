metadata {

	definition (
		name: "Virtual Switch (Alexa)",
		namespace: "vinnyw",
		author: "vinnyw",
		filename: "virtualSwitchAlexa.groovy",
		importUrl: "https://raw.githubusercontent.com/vinnyw/hubitat/master/drivers/virtualSwitchAlexa.groovy"
	)

	{

	    capability "Sensor"
        capability "Switch"
        capability "Contact Sensor"

		command "on"
		command "off"

	}

	preferences {
		input name: "deviceReset", type: "bool", title: "Auto reset?", defaultValue: false, required: true
		input name: "deviceEvent", type: "bool", title: "Ignore state?", defaultValue: false, required: true
		//input name: "deviceDebug", type: "bool", title: "Debug log?", defaultValue: false, required: true
	}

}

def installed() {
	initialize()
}

def updated() {
	initialize()
}

def initialize() {
    //if (deviceDebug) {
	//	writeLog("initialize()")
	//	writeLog("settings: $settings", "INFO")
	//	writeLog("state: $state", "INFO")
	//}

    sendEvent(name: "switch", value: "off")
	sendEvent(name: "contact", value: "open")
}

def on() {
	//if (deviceDebug) {
	//	writeLog("on()")
	//}

	if (device.currentValue("switch") == "on") {
		//if (deviceDebug) {
		//	writeLog("no action required.")
		//}
		return
	}

	sendEvent(name: "switch", value: "on", isStateChange: true)
	sendEvent(name: "contact", value: "closed", isStateChange: true, displayed: false)

	if (deviceReset) {
		runIn(2, "off", [overwrite: true])
	}

}

def off() {
	//if (deviceDebug) {
	//	writeLog("off()")
	//}

	if (device.currentValue("switch") == "off") {
		//if (deviceDebug) {
		//	writeLog("no action required.")
		//}
		return
	}

	unschedule()
	sendEvent(name: "switch", value: "off", isStateChange: true)

	if (deviceReset) {
		sendEvent(name: "contact", value: "", isStateChange: false, displayed: false)
	} else {
		sendEvent(name: "contact", value: "open", isStateChange: true, displayed: false)
	}
}

private writeLog(message, type = "DEBUG") {
	message = "${device} [v$version] ${message ?: ''}"
	switch (type?.toUpperCase()) {
		case "TRACE":
			log.trace "${message}"
			break
		case "DEBUG":
			log.debug "${message}"
			break
		case "INFO":
			log.info "${message}"
			break
		case "WARN":
			log.warn "${message}"
			break
		case "ERROR":
			log.error "${message}"
			break
		default:
			log.debug "${message}"
	}
}

private getVersion() {
	return "1.0.0"
}

