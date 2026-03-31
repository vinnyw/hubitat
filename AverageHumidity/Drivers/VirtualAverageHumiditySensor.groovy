import java.math.RoundingMode

@Field static final String DRIVER_VERSION = '2.6.0'
@Field static final Integer DEBUG_AUTO_DISABLE_SECONDS = 1800

metadata {
    definition(
        name: 'Virtual Average Humidity Sensor',
        namespace: 'vinnyw',
        author: 'vinny wadding',
        importUrl: 'https://raw.githubusercontent.com/vinnyw/hubitat/master/{dirname}/Drivers/VirtualOccupancySensor.groovy'
    ) {
        capability 'Sensor'
        capability 'RelativeHumidityMeasurement'
        capability 'Refresh'

        attribute 'humidityDisplay', 'string'
        attribute 'lastUpdated', 'number'
    }

    preferences {
        input name: 'txtEnable', type: 'bool',
              title: 'Enable descriptionText logging',
              defaultValue: true

        input name: 'logLevel', type: 'enum',
              title: 'Logging Level',
              options: ['Off', 'Error', 'Warn', 'Info', 'Debug', 'Trace'],
              defaultValue: 'Off'
    }
}

def installed() { configure() }
def updated() { configure() }

def configure() {
    List allowed = ['OFF', 'ERROR', 'WARN', 'INFO', 'DEBUG', 'TRACE']
    String raw = logLevel
    String normalized = (raw ?: 'OFF').toUpperCase()

    if (!allowed.contains(normalized)) {
        device.updateSetting('logLevel', [value: 'Off', type: 'enum'])
        log.warn "${device.displayName}: Invalid logLevel '${raw}' detected. Auto-corrected to Off."
    }

    String previousVersion = state.driverVersion
    state.driverVersion = DRIVER_VERSION

    if (!previousVersion) {
        log.info "${device.displayName}: Driver installed (v${DRIVER_VERSION})"
    } else if (previousVersion != DRIVER_VERSION) {
        log.info "${device.displayName}: Driver upgraded from v${previousVersion} to v${DRIVER_VERSION}"
    }

    if (!device.currentValue('lastActivity')) {
        sendEvent(name: 'lastActivity', value: now(), displayed: false, type: 'digital')
    }
}

def refresh() {
    parent?.childRefreshRequest()
}

def setHumidity(val, decimals = 0, unit = '%') {
    if (val == null) return

    try {
        BigDecimal incoming = (val instanceof BigDecimal) ? val : new BigDecimal(val.toString())
        Integer scale = (decimals instanceof Number) ? (decimals as Integer) : 0
        BigDecimal rounded = incoming.setScale(scale, RoundingMode.HALF_UP)

        BigDecimal currentHumidity = null
        Object existingHumidity = device.currentValue('humidity')
        if (existingHumidity != null) {
            try {
                currentHumidity = new BigDecimal(existingHumidity.toString())
            } catch (Exception ignored) {
            }
        }

        Boolean humidityChanged = (currentHumidity == null || currentHumidity.compareTo(rounded) != 0)

        if (humidityChanged) {
            sendEvent(
                name: 'humidity',
                value: rounded,
                unit: (unit == 'none' ? '' : unit),
                type: 'digital'
            )
        }

        String displayValue = buildDisplayValue(rounded, unit)
        String currentDisplay = device.currentValue('humidityDisplay')?.toString()

        if (currentDisplay != displayValue) {
            sendEvent(
                name: 'humidityDisplay',
                value: displayValue,
                isStateChange: false,
                type: 'digital',
                descriptionText: ''
            )
        }

        Long nowTs = now()
        Long previousTs = null
        Object existingTs = device.currentValue('lastUpdated')
        if (existingTs != null) {
            try {
                previousTs = existingTs as Long
            } catch (Exception ignored) {
            }
        }

        if (previousTs == null || humidityChanged || (nowTs - previousTs) > 1000L) {
            sendEvent(
                name: 'lastUpdated',
                value: nowTs,
                type: 'digital'
            )
        }
    } catch (Exception e) {
        log.warn "setHumidity error: ${e.message}"
    }
}

private String buildDisplayValue(BigDecimal value, String unit) {
    switch (unit) {
        case 'none':
            return value.toString()
        case '%RH':
            return "${value}%RH"
        default:
            return "${value}%"
    }
}
