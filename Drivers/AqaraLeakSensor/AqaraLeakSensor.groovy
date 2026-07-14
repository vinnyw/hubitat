metadata {
    definition(
        name: 'Aqara Leak Sensor',
        namespace: 'vinnyw',
        author: 'Vinny Wadding',
        importUrl: ''
    ) {
        capability 'WaterSensor'
        capability 'Battery'
        capability 'Sensor'

        fingerprint profileId: '0104', inClusters: '0000,0003,0001', outClusters: '0000,0003,0019', manufacturer: 'LUMI', model: 'lumi.sensor_wleak.aq1', deviceJoinName: 'Aqara Water Leak Sensor'
    }

    preferences {
        input name: 'enableDebug', type: 'bool', title: 'Enable debug logging', defaultValue: true
    }
}

def installed() {
    initialize()
}

def updated() {
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    if (enableDebug) log.debug 'Initialized'
}

def parse(String description) {
    if (enableDebug) log.debug "parse: ${description}"

    try {
        // HARD FILTER: Ignore Xiaomi/Aqara Basic cluster spam
        if (description?.contains('cluster: 0000')) {
            return
        }

        // Handle IAS Zone (water detection)
        if (description?.startsWith('zone status')) {
            def zs = zigbee.parseZoneStatus(description)

            if (zs) {
                def wet = zs.alarm1 ? 'wet' : 'dry'
                handleWaterEvent(wet)

                if (enableDebug) log.debug "IAS Zone → water: ${wet}"
            }
            return
        }

        // Standard Zigbee parsing (battery etc.)
        def descMap = zigbee.parseDescriptionAsMap(description)

        if (!descMap) return

        // Battery (Aqara raw voltage)
        if (descMap.clusterInt == 0x0001 && descMap.attrInt == 0x0021) {
            def raw = Integer.parseInt(descMap.value, 16)
            def volts = raw / 10.0

            volts = Math.max(2.5, Math.min(volts, 3.0))
            def pct = ((volts - 2.5) / 0.5 * 100).toInteger()

            handleBatteryEvent(pct)

            if (enableDebug) log.debug "Battery raw: ${volts}V → ${pct}%"
        }
    } catch (e) {
        log.error "Parse error: ${e.message}"
    }
}

def handleWaterEvent(value) {
    def current = device.currentValue('water')

    if (current != value) {
        sendEvent(name: 'water', value: value)
        if (enableDebug) log.debug "Water state: ${value}"
    }
}

def handleBatteryEvent(value) {
    def current = device.currentValue('battery')

    if (current != value) {
        sendEvent(name: 'battery', value: value, unit: '%')
        if (enableDebug) log.debug "Battery: ${value}%"
    }
}
