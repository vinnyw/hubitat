metadata {
	
	definition (
		name: "Virtual Switch", 
		namespace: "vinnyw", 
		author: "vinnyw", 
		filename: "virtualSwitch.groovy", 
		importUrl: "https://raw.githubusercontent.com/vinnyw/hubitat/master/drivers/virtualSwitch.groovy"
	) 
	
	{
		capability "Actuator"
		capability "Switch"
		capability "Sensor"
		command "on"
		command "off"
    }

	tiles {

		standardTile("switch", "device.switch", decoration: "flat", width: 3, height: 2, canChangeIcon: true, canChangeBackground: true) {
			state "off", label: '${currentValue}', action: "switch.on", icon: "st.switches.switch.off", backgroundColor: "#ffffff", nextState:"on"
			state "on", label: '${currentValue}', action: "switch.off", icon: "st.switches.switch.on", backgroundColor: "#00A0DC", nextState:"off"
		}

		main(["switch"])
		details(["switch"])

	}

	preferences {
		input name: "deviceReset", type: "bool", title: "Auto reset?", defaultValue: false, required: true
		input name: "deviceEvent", type: "bool", title: "Ignore state?", defaultValue: false, required: true
		input name: "deviceDebug", type: "bool", title: "Debug log?", defaultValue: false, required: true

		input type: "paragraph", element: "paragraph", description: "${version}"
	}

}

def installed() {
	if (deviceDebug) {
		writeLog("installed()")
		writeLog("settings: $settings", "INFO")
		writeLog("state: $state", "INFO")
	}

	state.clear()
	initialize()
	off()
}

private initialize() {
	if (deviceDebug) {
		writeLog("initialize()")
		writeLog("settings: $settings", "INFO")
		writeLog("state: $state", "INFO")
	}

	sendEvent(name: "DeviceWatch-Enroll", value: [protocol: "cloud", scheme: "untracked"].encodeAsJson(), displayed: false)
	sendEvent(name: "DeviceWatch-DeviceStatus", value: "online", displayed: false)
	sendEvent(name: "healthStatus", value: "online", displayed: false)
}

def updated() {
	if (deviceDebug) {
		writeLog("updated()")
		writeLog("settings: $settings", "INFO")
		writeLog("state: $state", "INFO")
	}

}

def on() {
	if (deviceDebug) {
		writeLog("on()")
	}

	if ((device.currentValue("switch") == "on") && !deviceEvent) {
		if (deviceDebug) {
			writeLog("no action required.")
		}
		return
	}

	sendEvent(name: "switch", value: "on", isStateChange: true)

	if (deviceReset) {
		runIn(1, "off", [overwrite: true])
	}
}

def off() {
	if (deviceDebug) {
		writeLog("off()")
	}

	if ((device.currentValue("switch") == "off") && !deviceEvent) {
		if (deviceDebug) {
			writeLog("no action required.")
		}
		return
	}

	unschedule()
	sendEvent(name: "switch", value: "off", isStateChange: true)
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

private getDeviceReset() {
	return (settings.deviceReset != null) ? settings.deviceReset.toBoolean() : false
}

private getDeviceEvent() {
	return (settings.deviceEvent != null) ? settings.deviceEvent.toBoolean() : false
}

private getDeviceDebug() {
	return (settings.deviceDebug != null) ? settings.deviceDebug.toBoolean() : false
}

private getVersion() {
	return "1.1.49"
}

