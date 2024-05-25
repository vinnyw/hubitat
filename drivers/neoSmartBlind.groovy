import groovy.json.JsonOutput

metadata {

	definition ( name: "Neo Smart Blind", namespace: "vinnyw", author: "vinnyw", 
		mnmn: "SmartThings", vid: "generic-shade", ocfDeviceType: "oic.d.blind") {

		capability "Window Shade"
		capability "Window Shade Level"
		capability "Window Shade Preset"
		capability "Health Check"

		command "open"
		command "close"
		command "pause"
		command "presetPosition"
	}

	simulator {
		// TODO
	}

	tiles(scale: 2) {
		multiAttributeTile(name:"windowShade", type: "generic", width: 6, height: 4, canChangeIcon: true) {
			tileAttribute ("device.windowShade", key: "PRIMARY_CONTROL") {
				attributeState "open", label:'${name}', action:"close", icon:"st.shades.shade-open", backgroundColor:"#79b821", nextState:"closing"
				attributeState "closed", label:'${name}', action:"open", icon:"st.shades.shade-closed", backgroundColor:"#ffffff", nextState:"opening"
				attributeState "partially open", label:'${name}', action:"close", icon:"st.shades.shade-open", backgroundColor:"#79b821", nextState:"closing"
				attributeState "opening", label:'${name}', action:"stop", icon:"st.shades.shade-opening", backgroundColor:"#79b821", nextState:"partially open"
				attributeState "closing", label:'${name}', action:"stop", icon:"st.shades.shade-closing", backgroundColor:"#ffffff", nextState:"partially open"
				attributeState "unknown", label:'${name}', action:"close", icon:"st.shades.shade-open", backgroundColor:"#79b821", nextState:"closing"
			}
			//tileAttribute ("device.level", key: "SLIDER_CONTROL") {
			//		attributeState "level", action:"setLevel"
			//}
		}

		standardTile("presetPosition", "device.presetPosition", width: 2, height: 2, decoration: "flat") {
			state "default", label: "Preset", action:"presetPosition", icon:"st.Home.home2"
		}

		main(["windowShade"])
		details(["windowShade", "presetPosition"])
	}

	preferences {
		input name: "controllerID", type: "text", title: "Controller ID", description: "\u2630 > Smart Controllers > Controller > ID", required: true
		input name: "controllerIP", type: "text", title: "Controller IP (Local)", description: "\u2630 > Smart Controllers > Controller > IP", required: true
		input name: "blindID", type: "text", title: "Blind code", description: "\u2630 > Your Rooms > Room > Blind > Blind Code", required: true
		input name: "blindDelay", type: "number", title: "Blind timing", description: "Time in seconds (Default: 15)", range: "1..120", displayDuringSetup: false
		input name: "blindPreset", type: "number", title: "Preset position", description: "Approximate percentage (Default: 50)", range: "1..99", displayDuringSetup: false
		input name: "deviceEvent", type: "boolean", title: "Ignore state?", defaultValue: false, required: true
		input name: "deviceDebug", type: "boolean", title: "Debug log?", defaultValue: false, required: true
		input type: "paragraph", element: "paragraph", title: "Neo Smart Blind", description: "${version}", displayDuringSetup: false
	}
}

def parse(description) {
	if (deviceDebug) {
		writeLog("parse('${description}')")
	}
	// TODO
}

def ping() {
	if (deviceDebug) {
		writeLog("ping()")
	}

	return true
}

def installed() {
	if (deviceDebug) {
		writeLog("installed()")
		writeLog("settings: $settings", "INFO")
		writeLog("state: $state", "INFO")
	}

	state.clear()
	initialize()
	updateState([position: "unknown", level: 0])
}

private initialize() {
	if (deviceDebug) {
		writeLog("initialize()")
		writeLog("settings: $settings", "INFO")
		writeLog("state: $state", "INFO")
	}
	
	sendEvent(name: "DeviceWatch-DeviceStatus", value: "online", displayed: false)
	sendEvent(name: "healthStatus", value: "online", displayed: false)

	sendEvent(name: "DeviceWatch-Enroll", value: [protocol: "cloud", scheme:"untracked"].encodeAsJson(), displayed: false)

}

def updated() {
	if (deviceDebug) {
		writeLog("updated()")
		writeLog("settings: $settings", "INFO")
		writeLog("state: $state", "INFO")
	}

	def commands = (settings.supportedCommands != null) ? settings.supportedCommands : "2"

	if (!setupComplete) {
		writeLog("Setup not completed. Required fields are missing or incorrect.", "ERROR")
		return
	}

	sendEvent(name: "supportedWindowShadeCommands", value: JsonOutput.toJson(supportedCommandsMap[commands]))

}

def open() {
	if (deviceDebug) {
		writeLog("open()")
	}

	def shadeState = device.currentState("windowShade")?.value

	if (!setupComplete) {
		sendEvent(name: "windowShade", value: "unknown", isStateChange: false, displayed: false)
		writeLog("Setup not completed. Required fields are missing or incorrect.", "ERROR")
		return
	}

	if ((shadeState.equalsIgnoreCase("open")) && !deviceEvent) {
		if (deviceDebug) {
			writeLog("no action required.")
		}
		return
	}

	unschedule()
	if (shadeState.equalsIgnoreCase("opening") || shadeState.equalsIgnoreCase("closing")) {
		pause()
		return
	}

	state.started = now().toLong()
	state.direction = 0

	sendEvent(name: "windowShade", value: "opening", isStateChange: false, displayed: false)
	attenuate("up")
	runIn(timeToLevel(0), "updateState", [overwrite: true, data: [position: "open", level: 0]])
}

def close() {
	if (deviceDebug) {
		writeLog("close()")
	}

	def shadeState = device.currentState("windowShade")?.value

	if (!setupComplete) {
		sendEvent(name: "windowShade", value: "unknown", isStateChange: false, displayed: false)
		writeLog("Setup not completed. Required fields are missing or incorrect.", "ERROR")
		return
	}

	if ((shadeState.equalsIgnoreCase("closed")) && !deviceEvent) {
		if (deviceDebug) {
			writeLog("no action required.")
		}
		return
	}

	unschedule()
	if (shadeState.equalsIgnoreCase("opening") || shadeState.equalsIgnoreCase("closing")) {
		pause()
		return
	}

	state.started = now().toLong()
	state.direction = 1

	sendEvent(name: "windowShade", value: "closing", isStateChange: false, displayed: false)
	attenuate("dn")
	runIn(timeToLevel(100), "updateState", [overwrite: true, data: [position: "closed", level: 100]])
}

def presetPosition() {
	if (deviceDebug) {
		writeLog("presetPosition()")
	}

	def shadeState = device.currentState("windowShade")?.value
	def shadeLevel = device.currentState("shadeLevel")?.value.toFloat()

	if (!setupComplete) {
		sendEvent(name: "windowShade", value: "unknown", isStateChange: false, displayed: false)
		writeLog("Setup not completed. Required fields are missing or incorrect.", "ERROR")
		return
	}

	if ((shadeLevel == blindPreset) && !deviceEvent) {
		if (deviceDebug) {
			writeLog("no action required.")
		}
		return
	}

	unschedule()
	if (shadeState.equalsIgnoreCase("opening") || shadeState.equalsIgnoreCase("closing")) {
		pause()
		return
	}

	state.started = now().toLong()

	if (shadeLevel <= 0) {
		state.direction = 1
		sendEvent(name: "windowShade", value: "closing", isStateChange: false, displayed: false)
	} else if (shadeLevel >= 100) {
		state.direction = 0
		sendEvent(name: "windowShade", value: "opening", isStateChange: false, displayed: false)
	} else if (shadeLevel <= blindPreset) {
		state.direction = 1
		sendEvent(name: "windowShade", value: "closing", isStateChange: false, displayed: false)
	} else if (shadeLevel > blindPreset) {
		state.direction = 0
		sendEvent(name: "windowShade", value: "opening", isStateChange: false, displayed: false)
	}

	attenuate("gp")
	runIn(timeToLevel(blindPreset), "updateState", [overwrite: true, data: [position: "partially open", level: blindPreset]])
}

def pause() {
	if (deviceDebug) {
		writeLog("pause()")
	}

	def shadeState = device.currentState("windowShade")?.value
	def shadeLevel = device.currentState("shadeLevel")?.value.toFloat()

	if (!setupComplete) {
		sendEvent(name: "windowShade", value: "unknown", isStateChange: false, displayed: false)
		writeLog("Setup not completed. Required fields are missing or incorrect.", "ERROR")
		return
	}

	unschedule()
	if (!shadeState.equalsIgnoreCase("opening") && !shadeState.equalsIgnoreCase("closing")) {
		sendEvent(name: "windowShade", value: "${shadeState}", isStateChange: false, displayed: false)
		return
	}

	attenuate("sp")
	def shadeNewLevel = (shadeLevel + positionFromTime()).toFloat().round(2)

	if (deviceDebug) {
		writeLog("position: ${shadeNewLevel}%", "INFO")
	}

	updateState([position: "partially open", level: shadeNewLevel])
}

def updateState(data) {
	if (deviceDebug) {
		writeLog("updateState()")
		writeLog("data: $data", "INFO")
	}

	sendEvent(name: "windowShade", value: data.position, isStateChange: true)
	sendEvent(name: "shadeLevel", value: data.level.toFloat().round(2), unit: "%", isStateChange: false, displayed: false)
}

private attenuate(action) {
	if (deviceDebug) {
		writeLog("attenuate($action)")
	}

	def result = new physicalgraph.device.HubAction (
		method: "GET",
		path: "/neo/v1/transmit",
		query: [command: "${blindID}-${action}", id: "${controllerID}", hash: "${hash}" ],
		headers: [
			Host: "${controllerIP}:8838",
			Connection: "close",
		],
		null,
		[callback: attenuateCallback]
	)

	try {
		delayBetween([sendHubCommand(result)], 750)
	} catch (e) {
		writeLog("$e", "ERROR")
	}

	//if (deviceDebug) {
	//	writeLog("\n $result", "INFO")
	//}
}

def attenuateCallback(physicalgraph.device.HubResponse hubResponse) {
	if (deviceDebug) {
		writeLog("attenuateCallback()")
		writeLog("${hubResponse}", "INFO")
	}

	switch (hubResponse.status?.toInteger()) {
		case 400:
			writeLog("Response: 400 Bad Request - Command not found or valid", "ERROR")
			sendEvent(name: "windowShade", value: "unknown", isStateChange: true)
			break
		case 401:
			writeLog("Response: 401 Unauthorized - ID not found or valid", "ERROR")
			sendEvent(name: "windowShade", value: "unknown", isStateChange: true)
			break
		case 404:
			writeLog("Response: 404 Not Found - URI not found or valid", "ERROR")
			sendEvent(name: "windowShade", value: "unknown", isStateChange: true)
			break
		case 409:
			writeLog("Response: 409 Conflict - Hash found but already used", "ERROR")
			sendEvent(name: "windowShade", value: "unknown", isStateChange: true)
			break
		case 200:
			if (deviceDebug) {
				writeLog("Response: 200 OK - Message received and transmitted", "INFO")
			}
			break
		default:
			if (deviceDebug) {
				writeLog("response ${hubResponse.status}", "INFO")
			}
			break
	}
}

private timeToLevel(targetLevel) {
	if (deviceDebug) {
		writeLog("timeToLevel(${targetLevel})")
	}

	def currentLevel = device.currentState("shadeLevel")?.value.toFloat()
	def timeDelay = blindDelay.toInteger()

	def percentTime = timeDelay / 100
	def levelDiff = currentLevel - targetLevel
	def runTime = levelDiff > 0 ? (percentTime * levelDiff) : (percentTime * -levelDiff)

	if (deviceDebug) {
		writeLog("runtime: ${runTime.round(2)}s" , "INFO")
	}

	return runTime.round(2)
}

private positionFromTime() {
	if (deviceDebug) {
		writeLog("positionFromTime()")
	}

	def currentLevel = device.currentState("shadeLevel")?.value.toFloat()
	def currentDirection = state.direction.toInteger()
	def timeDelay = blindDelay.toInteger()
	def runTime = ((now().toLong() - state.started.toLong()) / 1000).toFloat()

	def percentTime = 100 / timeDelay
    
	def newPosition = currentDirection > 0 ? (runTime * percentTime) : (runTime * -percentTime)

	if (deviceDebug) {
		writeLog("runtime: ${runTime.round(2)}s" , "INFO")
		writeLog("moved: ${newPosition.round(2)}%" , "INFO")
	}

	return newPosition.round(2)
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

private getBlindDelay() {
	return (settings.blindDelay != null) ? settings.blindDelay.toInteger() : 15
}

private getBlindPreset() {
	return (settings.blindPreset != null) ? settings.blindPreset.toInteger() : 50
}

private getDeviceEvent() {
	return (settings.deviceEvent != null) ? settings.deviceEvent.toBoolean() : false
}

private getDeviceDebug() {
	return (settings.deviceDebug != null) ? settings.deviceDebug.toBoolean() : false
}

private getSetupComplete() {

	if (!settings.controllerID || !settings.controllerIP || !settings.blindID) {
		return false
	}

	return true
}

private getSupportedCommandsMap() {
	[
		"1": ["open", "close"],
		"2": ["open", "close", "pause"],
		"3": ["open"],
		"4": ["close"],
		"5": ["pause"],
		"6": ["open", "pause"],
		"7": ["close", "pause"],
		"8": [],
		// For testing OCF/mobile client bugs
		"9": ["open", "closed", "pause"],
		"10": ["open", "closed", "close", "pause"]
	]
}

private getHash() {

	def currontRandom = new Random().nextInt(9) + 1			// 0-9
	def currentTime = new Date().getTime().toString() 		// ms

	return currontRandom.toString() + currentTime.substring(currentTime.length()-6)	// rnd + last 6 char
}

private getVersion() {
	return "1.6.17"
}

