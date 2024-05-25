/**
 *  Copyright 2015 SmartThings
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 **/
metadata {

	definition ( name: "Virtual Switch (Alexa)", namespace: "vinnyw", author: "vinnyw", mcdSync: true,
		mnmn: "SmartThings", vid: "generic-switch", ocfDeviceType: "oic.d.switch") {

		capability "Actuator"
		capability "Switch"
		capability "Contact Sensor"
		capability "Health Check"

		command "on"
		command "off"
	}

	simulator {
		// TODO
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
		input name: "deviceReset", type: "boolean", title: "Auto reset?", defaultValue: false, required: true
		input name: "deviceEvent", type: "boolean", title: "Ignore state?", defaultValue: false, required: true
		input name: "deviceDebug", type: "boolean", title: "Debug log?", defaultValue: false, required: true
		input type: "paragraph", element: "paragraph", title: "Virtual Switch (Alexa)", description: "${version}", displayDuringSetup: false
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
	sendEvent(name: "contact", value: "closed", isStateChange: true, displayed: false)

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

