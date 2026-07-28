/**
 *  --------------------------------------------------------------------------------------------------------------
 *  WeatherHub Manager
 *  --------------------------------------------------------------------------------------------------------------
 *
 *  Author      : Vinny Wadding
 *  Namespace   : vinnyw
 *  Version     : 1.3.10
 *  Date        : 2026-07-28
 *
 *  Description :
 *      Parent application for WeatherHub.
 *
 *      Responsibilities:
 *          - Creates and manages one WeatherHub child device
 *          - Retrieves Met Office Weather DataHub site-specific forecast data
 *          - Selects and normalises the applicable forecast period
 *          - Publishes forecast values to the WeatherHub child device
 *          - Uses the hub latitude and longitude configured in Hub Details
 *          - Schedules forecast polling at the configured interval
 *          - Handles API credentials, request guarding, errors, and parent-controlled logging
 *
 *      Integration:
 *          Forecast data is retrieved from the Met Office Weather DataHub Site-Specific Forecast API.
 *
 *  --------------------------------------------------------------------------------------------------------------
 */

definition(
    name: 'WeatherHub Manager',
    namespace: 'vinnyw',
    author: 'Vinny Wadding',
    description: 'Polls the Met Office Weather forecast API and publishes forecast attributes to one child device.',
    category: 'Convenience',
    importUrl: 'https://raw.githubusercontent.com/vinnyw/hubitat/master/WeatherHub/Apps/WeatherHub.groovy',
    iconUrl: 'https://raw.githubusercontent.com/vinnyw/hubitat/master/WeatherHub/resources/weatherhub.png',
    iconX2Url: 'https://raw.githubusercontent.com/vinnyw/hubitat/master/WeatherHub/resources/weatherhub.png',
    singleInstance: true,
    singleThreaded: true,
    installOnOpen: true
)

import groovy.transform.Field

@Field static final Integer DEBUG_AUTO_DISABLE_SECONDS = 1800
@Field static final Integer HTTP_CONNECTION_TIMEOUT_SECONDS = 5
@Field static final String MET_OFFICE_API_PATH = 'https://data.hub.api.metoffice.gov.uk/sitespecific/v0/point'
@Field static final String MET_OFFICE_API_TIMESTEP = 'hourly'   //  valid steps are 'hourly', 'three-hourly' or 'daily'
@Field static final Boolean MET_OFFICE_API_INCLUDE_LOCATION_NAME = true
@Field static final Boolean MET_OFFICE_API_INCLUDE_PARAMETER_METADATA = false

preferences {
    page(name: 'mainPage')
    page(name: 'credentialsPage')
}

Map credentialsPage() {
    return dynamicPage(
        name: 'credentialsPage',
        // title: 'WeatherHub Credentials',
        nextPage: 'mainPage',
        install: false,
        uninstall: false
    ) {
        section('') {
            paragraph """WeatherHub uses only the Met Office Weather DataHub <i>Global spot Site-Specific Forecast API</i>.
Do not subscribe to Atmospheric, Map Images, Observations, or Site-Specific Blended Probabilistic Forecast.<br>
<b>Only the Global spot free subscription is required.</b><br>

1. Open <a href='https://datahub.metoffice.gov.uk/pricing/site-specific' target='_new'><b>https://datahub.metoffice.gov.uk/pricing/site-specific</b></a>.
2. Find the <b>Global spot</b> section.
3. Under <b>Free plan</b>, select <b>Register</b>.
4. Create a Weather DataHub account or sign in.
5. Complete the <b>Global spot – Free plan</b> subscription.
6. Copy your API key immediately and save it securely.
7. Paste the API key into the <b>API Token</b> field below.
<br><hr>"""
        }

        section() {
            paragraph 'After entering the API token, tap Next to open to the WeatherHub settings page.'

            input(
                name: 'apiToken',
                type: 'password',
                title: 'API Token',
                description: 'Paste the API key issued for your Global spot free subscription.',
                required: true,
                noAutoComplete: true
            )
        }

        section {
            paragraph versionFooter()
        }
    }
}

Map mainPage() {
    syncChildLabelSettingAndDevice()

    if (shouldShowCredentialsFirst()) {
        return credentialsPage()
    }

    return dynamicPage(
        name: 'mainPage',
        install: true,
        uninstall: true
    ) {
        section() {
            paragraph 'WeatherHub retrieves official Met Office Weather Site-Specific Forecast data for your Hubitat hub location and publishes the latest forecast values to a WeatherHub virtual device. The forecast location is taken automatically from the latitude and longitude configured in Settings > Hub Details.<br><br>'
            paragraph hubCoordinatesTable()
            paragraph '<br>Use Refresh on the child device for an immediate update.'
        }

        if (!state?.setupComplete) {
            section() {
                paragraph '⚠️ Setup is not complete yet. Press <b>Done</b> to create or update the WeatherHub virtual device.'
            }
        }

        section(title: 'Settings', hideable: true, hidden: false) {
            input(
                name: 'pollInterval',
                type: 'enum',
                title: 'Polling interval',
                required: true,
                options: [
                    '15': '15 minutes',
                    '30': '30 minutes',
                    '60': '1 hour',
                    '180': '3 hour',
                    '360': '6 hour',
                    '720': '12 hour'
                ],
                defaultValue: '30'
            )
        }

        section(hideable: true, hidden: false, title: 'Logging') {
            paragraph "Debug logging automatically turns off after ${getDebugAutoDisableMinutes()} minutes."

            input(
                name: 'txtEnable',
                type: 'bool',
                title: 'Enable descriptionText logging',
                defaultValue: true,
                submitOnChange: true
            )

            input(
                name: 'debugEnable',
                type: 'bool',
                title: 'Enable debug logging',
                defaultValue: false,
                submitOnChange: true
            )
        }

        section(hideable: true, hidden: true, title: 'Advanced') {
            input(
                name: 'childLabel',
                type: 'text',
                title: 'Custom Device Name',
                defaultValue: 'WeatherHub',
                submitOnChange: true
            )
        }

        section() {
            String statusText = credentialsConfigured() ? 'Saved' : 'Required'
            href(
                name: 'credentialsPageLink',
                title: 'API credentials',
                page: 'credentialsPage',
                description: statusText,
                state: credentialsConfigured() ? 'complete' : null
            )
        }

        section {
            paragraph versionFooter()
        }
    }
}

private String hubCoordinatesTable() {
    BigDecimal HUB_SETTING_LATITUDE = location?.latitude
    BigDecimal HUB_SETTING_LONGITUDE = location?.longitude

    String latitudeDisplay = HUB_SETTING_LATITUDE != null
        ? HUB_SETTING_LATITUDE.setScale(5, java.math.RoundingMode.HALF_UP).toPlainString()
        : 'Not configured'
    String longitudeDisplay = HUB_SETTING_LONGITUDE != null
        ? HUB_SETTING_LONGITUDE.setScale(5, java.math.RoundingMode.HALF_UP).toPlainString()
        : 'Not configured'

    String apiLocation = weatherDevice()?.currentValue('locationName')?.toString()?.trim()
    String locationDisplay = apiLocation ?: 'Unknown'

    String hubTemperatureScale = location?.temperatureScale?.toString()?.trim()?.toUpperCase()
    String temperatureUnitDisplay = hubTemperatureScale == 'C'
        ? 'Celsius'
        : hubTemperatureScale == 'F'
            ? 'Fahrenheit'
            : 'Unknown'

    return """
        <div style="display:flex;justify-content:center;width:100%;">
            <table style="border-collapse:collapse;text-align:center;min-width:70%;max-width:100%;">

                <tr>
                    <td style="border:1px solid #bdbdbd;padding:8px 12px;font-weight:600;">Latitude</td>
                    <td style="border:1px solid #bdbdbd;padding:8px 12px;">${htmlEncode(latitudeDisplay)}</td>
                </tr>
                <tr>
                    <td style="border:1px solid #bdbdbd;padding:8px 12px;font-weight:600;">Longitude</td>
                    <td style="border:1px solid #bdbdbd;padding:8px 12px;">${htmlEncode(longitudeDisplay)}</td>
                </tr>
                <tr>
                    <td style="border:1px solid #bdbdbd;padding:8px 12px;font-weight:600;">Location</td>
                    <td style="border:1px solid #bdbdbd;padding:8px 12px;">${htmlEncode(locationDisplay)}</td>
                </tr>
                <tr>
                    <td style="border:1px solid #bdbdbd;padding:8px 12px;font-weight:600;">Temperature Unit</td>
                    <td style="border:1px solid #bdbdbd;padding:8px 12px;">${htmlEncode(temperatureUnitDisplay)}</td>
                </tr>
            </table>
        </div>
    """.stripIndent().trim()
}

private Boolean credentialsConfigured() {
    return getApiToken() != null
}

private String getApiToken() {
    String token = settings?.apiToken?.toString()?.trim()
    return token ?: null
}

private Boolean shouldShowCredentialsFirst() {
    return !credentialsConfigured()
}

private String versionFooter() {
    return "<div style='font-size: 10px; color: #888; width: 100%; text-align: right;'>${htmlEncode(formatDisplayVersion(getVersion()))}</div>"
}

private String formatDisplayVersion(Object versionValue) {
    String version = versionValue?.toString()?.trim()
    return version ? "v${version}" : 'unknown'
}

private String htmlEncode(Object value) {
    String text = value?.toString() ?: ''
    return text
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace("'", '&#39;')
}

String getVersion() {
    return '1.3.10'
}

void installed() {
    initialize()
}

void updated() {
    initialize()
}

void uninstalled() {
    unschedule()
    clearRequestGuard()
    getChildDevices()?.each { child ->
        deleteChildDevice(child.deviceNetworkId)
    }
}

private void initialize() {
    applyDefaultLoggingSettings()
    logDebug('initialize()')

    unschedule()
    clearRequestGuard()

    if (!createChildDeviceIfMissing()) {
        state.setupComplete = false
        return
    }

    syncChildLabelSettingAndDevice()
    syncChildSettings()
    state.setupComplete = true
    scheduleDebugAutoDisableIfNeeded()

    if (!credentialsConfigured()) {
        publishError('Configuration incomplete: API token is required')
        return
    }

    schedulePolling()
    pollWeather()
}

//
//    LOGGING CONFIGURATION AND SYNCHRONISATION
//

private void applyDefaultLoggingSettings() {
    if (settings?.txtEnable == null) {
        app?.updateSetting('txtEnable', [type: 'bool', value: true])
    }

    if (settings?.debugEnable == null) {
        app?.updateSetting('debugEnable', [type: 'bool', value: false])
    }
}

def getChildDriverLoggingConfig() {
    return [
        txtEnable              : descriptionTextLoggingEnabled(),
        debugEnable            : debugLoggingEnabled(),
        debugAutoDisableSeconds: getDebugAutoDisableSeconds(),
        debugAutoDisableMinutes: getDebugAutoDisableMinutes()
    ]
}

Integer getDebugAutoDisableMinutes() {
    return (int) (getDebugAutoDisableSeconds() / 60)
}

Integer getDebugAutoDisableSeconds() {
    return DEBUG_AUTO_DISABLE_SECONDS
}

def syncChildSettings() {
    def child = weatherDevice()
    if (!child) return

    try {
        child.configure()
    } catch (Exception e) {
        logWarn("Unable to configure WeatherHub child device from app settings: ${e.message}")
    }
}

def updateLoggingFromDriver(txtEnableValue, debugEnableValue) {
    Boolean descEnabled = normalizeBoolean(txtEnableValue, true)
    Boolean debugEnabled = normalizeBoolean(debugEnableValue, false)

    app?.updateSetting('txtEnable', [value: descEnabled, type: 'bool'])
    app?.updateSetting('debugEnable', [value: debugEnabled, type: 'bool'])

    scheduleDebugAutoDisableIfNeeded()
    syncChildSettings()
}

//
//    LOGGING SCHEDULER
//

def logsOff() {
    if (!debugLoggingEnabled()) return

    app?.updateSetting('debugEnable', [value: false, type: 'bool'])

    try {
        syncChildSettings()
    } catch (Exception ignored) {
    }

    log.warn "${app.label}: Debug logging disabled automatically after ${getDebugAutoDisableMinutes()} minutes"
}

private void scheduleDebugAutoDisableIfNeeded() {
    unschedule('logsOff')

    if (debugLoggingEnabled()) {
        runIn(DEBUG_AUTO_DISABLE_SECONDS, 'logsOff')
        logDebug("Debug logging will automatically turn off in ${getDebugAutoDisableMinutes()} minutes")
    }
}

private String hubLocationSummary() {
    BigDecimal HUB_SETTING_LATITUDE = location?.latitude
    BigDecimal HUB_SETTING_LONGITUDE = location?.longitude

    if (HUB_SETTING_LATITUDE == null || HUB_SETTING_LONGITUDE == null) {
        return 'not configured'
    }

    return "${HUB_SETTING_LATITUDE}, ${HUB_SETTING_LONGITUDE}"
}

private String childDeviceNetworkId() {
    return "${app.id}-weather"
}

private Boolean createChildDeviceIfMissing() {
    String dni = childDeviceNetworkId()

    if (getChildDevice(dni)) {
        return true
    }

    String desiredLabel = normalizeLabelValue(settings?.childLabel, 'WeatherHub')

    try {
        addChildDevice(
            'vinnyw',
            'WeatherHub',
            dni,
            [
                name: 'WeatherHub Weather',
                label: desiredLabel,
                isComponent: false
            ]
        )
        return true
    } catch (Exception e) {
        state.setupComplete = false
        logWarn("Unable to create WeatherHub child device ${dni}: ${e.message}")
        return false
    }
}

private def weatherDevice() {
    return getChildDevice(childDeviceNetworkId())
}

private Boolean isExpectedChild(String dni) {
    return !dni || dni == childDeviceNetworkId()
}

private String normalizeLabelValue(Object value, String fallbackValue) {
    String normalized = value == null ? null : value.toString().trim()
    return normalized ? normalized : fallbackValue
}

private void syncChildLabelSettingAndDevice() {
    def child = weatherDevice()
    if (!child) {
        return
    }

    String defaultLabel = 'WeatherHub'
    String configuredLabel = normalizeLabelValue(settings?.childLabel, null)
    String childLabelValue = normalizeLabelValue(child.label, null)
    String lastSyncedLabel = normalizeLabelValue(state.lastSyncedChildLabel, null)

    if (!configuredLabel) {
        configuredLabel = defaultLabel
    }

    if (childLabelValue != configuredLabel) {
        Boolean childWasRenamedExternally =
            childLabelValue &&
            lastSyncedLabel &&
            childLabelValue != lastSyncedLabel &&
            configuredLabel == lastSyncedLabel

        if (childWasRenamedExternally) {
            app?.updateSetting('childLabel', [value: childLabelValue, type: 'text'])
            state.lastSyncedChildLabel = childLabelValue
            return
        }

        child.setLabel(configuredLabel)
        state.lastSyncedChildLabel = configuredLabel
        return
    }

    state.lastSyncedChildLabel = configuredLabel
}

private void schedulePolling() {
    unschedule('scheduledPoll')

    String interval = (pollInterval ?: '30').toString()
    Set<String> supportedIntervals = ['15', '30', '60', '180', '360', '720'] as Set<String>

    if (!supportedIntervals.contains(interval)) {
        interval = '30'
        app?.updateSetting('pollInterval', [type: 'enum', value: interval])
        logWarn('Unsupported polling interval was reset to 30 minute.')
    }

    switch (interval) {
        case '15':
            runEvery15Minutes('scheduledPoll')
            break
        case '30':
            runEvery30Minutes('scheduledPoll')
            break
        case '180':
            runEvery3Hours('scheduledPoll')
            break
        case '360':
            runEvery6Hours('scheduledPoll')
            break
        case '720':
            runEvery12Hours('scheduledPoll')
            break
        default:
            runEvery1Hour('scheduledPoll')
            break
    }

    logDebug("Polling schedule set to ${pollIntervalLabel(interval)}.")
}

private String pollIntervalLabel(String interval) {
    Map<String, String> labels = [
        '15' : '15 minute',
        '30' : '30 minute',
        '60' : '1 hour',
        '180': '3 hour',
        '360': '6 hour',
        '720': '12 hour'
    ]

    return labels[interval] ?: '30 minute'
}

void scheduledPoll() {
    if (!credentialsConfigured()) {
        unschedule()
        publishError('Configuration incomplete: API token is required')
        return
    }

    pollWeather()
}

void childRefresh() {
    if (!credentialsConfigured()) {
        publishError('Configuration incomplete: API token is required')
        return
    }

    pollWeather()
}

private void pollWeather() {
    if (requestIsActive()) {
        logWarn('Skipped poll because the previous request is still active.')
        return
    }

    BigDecimal HUB_SETTING_LATITUDE = location?.latitude
    BigDecimal HUB_SETTING_LONGITUDE = location?.longitude

    String apiToken = getApiToken()
    if (!apiToken) {
        publishError('Configuration incomplete: API token is required')
        return
    }

    if (HUB_SETTING_LATITUDE == null || HUB_SETTING_LONGITUDE == null) {
        publishError('Hub latitude/longitude are not configured in Settings > Hub Details')
        return
    }

    Map params = [
        uri: "${MET_OFFICE_API_PATH}/${MET_OFFICE_API_TIMESTEP}",
        headers: [
            accept: 'application/json',
            apikey: apiToken
        ],
        query: [
            excludeParameterMetadata: !MET_OFFICE_API_INCLUDE_PARAMETER_METADATA,
            includeLocationName: MET_OFFICE_API_INCLUDE_LOCATION_NAME,
            latitude: HUB_SETTING_LATITUDE,
            longitude: HUB_SETTING_LONGITUDE
        ],
        contentType: 'application/json',
        timeout: HTTP_CONNECTION_TIMEOUT_SECONDS
    ]

    state.requestInFlight = true
    state.requestStartedAt = now()

    logDebug("Requesting ${MET_OFFICE_API_TIMESTEP} forecast for ${HUB_SETTING_LATITUDE}, ${HUB_SETTING_LONGITUDE}.")

    try {
        asynchttpGet('weatherResponseHandler', params)
    } catch (Exception exception) {
        clearRequestGuard()
        publishError("Request failed: ${safeMessage(exception)}")
    }
}

void weatherResponseHandler(response, data) {
    clearRequestGuard()

    Integer httpStatus = safeInteger(response?.status)
    boolean failed = response == null ||
        response.hasError() ||
        httpStatus == null ||
        httpStatus < 200 ||
        httpStatus >= 300

    if (failed) {
        String detail = response?.getErrorMessage()
        if (!detail) {
            detail = httpStatus != null ? "HTTP ${httpStatus}" : 'No HTTP response'
        }
        publishError("API error: ${detail}")
        return
    }

    try {
        Map body = response.json as Map
        List features = body?.features instanceof List ? (List) body.features : []
        Map feature = selectLatestFeature(features)

        if (!feature) {
            publishError('No forecast feature returned')
            return
        }

        Map properties = feature.properties instanceof Map ? (Map) feature.properties : [:]
        List periods = properties.timeSeries instanceof List ? (List) properties.timeSeries : []

        if (!periods) {
            publishError('No forecast periods returned')
            return
        }

        String incomingModelRunDate = properties.modelRunDate?.toString()
        if (isOlderModelRun(incomingModelRunDate, state.lastModelRunDate?.toString())) {
            publishError("Older model run ignored: ${incomingModelRunDate}")
            return
        }

        Map period = selectCurrentPeriod(periods)
        if (!period) {
            publishError('No usable forecast period returned')
            return
        }

        Map output = normalizeForecast(period, properties)
        output.lastActivity = (long)(new Date().getTime() / 1000L)

        if (!createChildDeviceIfMissing()) {
            publishError('Unable to create WeatherHub child device')
            return
        }

        def child = weatherDevice()
        if (!child) {
            publishError('Unable to create or locate WeatherHub child device')
            return
        }

        child.updateWeather(output)

        if (incomingModelRunDate) {
            state.lastModelRunDate = incomingModelRunDate
        }

        logDebug("Published model run ${incomingModelRunDate ?: 'unknown'} for forecast ${output.forecastTime ?: 'unknown'}.")
    } catch (Exception exception) {
        publishError("Response parsing failed: ${safeMessage(exception)}")
    }
}

private boolean requestIsActive() {
    if (state.requestInFlight != true) {
        return false
    }

    Long startedAt = safeLong(state.requestStartedAt)
    if (startedAt == null || now() - startedAt > 60000L) {
        logWarn('Cleared a stale request guard.')
        clearRequestGuard()
        return false
    }

    return true
}

private void clearRequestGuard() {
    state.requestInFlight = false
    state.remove('requestStartedAt')
}

private Map selectLatestFeature(List features) {
    List<Map> candidates = features.findAll { item ->
        item instanceof Map
    }.collect { item ->
        (Map) item
    }

    if (!candidates) {
        return null
    }

    return candidates.max { Map candidate ->
        Map properties = candidate.properties instanceof Map ? (Map) candidate.properties : [:]
        Long timestamp = parseTimeMillis(properties.modelRunDate)
        return timestamp != null ? timestamp : Long.MIN_VALUE
    }
}

private boolean isOlderModelRun(String incoming, String accepted) {
    Long incomingMillis = parseTimeMillis(incoming)
    Long acceptedMillis = parseTimeMillis(accepted)

    return incomingMillis != null &&
        acceptedMillis != null &&
        incomingMillis < acceptedMillis
}

private Map selectCurrentPeriod(List periods) {
    List<Map> candidates = periods.findAll { item ->
        item instanceof Map
    }.collect { item ->
        (Map) item
    }

    if (!candidates) {
        return null
    }

    Long currentTime = now()
    Map nextPeriod = candidates.find { Map period ->
        Long periodTime = parseTimeMillis(period.time)
        return periodTime != null && periodTime >= currentTime
    }

    return nextPeriod ?: candidates.last()
}

private Long parseTimeMillis(def value) {
    if (!value) {
        return null
    }

    String timestamp = value.toString()
    List<String> patterns = [
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm'Z'"
    ]

    for (String pattern : patterns) {
        try {
            return Date.parse(pattern, timestamp).time
        } catch (Exception ignored) {
        // Try the next supported API timestamp format.
        }
    }

    return null
}

private Map normalizeForecast(Map period, Map properties) {
    Map output = [:]

    output.forecastTime = formatHubDateTime(firstValue(period, ['time']))
    output.locationName = firstValue(properties, ['locationName', 'name'])

    if (!output.locationName && properties.location instanceof Map) {
        output.locationName = firstValue((Map) properties.location, ['name', 'locationName'])
    }

    output.temperature = firstValue(period, ['screenTemperature', 'temperature', 'temperature2m'])
    output.feelsLike = firstValue(period, ['feelsLikeTemperature', 'feelsLike'])
    output.dewPoint = firstValue(period, ['screenDewPointTemperature', 'dewPointTemperature', 'dewPoint'])
    output.humidity = firstValue(period, ['screenRelativeHumidity', 'relativeHumidity', 'humidity'])
    output.pressure = firstValue(period, ['mslp', 'meanSeaLevelPressure', 'pressure'])
    output.visibility = firstValue(period, ['visibility'])
    output.windSpeed = firstValue(period, ['windSpeed10m', 'windSpeed'])
    output.windGust = firstValue(period, ['windGustSpeed10m', 'windGustSpeed', 'windGust'])
    output.windDirection = firstValue(period, ['windDirectionFrom10m', 'windDirection'])
    output.windDirectionCardinal = windDirectionToCardinal(output.windDirection)
    output.uvIndex = firstValue(period, ['uvIndex'])
    output.uvLevel = uvIndexToLevel(output.uvIndex)
    output.precipitationRate = firstValue(period, ['precipitationRate'])
    output.precipitationAmount = firstValue(period, ['totalPrecipAmount', 'precipitationAmount'])
    output.snowAmount = firstValue(period, ['totalSnowAmount', 'snowAmount'])
    output.precipitationProbability = firstValue(period, ['probOfPrecipitation', 'probabilityOfPrecipitation', 'precipitationProbability'])
    output.weatherCode = firstValue(period, ['significantWeatherCode', 'weatherCode'])
    output.weatherCondition = weatherCodeDescription(output.weatherCode)
    output.summary = buildSummary(output)

    return output.findAll { key, value ->
        value != null
    }
}

private String windDirectionToCardinal(def degreesValue) {
    BigDecimal degrees = safeDecimal(degreesValue)
    if (degrees == null) {
        return 'Unknown'
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
        return 'Unknown'
    }

    if (uv <= 2G) return 'Low'
    if (uv <= 5G) return 'Moderate'
    if (uv <= 7G) return 'High'
    if (uv <= 10G) return 'Very High'
    return 'Extreme'
}

private def firstValue(Map source, List<String> keys) {
    for (String key : keys) {
        if (source?.containsKey(key) && source[key] != null) {
            return source[key]
        }
    }

    return null
}

private String buildSummary(Map weather) {
    List<String> parts = []

    if (weather.weatherCondition) {
        parts << weather.weatherCondition.toString()
    }
    if (weather.temperature != null) {
        parts << "${weather.temperature}°C"
    }
    if (weather.precipitationProbability != null) {
        parts << "${weather.precipitationProbability}% precipitation"
    }
    if (weather.windSpeed != null) {
        String direction = weather.windDirectionCardinal ? "${weather.windDirectionCardinal} " : ''
        parts << "wind ${direction}${weather.windSpeed}m/s"
    }

    return parts.join(', ')
}

private String weatherCodeDescription(def codeValue) {
    Integer code = safeInteger(codeValue)
    if (code == null) {
        return 'not available'
    }

    Map<Integer, String> descriptions = [
        0: 'clear night',
        1: 'sunny day',
        2: 'partly cloudy night',
        3: 'partly cloudy day',
        4: 'not used',
        5: 'mist',
        6: 'fog',
        7: 'cloudy',
        8: 'overcast',
        9: 'light rain shower night',
        10: 'light rain shower day',
        11: 'drizzle',
        12: 'light rain',
        13: 'heavy rain shower night',
        14: 'heavy rain shower day',
        15: 'heavy rain',
        16: 'sleet shower night',
        17: 'sleet shower day',
        18: 'sleet',
        19: 'hail shower night',
        20: 'hail shower day',
        21: 'hail',
        22: 'light snow shower night',
        23: 'light snow shower day',
        24: 'light snow',
        25: 'heavy snow shower night',
        26: 'heavy snow shower day',
        27: 'heavy snow',
        28: 'thunder shower night',
        29: 'thunder shower day',
        30: 'thunder'
    ]

    return descriptions.containsKey(code) ?
        descriptions[code] :
        'not available'
}

private void publishError(String message) {
    log.error "WeatherHub: ${message}"
}

/**
 * Formats timestamps explicitly with the hub time zone and 12/24-hour setting.
 * The Hubitat time-format value is normalized to String before comparison,
 * avoiding unsafe String-to-Integer casts in undocumented helper methods.
 */
private String formatHubDateTime(def value) {
    Date dateValue = null

    if (value instanceof Date) {
        dateValue = (Date) value
    } else if (value != null) {
        Long millis = parseTimeMillis(value)
        if (millis != null) {
            dateValue = new Date(millis)
        }
    }

    if (dateValue == null) {
        return value?.toString()
    }

    TimeZone zone = location?.timeZone ?: TimeZone.getTimeZone('UTC')
    String datePattern = hubDatePattern()
    String timePattern = hubClockDisplayPattern()

    try {
        return dateValue.format("${datePattern} ${timePattern}", zone)
    } catch (Throwable error) {
        log.warn 'WeatherHub: Hub datetime format was invalid; using ISO-style fallback'
        return dateValue.format('yyyy-MM-dd HH:mm:ss', zone)
    }
}

private String hubClockDisplayPattern() {
    String configured = null

    try {
        def rawFormat = location?.getTimeFormat()
        configured = rawFormat == null ? null : rawFormat.toString().trim()
    } catch (Throwable ignored) {
        configured = null
    }

    return configured == '12' ? 'h:mm:ss a' : 'HH:mm:ss'
}

private String hubDatePattern() {
    try {
        def configured = location?.dateFormat
        String pattern = configured == null ? null : configured.toString().trim()
        if (pattern) {
            return pattern
        }
    } catch (Throwable ignored) {
    // Hubitat does not officially document a date-format property.
    }

    return 'yyyy-MM-dd'
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

private Integer safeInteger(def value) {
    if (value == null) {
        return null
    }

    try {
        return value as Integer
    } catch (Exception ignored) {
        return null
    }
}

private Long safeLong(def value) {
    if (value == null) {
        return null
    }

    try {
        return value as Long
    } catch (Exception ignored) {
        return null
    }
}

private String safeMessage(Exception exception) {
    return exception?.message ?: exception?.class?.simpleName ?: 'Unknown error'
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

private void logDebug(String message) {
    if (debugLoggingEnabled()) log.debug "${app.label}: ${message}"
}

private void logText(String message) {
    if (descriptionTextLoggingEnabled()) log.info "${app.label}: ${message}"
}

private void logWarn(String message) {
    log.warn "${app.label}: ${message}"
}

private Boolean normalizeBoolean(value, Boolean defaultValue) {
    if (value == null) return defaultValue
    if (value instanceof Boolean) return value
    String s = value.toString().trim().toLowerCase()
    if (s == 'true') return true
    if (s == 'false') return false
    return defaultValue
}

String uvLevelFromIndex(def uv) {
    if (uv == null) {
        return 'Unknown'
    }

    BigDecimal normalizedUv
    try {
        normalizedUv = uv as BigDecimal
    } catch (Exception ignored) {
        return 'Unknown'
    }

    if (normalizedUv <= 2) {
        return 'Low'
    }
    if (normalizedUv <= 5) {
        return 'Moderate'
    }
    if (normalizedUv <= 7) {
        return 'High'
    }
    if (normalizedUv <= 10) {
        return 'Very High'
    }

    return 'Extreme'
}
