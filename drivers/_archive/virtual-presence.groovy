/**
 *  Virtual Presence Sensor
 *
 *  Copyright 2020 vinnyw
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 */
metadata {
	definition (name: "Virtual Presence", namespace: "vinnyw", author: "vinnyw", mcdSync: true, cstHandler: true,
		mnmn: "SmartThings", vid: "generic-switch", ocfDeviceType: "x.com.st.d.sensor.presence" ) {

		capability "Actuator"
		capability "Presence Sensor"
		capability "Switch"
		capability "Health Check"

		command "arrived"
		command "departed"
	}

	simulator {
		// TODO
	}

	tiles {
		standardTile("presence", "device.presence", width: 3, height: 3, canChangeIcon: false, canChangeBackground: true) {
			state("present", label: '${currentValue}', icon:"st.presence.tile.mobile-present", backgroundColor:"#00A0DC")
			state("not present", label: '${currentValue}', icon:"st.presence.tile.mobile-not-present", backgroundColor:"#FFFFFF")
		}
  
		standardTile("switch", "device.switch", decoration: "flat", width: 3, height: 3, canChangeIcon: false, canChangeBackground: true) {
			state("off", action: "switch.on", icon: "st.switches.switch.off", backgroundColor: "#00A0DC")
			state("on", action: "switch.off", icon: "st.switches.switch.on", backgroundColor: "#FFFFFF")
		}

 		main(["switch"])
		details(["presense","switch"])
	}

	preferences {
		input name: "deviceReset", type: "boolean", title: "Auto reset?", defaultValue: false, required: true
		input name: "deviceEvent", type: "boolean", title: "Ignore state?", defaultValue: false, required: true
		input name: "deviceDebug", type: "boolean", title: "Debug log?", defaultValue: false, required: true
		input type: "paragraph", element: "paragraph", title: "Virtual Presense", description: "${version}", displayDuringSetup: false
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

	sendEvent(name: "presence", value: "present", isStateChange: true)
	sendEvent(name: "switch", value: "on", isStateChange: true, displayed: false)

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
	sendEvent(name: "presence", value: "not present", isStateChange: true)
	sendEvent(name: "switch", value: "off", isStateChange: true, displayed: false)
}

def arrived() {
	if (deviceDebug) {
		writeLog("arrived()")
	}

	on()
}

def departed() {
	if (deviceDebug) {
		writeLog("Executing 'departed()'")
	}

	off()
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
	return "1.0.3"
}

