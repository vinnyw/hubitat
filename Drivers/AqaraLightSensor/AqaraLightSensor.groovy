/*
 * Aqara Light Sensor (GZCGQ11LM / MGL01) Optimized Aqara-Compatible
 *
 * Device-reported models:
 * - lumi.sen_ill.agl01
 * - lumi.sen_ill.mgl01
 *
 * Capabilities:
 * - Sensor
 * - IlluminanceMeasurement
 * - Battery
 * - Refresh
 * - Configuration
 *
 * Design:
 * - No polling
 * - No recurring refresh schedule
 * - Preserves autodetection fingerprints and adds explicit Zigbee controllerType
 * - Enables singleThreaded execution for deterministic top-level driver calls
 * - Preserves Disable Device Status LED preference
 * - Configure binds Basic, Power, Identify, and Illuminance clusters
 * - Configure triggers Refresh after reporting setup
 * - Illuminance uses only normalized Hubitat read-attribute values or validated report tuples
 * - Battery uses standard voltage plus Xiaomi/Aqara FF01/FF02 proprietary payloads
 * - Does not use unsupported Power Configuration battery percentage attr 0x0021
 * - Excludes 0x0402 Temperature by request
 */

import groovy.transform.Field

@Field final Integer CLUSTER_BASIC        = 0x0000
@Field final Integer CLUSTER_POWER        = 0x0001
@Field final Integer CLUSTER_IDENTIFY     = 0x0003
@Field final Integer CLUSTER_ILLUMINANCE  = 0x0400

@Field final Integer ATTR_MANUFACTURER    = 0x0004
@Field final Integer ATTR_MODEL_ID        = 0x0005
@Field final Integer ATTR_BATTERY_VOLTAGE = 0x0020
@Field final Integer ATTR_MEASURED_VALUE  = 0x0000
@Field final Integer ATTR_XIAOMI_FF01     = 0xFF01
@Field final Integer ATTR_XIAOMI_FF02     = 0xFF02

metadata {
    definition(
        name: "Aqara Light Sensor",
        namespace: "vinnyw",
        author: "Vinny Wadding",
        singleThreaded: true
    ) {
        capability "Sensor"
        capability "IlluminanceMeasurement"
        capability "Battery"
        capability "Refresh"
        capability "Configuration"

        fingerprint(
            profileId: "0104",
            endpointId: "01",
            inClusters: "0000,0400,0003,0001",
            outClusters: "0003",
            manufacturer: "LUMI",
            model: "lumi.sen_ill.agl01",
            deviceJoinName: "Aqara Light Sensor AGL01",
            controllerType: "ZGB"
        )

        fingerprint(
            profileId: "0104",
            endpointId: "01",
            inClusters: "0000,0001,0003,0400",
            outClusters: "0003",
            manufacturer: "XIAOMI",
            model: "lumi.sen_ill.mgl01",
            deviceJoinName: "Aqara Light Sensor MGL01",
            controllerType: "ZGB"
        )
    }

    preferences {
        input name: "txtEnable",
              type: "bool",
              title: "Enable descriptionText logging",
              defaultValue: true

        input name: "debugEnable",
              type: "bool",
              title: "Enable debug logging",
              defaultValue: false

        input name: "disableLED",
              type: "bool",
              title: "Disable Device Status LED",
              defaultValue: false
    }
}

void installed() {
    log.info "${device.displayName} installed"
}

void updated() {
    log.info "${device.displayName} updated"

    unschedule(logsOff)

    if (debugEnable) {
        log.debug "${device.displayName} debug logging will disable in 30 minutes"
        runIn(1800, logsOff)
    }
}

List<String> configure() {
    log.info "${device.displayName} configure requested"

    String endpoint = device.endpointId ?: "01"
    List<String> cmds = []

    /* Aqara-compatible bindings */
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x${endpoint} 0x01 0x0000 {${device.zigbeeId}} {}"
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x${endpoint} 0x01 0x0001 {${device.zigbeeId}} {}"
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x${endpoint} 0x01 0x0003 {${device.zigbeeId}} {}"
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x${endpoint} 0x01 0x0400 {${device.zigbeeId}} {}"

    /*
     * Illuminance reporting:
     * - Cluster 0x0400, attr 0x0000, type uint16
     * - Min 10s, max 1h, reportable change 300 raw units
     *
     * Battery voltage reporting:
     * - Cluster 0x0001, attr 0x0020, type uint8
     * - Min/max 1h, reportable change 1 tenth volt
     */
    cmds += zigbee.configureReporting(CLUSTER_ILLUMINANCE, ATTR_MEASURED_VALUE, 0x21, 10, 3600, 300)
    cmds += zigbee.configureReporting(CLUSTER_POWER, ATTR_BATTERY_VOLTAGE, 0x20, 3600, 3600, 1)

    if (disableLED) {
        cmds += suppressLed()
    }

    log.info "${device.displayName} configure will trigger refresh after reporting setup"
    cmds += refresh()

    return cmds
}

List<String> refresh() {
    log.info "${device.displayName} refresh requested"

    List<String> cmds = []

    cmds += zigbee.readAttribute(CLUSTER_ILLUMINANCE, ATTR_MEASURED_VALUE)
    cmds += zigbee.readAttribute(CLUSTER_POWER, ATTR_BATTERY_VOLTAGE)
    cmds += zigbee.readAttribute(CLUSTER_BASIC, ATTR_MANUFACTURER)
    cmds += zigbee.readAttribute(CLUSTER_BASIC, ATTR_MODEL_ID)

    return cmds
}

List<String> suppressLed() {
    log.info "${device.displayName} LED suppression requested"

    /*
     * Conservative only:
     * Sends standard Identify cluster command with identifyTime = 0.
     * This may stop identify-mode blinking, but may not disable Aqara firmware status blinks.
     */
    String endpoint = device.endpointId ?: "01"
    return ["he cmd 0x${device.deviceNetworkId} 0x${endpoint} 0x0003 0x00 { 00 00 }"]
}

void parse(String description) {
    if (debugEnable) {
        log.debug "parse: ${description}"
    }

    if (!description) {
        return
    }

    /*
     * The working Aqara driver handles Xiaomi FF01/FF02 before the generic parser.
     * Keep that behavior, but do not manually decode standard illuminance read-attr
     * messages; Hubitat normalizes those values correctly.
     */
    if (description.startsWith("read attr -")) {
        Map readMap = parseReadAttrDescription(description)
        if (readMap && handleXiaomiReadAttribute(readMap)) {
            return
        }
    }

    Map descMap = zigbee.parseDescriptionAsMap(description)

    if (!descMap) {
        if (debugEnable) {
            log.debug "ignored empty parse result"
        }
        return
    }

    Integer clusterInt = parseClusterId(descMap)

    if (clusterInt == null) {
        if (debugEnable) {
            log.debug "ignored message without cluster: ${descMap}"
        }
        return
    }

    /*
     * Ignore known non-sensor responses:
     * - Identify cluster messages
     * - Configure reporting responses, command 0x07
     * - Bind responses, cluster 0x8021
     */
    if (clusterInt == CLUSTER_IDENTIFY || descMap.command == "07" || clusterInt == 0x8021) {
        if (debugEnable) {
            log.debug "ignored non-sensor Zigbee response: ${descMap}"
        }
        return
    }

    Boolean handled = false

    if (descMap?.attrId != null && descMap?.value != null) {
        handled = handleParsedAttribute(clusterInt, descMap.attrId, descMap.value, descMap.encoding)
    }

    if (descMap?.additionalAttrs instanceof List) {
        descMap.additionalAttrs.each { Map attrMap ->
            if (attrMap?.attrId != null && attrMap?.value != null) {
                handled = handleParsedAttribute(clusterInt, attrMap.attrId, attrMap.value, attrMap.encoding) || handled
            }
        }
    }

    if (!handled && descMap?.data) {
        handled = handleCatchallPayload(clusterInt, descMap)
    }

    if (!handled && debugEnable) {
        log.debug "ignored unhandled message: ${descMap}"
    }
}

Boolean handleParsedAttribute(Integer clusterInt, Object attrId, Object value, Object encoding = null) {
    Integer attrInt = parseAttributeId(attrId)

    if (attrInt == null) {
        return false
    }

    if (clusterInt == CLUSTER_ILLUMINANCE && attrInt == ATTR_MEASURED_VALUE) {
        /*
         * For parsed-map illuminance values, Hubitat has already normalized byte order.
         * This is the safe path that produced realistic 98-110 lux values in logs.
         */
        Integer rawValue = parseHexText(value)
        if (rawValue == null) {
            return false
        }
        handleIlluminance(rawValue)
        return true
    }

    if (clusterInt == CLUSTER_POWER && attrInt == ATTR_BATTERY_VOLTAGE) {
        Integer rawValue = parseHexText(value)
        if (rawValue == null) {
            return false
        }
        handleBatteryVoltage(rawValue)
        return true
    }

    if (clusterInt == CLUSTER_BASIC && (attrInt == ATTR_XIAOMI_FF01 || attrInt == ATTR_XIAOMI_FF02)) {
        return handleXiaomiStruct(attrInt, value?.toString())
    }

    return false
}

Boolean handleCatchallPayload(Integer clusterInt, Map descMap) {
    List data = descMap.data as List

    if (!data || data.size() < 3) {
        return false
    }

    /*
     * Xiaomi/Aqara proprietary battery payloads may be delivered as Basic-cluster
     * catchall data. Decode only recognized FF01/FF02/FF42 markers.
     */
    if (clusterInt == CLUSTER_BASIC) {
        String hex = data.collect { normalizeHexByte(it) }.join("").toUpperCase()

        if (hex.contains("FF01") || hex.contains("FF02")) {
            return handleXiaomiStructFromPayload(hex)
        }

        if (hex.contains("FF42")) {
            return handleXiaomiModelPayload(hex)
        }

        return false
    }

    /*
     * Do not decode Illuminance catchall command 0x07 frames. Those are configure
     * reporting responses, not sensor readings. Only decode standard attribute
     * report command 0x0A or read-attribute response command 0x01 with success status.
     */
    if (clusterInt == CLUSTER_ILLUMINANCE || clusterInt == CLUSTER_POWER) {
        String command = descMap.command?.toString()?.toUpperCase()

        if (command == "0A") {
            return handleAttributeReportPayload(clusterInt, data)
        }

        if (command == "01") {
            return handleReadAttributeResponsePayload(clusterInt, data)
        }
    }

    return false
}

Boolean handleAttributeReportPayload(Integer clusterInt, List data) {
    Integer i = 0
    Boolean handled = false

    while (i + 2 < data.size()) {
        Integer attrId = hexPairToInt(data[i]) + (hexPairToInt(data[i + 1]) << 8)
        Integer dataType = hexPairToInt(data[i + 2])
        i += 3

        Integer valueLength = zigbeeTypeLength(dataType)

        if (valueLength <= 0 || i + valueLength > data.size()) {
            if (debugEnable) {
                log.debug "stopped parsing attribute report cluster ${intToHex4(clusterInt)} attr ${intToHex4(attrId)} type ${intToHex2(dataType)} data ${data}"
            }
            return handled
        }

        Integer rawValue = littleEndianValue(data, i, valueLength)
        i += valueLength

        handled = handleAttributeValue(clusterInt, attrId, rawValue) || handled
    }

    return handled
}

Boolean handleReadAttributeResponsePayload(Integer clusterInt, List data) {
    Integer i = 0
    Boolean handled = false

    while (i + 2 < data.size()) {
        Integer attrId = hexPairToInt(data[i]) + (hexPairToInt(data[i + 1]) << 8)
        Integer status = hexPairToInt(data[i + 2])
        i += 3

        if (status != 0x00) {
            if (debugEnable) {
                log.debug "attribute ${intToHex4(attrId)} read/report rejected with status ${intToHex2(status)}"
            }
            continue
        }

        if (i >= data.size()) {
            return handled
        }

        Integer dataType = hexPairToInt(data[i])
        i += 1

        Integer valueLength = zigbeeTypeLength(dataType)

        if (valueLength <= 0 || i + valueLength > data.size()) {
            if (debugEnable) {
                log.debug "stopped parsing read response cluster ${intToHex4(clusterInt)} attr ${intToHex4(attrId)} type ${intToHex2(dataType)} data ${data}"
            }
            return handled
        }

        Integer rawValue = littleEndianValue(data, i, valueLength)
        i += valueLength

        handled = handleAttributeValue(clusterInt, attrId, rawValue) || handled
    }

    return handled
}

Boolean handleAttributeValue(Integer clusterInt, Integer attrId, Integer rawValue) {
    if (clusterInt == CLUSTER_ILLUMINANCE && attrId == ATTR_MEASURED_VALUE) {
        /*
         * Only validated attribute report/read-response tuples reach this path.
         */
        handleIlluminance(rawValue)
        return true
    }

    if (clusterInt == CLUSTER_POWER && attrId == ATTR_BATTERY_VOLTAGE) {
        handleBatteryVoltage(rawValue)
        return true
    }

    return false
}

Boolean handleXiaomiReadAttribute(Map readMap) {
    Integer clusterInt = parseHexText(readMap.cluster)
    Integer attrInt = parseHexText(readMap.attrId)

    if (clusterInt != CLUSTER_BASIC || attrInt == null || !readMap.value) {
        return false
    }

    if (attrInt == ATTR_XIAOMI_FF01 || attrInt == ATTR_XIAOMI_FF02) {
        return handleXiaomiStruct(attrInt, readMap.value.toString())
    }

    if (attrInt == ATTR_MODEL_ID && readMap.encoding?.toString()?.equalsIgnoreCase("42")) {
        return handleXiaomiModelPayload(readMap.value.toString())
    }

    return false
}

Boolean handleXiaomiStructFromPayload(String hex) {
    try {
        Integer ff01 = hex.indexOf("FF01")
        if (ff01 >= 0) {
            String payload = hex.substring(ff01 + 4)
            if (handleXiaomiStruct(ATTR_XIAOMI_FF01, payload)) {
                return true
            }
        }

        Integer ff02 = hex.indexOf("FF02")
        if (ff02 >= 0) {
            String payload = hex.substring(ff02 + 4)
            if (handleXiaomiStruct(ATTR_XIAOMI_FF02, payload)) {
                return true
            }
        }
    }
    catch (Exception ignored) {
        return false
    }

    return false
}

Boolean handleXiaomiStruct(Integer attrInt, String value) {
    if (!value) {
        return false
    }

    String data = value.toUpperCase().replaceAll("[^0-9A-F]", "")
    String batteryVoltage = ""

    try {
        /*
         * Same battery-voltage extraction pattern used by the known-working
         * Xiaomi/Aqara driver.
         */
        if (attrInt == ATTR_XIAOMI_FF01 && data.size() > 10 && data[4..5] == "21") {
            batteryVoltage = data[8..9] + data[6..7]
        }
        else if (attrInt == ATTR_XIAOMI_FF02 && data.size() > 14 && data[8..9] == "21") {
            batteryVoltage = data[12..13] + data[10..11]
        }
    }
    catch (Exception ignored) {
        batteryVoltage = ""
    }

    if (batteryVoltage) {
        handleBatteryVolts(Integer.parseInt(batteryVoltage, 16) / 100.0)
        return true
    }

    return false
}

Boolean handleXiaomiModelPayload(String value) {
    if (!value) {
        return false
    }

    String data = value.toUpperCase().replaceAll("[^0-9A-F]", "")

    try {
        if (data.contains("FF42")) {
            String batteryData = data.split("FF42", 2)[1]
            if (batteryData.size() > 10 && batteryData[4..5] == "21") {
                String batteryVoltage = batteryData[8..9] + batteryData[6..7]
                handleBatteryVolts(Integer.parseInt(batteryVoltage, 16) / 100.0)
                return true
            }
        }
    }
    catch (Exception ignored) {
        return false
    }

    return false
}

void handleIlluminance(Integer rawValue) {
    if (rawValue == null) {
        return
    }

    if (rawValue == 0xFFFF) {
        sendSensorEvent("illuminance", 0, "lux")
        return
    }

    /*
     * Zigbee Illuminance Measurement cluster measured value:
     * MeasuredValue = 10000 x log10(lux) + 1
     *
     * This reverts the broken over-bright path and keeps the original
     * Aqara/Hubitat conversion behavior.
     */
    Integer lux = rawValue <= 0 ? 0 : Math.round(Math.pow(10, ((rawValue - 1) / 10000.0)))

    /*
     * Defensive guard: reject impossible values generated by malformed payloads
     * rather than corrupting Current States. Direct sunlight is far below this.
     */
    if (lux > 200000) {
        if (debugEnable) {
            log.debug "${device.displayName} ignored unrealistic illuminance ${lux} lux from raw ${rawValue}"
        }
        return
    }

    sendSensorEvent("illuminance", lux, "lux")
}

void handleBatteryVoltage(Integer rawValue) {
    if (rawValue == null || rawValue <= 0 || rawValue == 0xFF) {
        return
    }

    /*
     * Standard Zigbee battery voltage attr 0x0020 is in tenths of a volt.
     */
    handleBatteryVolts(rawValue / 10.0)
}

void handleBatteryVolts(BigDecimal volts) {
    if (volts == null || volts <= 0) {
        return
    }

    Integer battery = Math.min(100, Math.max(1, Math.round((volts - 2.5) / 0.5 * 100)))

    sendSensorEvent("battery", battery, "%")
}

void sendSensorEvent(String name, Object value, String unit) {
    if (device.currentValue(name) == value) {
        return
    }

    String descriptionText = "${device.displayName} ${name} is ${value} ${unit}"

    if (txtEnable) {
        log.info descriptionText
    }

    sendEvent(
        name: name,
        value: value,
        unit: unit,
        descriptionText: descriptionText
    )
}

void logsOff() {
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
    log.info "${device.displayName} debug logging disabled"
}

Map parseReadAttrDescription(String description) {
    try {
        String body = description.replaceFirst(/^read attr - /, "")
        return body.split(", ").collectEntries { String entry ->
            List parts = entry.split(": ", 2)
            if (parts.size() == 2) {
                [(parts[0]): parts[1]]
            }
            else {
                [:]
            }
        }
    }
    catch (Exception ignored) {
        return [:]
    }
}

Integer parseClusterId(Map descMap) {
    /*
     * Prefer hex text fields over clusterInt because strings like "0400" must
     * be treated as hex 0x0400, not decimal 400.
     */
    if (descMap?.cluster != null) {
        return parseHexText(descMap.cluster)
    }

    if (descMap?.clusterId != null) {
        return parseHexText(descMap.clusterId)
    }

    if (descMap?.clusterInt instanceof Number) {
        return descMap.clusterInt as Integer
    }

    return parseHexText(descMap?.clusterInt)
}

Integer parseAttributeId(Object attrId) {
    return parseHexText(attrId)
}

Integer parseHexText(Object value) {
    if (value == null) {
        return null
    }

    if (value instanceof Number) {
        return value as Integer
    }

    String text = value.toString().trim()

    if (!text) {
        return null
    }

    try {
        return Integer.parseInt(text, 16)
    }
    catch (Exception ignored) {
        return null
    }
}

String normalizeHexByte(Object value) {
    return value.toString().padLeft(2, "0").takeRight(2)
}

Integer hexPairToInt(Object value) {
    return Integer.parseInt(normalizeHexByte(value), 16)
}

Integer littleEndianValue(List data, Integer startIndex, Integer length) {
    Integer value = 0

    for (Integer offset = 0; offset < length; offset++) {
        value += hexPairToInt(data[startIndex + offset]) << (8 * offset)
    }

    return value
}

Integer zigbeeTypeLength(Integer dataType) {
    switch (dataType) {
        case 0x10: return 1   // Boolean
        case 0x18: return 1   // Bitmap 8
        case 0x20: return 1   // Unsigned 8-bit
        case 0x21: return 2   // Unsigned 16-bit
        case 0x23: return 4   // Unsigned 32-bit
        case 0x28: return 1   // Signed 8-bit
        case 0x29: return 2   // Signed 16-bit
        case 0x2B: return 4   // Signed 32-bit
        case 0x30: return 1   // Enum 8
        case 0x31: return 2   // Enum 16
        case 0x39: return 4   // Single precision
        case 0xE0: return 4   // Time of day
        case 0x42:
            /*
             * Variable-length character strings are not decoded through the generic
             * tuple parser here; Xiaomi string-like payloads are handled separately.
             */
            return 0
        default:
            return 0
    }
}

String intToHex2(Integer value) {
    return String.format("%02X", value)
}

String intToHex4(Integer value) {
    return String.format("%04X", value)
}
