/**
 *  --------------------------------------------------------------------------------------------------------------
 *  WeatherHub Child Device
 *  --------------------------------------------------------------------------------------------------------------
 *
 *  Author      : Vinny Wadding
 *  Namespace   : vinnyw
 *  Version     : 1.3.8
 *  Date        : 2026-07-28
 *
 *  Description :
 *      Child device driver for WeatherHub.
 *
 *      Responsibilities:
 *          - Stores and exposes forecast values published by the WeatherHub Manager parent app
 *          - Delegates Refresh requests to the parent app
 *          - Initialises missing attributes and synchronises parent-controlled logging
 *          - Performs no independent forecast scheduling, polling, or external HTTP requests
 *
 *      Capabilities:
 *          Sensor
 *          Refresh
 *          TemperatureMeasurement
 *          RelativeHumidityMeasurement
 *
 *      Commands:
 *          Refresh
 *
 *      Custom Attributes:
 *          dewPoint
 *          feelsLike
 *          forecastTime
 *          lastActivity
 *          locationName
 *          precipitationAmount
 *          precipitationProbability
 *          precipitationRate
 *          pressure
 *          snowAmount
 *          uvIndex
 *          uvLevel
 *          visibility
 *          weatherCode
 *          weatherCondition
 *          weatherSummary
 *          windDirection
 *          windDirectionCardinal
 *          windGust
 *          windSpeed
 *
 *      Standard Attributes:
 *          temperature
 *          humidity
 *
 *  --------------------------------------------------------------------------------------------------------------
 */

metadata {
    definition(
        name: 'WeatherHub',
        namespace: 'vinnyw',
        author: 'Vinny Wadding',
        importUrl: 'https://raw.githubusercontent.com/vinnyw/hubitat/master/WeatherHub/Drivers/WeatherHub.groovy',
        iconUrl: 'https://raw.githubusercontent.com/vinnyw/hubitat/master/WeatherHub/resources/weatherhub.png',
        iconX2Url: 'https://raw.githubusercontent.com/vinnyw/hubitat/master/WeatherHub/resources/weatherhub.png'
    ) {
        capability 'Sensor'
        capability 'Refresh'
        capability 'TemperatureMeasurement'
        capability 'RelativeHumidityMeasurement'

        attribute 'dewPoint', 'number'
        attribute 'feelsLike', 'number'

        attribute 'forecastTime', 'string'
        attribute 'lastActivity', 'number'
        attribute 'locationName', 'string'

        attribute 'precipitationAmount', 'number'
        attribute 'precipitationProbability', 'number'
        attribute 'precipitationRate', 'number'

        attribute 'pressure', 'number'

        attribute 'snowAmount', 'number'

        attribute 'uvIndex', 'number'
        attribute 'uvLevel', 'enum', ['Unknown', 'Low', 'Moderate', 'High', 'Very High', 'Extreme']

        attribute 'visibility', 'number'

        attribute 'weatherCode', 'number'
        attribute 'weatherCondition', 'enum', ['not available', 'clear night', 'sunny day', 'partly cloudy night', 'partly cloudy day', 'not used', 'mist', 'fog', 'cloudy', 'overcast', 'light rain shower night', 'light rain shower day', 'drizzle', 'light rain', 'heavy rain shower night', 'heavy rain shower day', 'heavy rain', 'sleet shower night', 'sleet shower day', 'sleet', 'hail shower night', 'hail shower day', 'hail', 'light snow shower night', 'light snow shower day', 'light snow', 'heavy snow shower night', 'heavy snow shower day', 'heavy snow', 'thunder shower night', 'thunder shower day', 'thunder']
        attribute 'weatherSummary', 'string'

        attribute 'windDirection', 'number'
        attribute 'windDirectionCardinal', 'enum', ['Unknown', 'N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE', 'S', 'SSW', 'SW', 'WSW', 'W', 'WNW', 'NW', 'NNW']
        attribute 'windGust', 'number'
        attribute 'windSpeed', 'number'
    }

    preferences {
        input name: 'txtEnable', type: 'bool',
              title: 'Enable descriptionText logging',
              defaultValue: true

        input name: 'debugEnable', type: 'bool',
              title: 'Enable debug logging',
              defaultValue: false
    }
}

private String formatDisplayVersion(Object versionValue) {
    String version = versionValue?.toString()?.trim()
    return version ? "v${version}" : 'unknown'
}

def getVersion() {
    return parent?.getVersion() ?: 'unknown'
}

private void synchronizeDriverVersion() {
    String currentVersion = getVersion()?.toString()?.trim() ?: 'unknown'
    String previousVersion = state?.driverVersion?.toString()

    state.driverVersion = currentVersion

    if (!previousVersion) {
        log.info "Driver Version: ${formatDisplayVersion(currentVersion)}"
    } else if (previousVersion != currentVersion) {
        log.info "Driver Version: ${formatDisplayVersion(currentVersion)}"
    }
}

def configure() {
    Map cfg = parent?.getChildDriverLoggingConfig()
    if (cfg instanceof Map) {
        applyParentLogging(cfg.txtEnable, cfg.debugEnable)
    }

    scheduleDebugAutoDisableIfNeeded()
    initializeAttributes()
    synchronizeDriverVersion()

    logDebug("Configure completed with txtEnable=${settings?.txtEnable}, debugEnable=${settings?.debugEnable}, version=${getVersion()}")
}

void installed() {
    configure()
}

void updated() {
    unschedule('logsOff')
    parent?.updateLoggingFromDriver(settings?.txtEnable, settings?.debugEnable)
    configure()
}

void refresh() {
    logDebug('Refresh requested; delegating to parent app')
    parent?.childRefresh()
}

void updateWeather(Map values) {
    synchronizeDriverVersion()

    if (!values) {
        return
    }

    publishChanged('lastActivity', values.lastActivity)
    publishChanged('forecastTime', values.forecastTime)
    publishChanged('locationName', values.locationName)
    publishChanged('weatherCode', values.weatherCode)
    publishChanged('weatherCondition', values.weatherCondition)
    publishChanged('weatherSummary', values.summary)

    publishChanged('temperature', values.temperature, '°C')
    publishChanged('humidity', values.humidity, '%')
    publishChanged('feelsLike', values.feelsLike, '°C')
    publishChanged('dewPoint', values.dewPoint, '°C')
    publishChanged('pressure', values.pressure, 'Pa')
    publishChanged('visibility', values.visibility, 'm')
    publishChanged('windSpeed', values.windSpeed, 'm/s')
    publishChanged('windGust', values.windGust, 'm/s')
    publishChanged('windDirection', values.windDirection, '°')

    String cardinal = values.windDirectionCardinal?.toString()
    if (!cardinal) {
        cardinal = windDirectionToCardinal(values.windDirection)
    }
    publishChanged('windDirectionCardinal', cardinal)

    publishChanged('uvIndex', values.uvIndex)

    String uvCategory = values.uvLevel?.toString()
    if (!uvCategory) {
        uvCategory = uvIndexToLevel(values.uvIndex)
    }
    publishChanged('uvLevel', uvCategory)

    publishChanged('precipitationRate', values.precipitationRate, 'mm/h')
    publishChanged('precipitationAmount', values.precipitationAmount, 'mm')
    publishChanged('snowAmount', values.snowAmount, 'mm')
    publishChanged('precipitationProbability', values.precipitationProbability, '%')
    logText('Weather forecast attributes updated')
}

//
//    LOGGING CONFIGURATION AND SYNCHRONISATION
//

private void applyParentLogging(txtEnableValue, debugEnableValue) {
    Boolean descEnabled = normalizeBoolean(txtEnableValue, true)
    Boolean debugEnabled = normalizeBoolean(debugEnableValue, false)

    updateBooleanSettingIfChanged('txtEnable', descEnabled)
    updateBooleanSettingIfChanged('debugEnable', debugEnabled)
}

private void updateBooleanSettingIfChanged(String name, Boolean newValue) {
    Boolean currentValue = normalizeBoolean(settings?."${name}", newValue)
    if (currentValue != newValue) {
        device.updateSetting(name, [value: newValue, type: 'bool'])
    }
}

//
//    LOGGING SCHEDULER
//

private Integer debugAutoDisableMinutes() {
    return (int) (debugAutoDisableSeconds() / 60)
}

private Integer debugAutoDisableSeconds() {
    return getParentDebugAutoDisableSeconds()
}

private Integer getParentDebugAutoDisableSeconds() {
    try {
        return normalizeDebugAutoDisableSeconds(parent?.getDebugAutoDisableSeconds())
    } catch (Exception ignored) {
        return 1800
    }
}

def logsOff() {
    if (!debugLoggingEnabled()) return

    updateBooleanSettingIfChanged('debugEnable', false)

    try {
        parent?.updateLoggingFromDriver(settings?.txtEnable, false)
    } catch (Exception ignored) {
    }

    log.warn "${device.displayName}: Debug logging disabled automatically after ${debugAutoDisableMinutes()} minutes"
}

private Integer normalizeDebugAutoDisableSeconds(value) {
    try {
        Integer seconds = value as Integer
        return seconds > 0 ? seconds : 1800
    } catch (Exception ignored) {
        return 1800
    }
}

private void scheduleDebugAutoDisableIfNeeded() {
    unschedule('logsOff')

    if (debugLoggingEnabled()) {
        runIn(debugAutoDisableSeconds(), 'logsOff')
        logDebug("Debug logging will automatically turn off in ${debugAutoDisableMinutes()} minutes")
    }
}

//
//    LOGGING HELPERS
//

private Boolean debugLoggingEnabled() {
    return normalizeBoolean(settings?.debugEnable, false)
}

private Boolean descriptionTextLoggingEnabled() {
    return normalizeBoolean(settings?.txtEnable, true)
}

private void logDebug(String msg) {
    if (debugLoggingEnabled()) log.debug "${device.displayName}: ${msg}"
}

private void logText(String msg) {
    if (descriptionTextLoggingEnabled()) log.info "${device.displayName}: ${msg}"
}

private void logWarn(String msg) {
    log.warn "${device.displayName}: ${msg}"
}

private Boolean normalizeBoolean(value, Boolean defaultValue) {
    if (value == null) return defaultValue
    if (value instanceof Boolean) return value
    String s = value.toString().trim().toLowerCase()
    if (s == 'true') return true
    if (s == 'false') return false
    return defaultValue
}

private void initializeAttributes() {
    initializeIfMissing('windDirectionCardinal', 'Unknown')
    initializeIfMissing('uvLevel', 'unknown')
}

private void initializeIfMissing(String name, def value) {
    if (device.currentValue(name) == null) {
        sendEvent(name: name, value: value)
    }
}

private String windDirectionToCardinal(def degreesValue) {
    BigDecimal degrees = safeDecimal(degreesValue)
    if (degrees == null) {
        return null
    }

    // Avoid BigDecimal remainder/modulo: Hubitat's Groovy runtime does not
    // support mod() for this numeric type.
    Double rawDegrees = degrees.doubleValue()
    Double normalized = rawDegrees - (Math.floor(rawDegrees / 360.0D) * 360.0D)

    List<String> points = [
        'N', 'NNE', 'NE', 'ENE',
        'E', 'ESE', 'SE', 'SSE',
        'S', 'SSW', 'SW', 'WSW',
        'W', 'WNW', 'NW', 'NNW'
    ]

    Integer index = Math.floor((normalized + 11.25D) / 22.5D) as Integer
    if (index >= points.size()) {
        index = 0
    }

    return points[index]
}

private String uvIndexToLevel(def uvValue) {
    BigDecimal uv = safeDecimal(uvValue)
    if (uv == null || uv < 0G) {
        return null
    }

    if (uv <= 2G) return 'Low'
    if (uv <= 5G) return 'Medium'
    if (uv <= 7G) return 'High'
    if (uv <= 10G) return 'Very high'
    return 'Extremely high'
}

private BigDecimal safeDecimal(def value) {
    if (value == null) {
        return null
    }

    try {
        return value as BigDecimal
    } catch (Exception ignored) {
        return null
    }
}

private void publishChanged(String name, def value, String unit = null) {
    if (value == null) {
        return
    }

    def currentValue = device.currentValue(name)
    if (currentValue?.toString() == value.toString()) {
        return
    }

    Map event = [name: name, value: value]
    if (unit) {
        event.unit = unit
    }

    sendEvent(event)
}
