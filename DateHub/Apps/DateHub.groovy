/**
 *  --------------------------------------------------------------------------------------------------------------
 *  DateHub Manager
 *  --------------------------------------------------------------------------------------------------------------
 *
 *  Author      : Vinny Wadding
 *  Namespace   : vinnyw
 *  Version     : 1.3.28
 *  Date        : 2026-07-16
 *
 *  Description :
 *      Parent application for DateHub.
 *
 *      Responsibilities:
 *          - Creates and manages one DateHub child device
 *          - Fetches and caches GOV.UK bank holiday data
 *          - Publishes selected holiday, calendar, seasonal, daylight-saving, and moon-phase fields
 *          - Validates hub timezone compatibility before publishing UK-specific values
 *          - Refreshes once per day at the configured time
 *
 *      Integration:
 *          Source holiday data is retrieved from the public GOV.UK bank holidays JSON endpoint.
 *
 *  --------------------------------------------------------------------------------------------------------------
 */

definition(
    name: 'DateHub Manager',
    namespace: 'vinnyw',
    author: 'Vinny Wadding',
    description: 'Fetches GOV.UK bank holidays, validates UK-compatible hub timezone, caches selected regions, and exposes calculated holiday, leap-year/leap-day, moon-phase, blue-moon, next-new/full-moon, next-new/full-moon, next moon phase date, and UK daylight-saving fields through a child device.',
    category: 'Convenience',
    importUrl: 'https://raw.githubusercontent.com/vinnyw/hubitat/master/DateHub/Apps/DateHub.groovy',
    documentationLink: 'https://github.com/vinnyw/hubitat/blob/master/README.md',
    iconUrl: 'https://raw.githubusercontent.com/hubitat/HubitatPublic/master/resources/images/App%20Icons/Convenience.png',
    iconX2Url: 'https://raw.githubusercontent.com/hubitat/HubitatPublic/master/resources/images/App%20Icons/Convenience.png',
    iconX3Url: 'https://raw.githubusercontent.com/hubitat/HubitatPublic/master/resources/images/App%20Icons/Convenience.png',
    singleInstance: true,
    installOnOpen: true
)

import groovy.transform.Field

@Field static final Integer DEBUG_AUTO_DISABLE_SECONDS = 1800
@Field static final String DEFAULT_HTTP_JSON = 'https://www.gov.uk/bank-holidays.json'
@Field static final Integer DEFAULT_HTTP_TIMEOUT_SECONDS = 5

//
//    APP CONFIGURATION
//

preferences {
    page(name: 'mainPage', install: true, uninstall: true) {
        syncChildLabelSettingAndDevice()

        section('Hub Locale / Timezone') {
            paragraph 'DateHub uses UK-specific holiday and daylight-saving data. For the app to work correctly, set the hub location to the United Kingdom and use a UK-compatible timezone.'
            paragraph localeCompatibilityTable()
        }

        if (!state?.setupComplete) {
            section() {
                paragraph '⚠️ Setup is not complete yet. Press <b>Done</b> to create or update the DateHub virtual device.'
            }
        }

        section(title: 'Settings', hideable: true, hidden: false) {
            input name: 'Regions',
                  type: 'enum',
                  title: 'Holiday Regions',
                  options: [
                      'england-and-wales': 'England and Wales',
                      'scotland': 'Scotland',
                      'northern-ireland': 'Northern Ireland'
                  ],
                  multiple: true,
                  required: true,
                  defaultValue: ['england-and-wales']

                input name: 'refreshTime',
                    type: 'time',
                    title: 'Schedule refresh ',
                    required: true,
                    defaultValue: '02:10'
        }

        section(hideable: true, hidden: false, title: 'Logging') {
            paragraph "Debug logging automatically turns off after ${getDebugAutoDisableMinutes()} minutes."

            input name: 'txtEnable', type: 'bool',
                  title: 'Enable descriptionText logging',
                  defaultValue: true,
                  submitOnChange: true

            input name: 'debugEnable', type: 'bool',
                  title: 'Enable debug logging',
                  defaultValue: false,
                  submitOnChange: true
        }

        section(hideable: true, hidden: true, title: 'Advanced') {
            input name: 'childLabel',
                    type: 'text',
                    title: 'Custom Device Name',
                    defaultValue: 'DateHub',
                    submitOnChange: true
        }

        section() {
            String versionLabel = getDisplayVersionValue(getVersion())
            paragraph "<div style='font-size: 10px; color: #888; width: 100%; text-align: right;'>${htmlEncode(versionLabel)}</div>"
        }
    }
}

//
//    VERSION AND DISPLAY HELPERS
//

private String getDisplayVersionValue(Object versionValue) {
    String version = versionValue?.toString()?.trim()
    return version ? "v${version}" : 'unknown'
}

def getVersion() {
    return '1.3.28'
}

private String htmlEncode(Object value) {
    String s = value?.toString() ?: ''
    return s
        .replace('&', '&amp;')
        .replace('<', '&lt;')
        .replace('>', '&gt;')
        .replace('"', '&quot;')
        .replace("'", '&#39;')
}

//
//    APPLICATION LIFECYCLE
//

def initialize() {
    applyDefaultLoggingSettings()
    logDebug('initialize()')

    unschedule()

    if (!createChildDeviceIfMissing()) {
        state.setupComplete = false
        return
    }

    syncChildLabelSettingAndDevice()
    syncChildSettings()
    state.setupComplete = true

    if (!checkCompatibleLocale()) {
        unschedule()
        publishEmptyValues('Locale error')
        return
    }

    if (refreshTime) {
        schedule(refreshTime, 'scheduledRefresh')
        logDebug("Scheduled daily refresh at ${refreshTime}")
    }

    scheduleDebugAutoDisableIfNeeded()

    if (!state.cachedHolidays) {
        fetchAndPublish()
    } else {
        publishCachedValues()
    }
}

def installed() {
    initialize()
}

def scheduledRefresh() {
    if (!checkCompatibleLocale()) {
        publishEmptyValues('Locale error')
        return
    }

    fetchAndPublish()
}

def uninstalled() {
    unschedule()
}

def updated() {
    applyDefaultLoggingSettings()
    syncChildSettings()
    initialize()
}

//
//    CHILD DEVICE COMMANDS
//

def deviceClearCache(String dni = null) {
    if (!isExpectedChild(dni)) {
        logWarn("Ignoring clear-cache request from unknown child device: ${dni}")
        return
    }

    logDebug('deviceClearCache()')

    state.remove('cachedHolidays')
    state.selectedRegions = normalizedRegions()
    state.lastSuccessfulFetchMs = null
    state.lastUpdatedMs = now()
    state.lastError = ''
    state.cacheStatus = 'Cleared'

    publishEmptyValues('Cleared')
    logText('Holiday cache cleared')
}

def deviceConfigure(String dni = null) {
    if (!isExpectedChild(dni)) {
        logWarn("Ignoring configure request from unknown child device: ${dni}")
        return
    }

    if (!createChildDeviceIfMissing()) {
        state.setupComplete = false
        return
    }

    syncChildLabelSettingAndDevice()
    state.setupComplete = true

    if (!checkCompatibleLocale()) {
        publishEmptyValues('Locale error')
        return
    }

    if (state.cachedHolidays) {
        publishCachedValues()
    } else {
        publishEmptyValues(state.cacheStatus ?: 'Not loaded')
    }
}

def deviceRefresh(String dni = null) {
    if (!isExpectedChild(dni)) {
        logWarn("Ignoring refresh request from unknown child device: ${dni}")
        return
    }

    if (!checkCompatibleLocale()) {
        publishEmptyValues('Locale error')
        return
    }

    fetchAndPublish()
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
    def child = holidayDevice()
    if (!child) return

    try {
        child.configure()
    } catch (Exception e) {
        logWarn("Unable to configure DateHub child device from app settings: ${e.message}")
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

//
//    LOCALE AND TIMEZONE VALIDATION
//

private Boolean checkCompatibleLocale(Boolean logWarning = true) {
    String tzId = hubTimeZoneId()
    Boolean compatible = isUkCompatibleTimeZone(tzId)
    String warning = "Hub timezone '${tzId}' is not UK-compatible. Set hub timezone to Europe/London, Europe/Jersey, Europe/Guernsey, or Europe/Isle_of_Man before relying on UK holiday/BST dates."

    state.hubTimeZone = tzId
    state.localeStatus = compatible ? 'Compatible' : 'Incompatible'
    state.localeWarning = compatible ? '' : warning

    if (!compatible) {
        state.lastError = warning
        if (logWarning) {
            logWarn(warning)
        }
    } else if (state.cacheStatus == 'Locale error') {
        state.lastError = ''
        state.cacheStatus = 'Not loaded'
    }

    return compatible
}

private String hubTimeZoneId() {
    return location?.timeZone?.ID ?: 'Unknown'
}

private Boolean isUkCompatibleTimeZone(String tzId) {
    return ukCompatibleTimeZones().contains(tzId)
}

private String localeCompatibilityMessage() {
    checkCompatibleLocale(false)
    String tzId = hubTimeZoneId()
    if (isUkCompatibleTimeZone(tzId)) {
        return "Hub timezone '${tzId}' is UK-compatible."
    }

    return "WARNING: Hub timezone '${tzId}' is not UK-compatible. Supported UK-compatible timezones: ${ukCompatibleTimeZones().join(', ')}."
}

private String localeCompatibilityStatus() {
    return isUkCompatibleTimeZone(hubTimeZoneId()) ? 'Compatible' : 'Incompatible'
}

private String localeCompatibilityTable() {
    checkCompatibleLocale(false)

    String tzId = hubTimeZoneId()
    Boolean compatible = isUkCompatibleTimeZone(tzId)
    String status = compatible ? 'Compatible' : 'Incompatible'
    String message = compatible
        ? "Hub timezone '${tzId}' is UK-compatible."
        : "Hub timezone '${tzId}' is not UK-compatible. Supported timezones: ${ukCompatibleTimeZones().join(', ')}."
    String statusColour = compatible ? '#228B22' : '#C62828'

    return """
        <div style="display:flex;justify-content:center;width:100%;">
            <table style="border-collapse:collapse;text-align:center;min-width:70%;max-width:100%;">
                <tr>
                    <th style="border:1px solid #bdbdbd;padding:8px 12px;">Check</th>
                    <th style="border:1px solid #bdbdbd;padding:8px 12px;">Result</th>
                </tr>
                <tr>
                    <td style="border:1px solid #bdbdbd;padding:8px 12px;font-weight:600;">Hub timezone</td>
                    <td style="border:1px solid #bdbdbd;padding:8px 12px;">${htmlEncode(tzId)}</td>
                </tr>
                <tr>
                    <td style="border:1px solid #bdbdbd;padding:8px 12px;font-weight:600;">Compatibility</td>
                    <td style="border:1px solid #bdbdbd;padding:8px 12px;color:${statusColour};font-weight:700;">${status}</td>
                </tr>
            </table>
        </div>
    """.stripIndent().trim()
}

private List<String> ukCompatibleTimeZones() {
    return ['Europe/London', 'Europe/Jersey', 'Europe/Guernsey', 'Europe/Isle_of_Man']
}

//
//    CHILD DEVICE MANAGEMENT
//

private String childDni() {
    return "datehub-${app.id}"
}

private Boolean createChildDeviceIfMissing() {
    String dni = childDni()

    if (getChildDevice(dni)) {
        return true
    }

    String desiredLabel = normalizeLabelValue(settings?.childLabel, 'DateHub')

    try {
        addChildDevice(
            'vinnyw',
            'DateHub-1.3',
            dni,
            [
                name: 'DateHub-1.3',
                label: desiredLabel,
                isComponent: false
            ]
        )
        return true
    } catch (Exception e) {
        state.setupComplete = false
        logWarn("Unable to create DateHub child device ${dni}: ${e.message}")
        return false
    }
}

private def holidayDevice() {
    return getChildDevice(childDni())
}

private Boolean isExpectedChild(String dni) {
    return !dni || dni == childDni()
}

private String normalizeLabelValue(Object value, String fallbackValue) {
    String normalized = value == null ? null : value.toString().trim()
    return normalized ? normalized : fallbackValue
}

private void syncChildLabelSettingAndDevice() {
    def child = holidayDevice()
    if (!child) {
        return
    }

    String defaultLabel = 'DateHub'
    String configuredLabel = normalizeLabelValue(settings?.childLabel, null)
    String childLabelValue = normalizeLabelValue(child.label, null)
    String lastSyncedLabel = normalizeLabelValue(state.lastSyncedChildLabel, null)

    if (!configuredLabel) {
        configuredLabel = defaultLabel
    }

    if (childLabelValue != configuredLabel) {
        Boolean childWasRenamedExternally = childLabelValue && lastSyncedLabel && childLabelValue != lastSyncedLabel && configuredLabel == lastSyncedLabel

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

//
//    DATA RETRIEVAL AND PUBLISHING
//

private void fetchAndPublish() {
    logDebug('fetchAndPublish()')

    if (!checkCompatibleLocale()) {
        publishEmptyValues('Locale error')
        return
    }

    try {
        httpGet([
            uri: DEFAULT_HTTP_JSON,
            contentType: 'application/json',
            timeout: DEFAULT_HTTP_TIMEOUT_SECONDS
        ]) { resp ->
            if (resp.status != 200) {
                recordError("HTTP status ${resp.status}")
                return
            }

            if (!resp.data) {
                recordError('Empty response data')
                return
            }

            List selectedRegions = normalizedRegions()
            Map filtered = [:]

            selectedRegions.each { String region ->
                def regionPayload = resp.data[region]
                if (regionPayload?.events instanceof List) {
                    filtered[region] = regionPayload.events.collect { event ->
                        [
                            title  : safeString(event.title),
                            date   : safeString(event.date),
                            notes  : safeString(event.notes),
                            bunting: event.bunting == true
                        ]
                    }.findAll { it.date }
                } else {
                    filtered[region] = []
                }
            }

            state.cachedHolidays = filtered
            state.selectedRegions = selectedRegions
            state.lastSuccessfulFetchMs = now()
            state.lastError = ''
            state.cacheStatus = 'Loaded'

            logText("Fetched and cached GOV.UK holiday data for ${selectedRegions.size()} region(s)")
            publishCachedValues()
        }
    } catch (Exception e) {
        recordError(e.message ?: e.toString())
    }
}

void publishCachedValues() {
    def child = holidayDevice()
    if (!child) {
        logWarn('Child device is missing; cannot publish values.')
        return
    }

    Map cache = state.cachedHolidays ?: [:]
    List selectedRegions = state.selectedRegions ?: normalizedRegions()

    String today = new Date().format('yyyy-MM-dd', location.timeZone)
    List allEvents = flattenEvents(cache, selectedRegions)
    List todaysEvents = allEvents.findAll { it.date == today }
    List futureEvents = allEvents.findAll { it.date >= today }
                                  .sort { a, b -> a.date <=> b.date ?: a.region <=> b.region ?: a.title <=> b.title }

    Map nextEvent = futureEvents ? futureEvents[0] : null
    state.lastUpdatedMs = now()

    state.selectedRegions = selectedRegions
    state.totalCachedEvents = allEvents.size()

    Map values = [
        driverVersion                 : getVersion(),

        isPublicHoliday             : todaysEvents ? 'true' : 'false',
        publicHolidayName         : todaysEvents ? uniqueJoin(todaysEvents.collect { it.title }) : null,

        nextPublicHolidayName          : toTitleCase(nextEvent?.title ?: ''),
        nextPublicHolidayDate          : nextEvent?.date ? formatHubDate(nextEvent.date) : '',
        daysUntilNextPublicHoliday     : nextEvent?.date ? daysUntil(nextEvent.date) : null,

        lastError                : state.lastError ?: ''
    ]

    values.putAll(daylightSavingValues())
    values.putAll(calendarValues())
    values.putAll(leapYearValues())
    values.putAll(moonPhaseValues())
    publishChangedValuesToChild(child, values)
}

private void publishEmptyValues(String status) {
    def child = holidayDevice()
    if (!child) {
        return
    }

    state.lastUpdatedMs = now()

    state.cacheStatus = status
    state.selectedRegions = normalizedRegions()
    state.totalCachedEvents = 0

    Map values = [
        driverVersion                 : getVersion(),

        isPublicHoliday                : 'false',
        publicHolidayName              : null,

        nextPublicHolidayName          : null,
        nextPublicHolidayDate          : null,
        daysUntilNextPublicHoliday     : null,

        lastError                : state.lastError ?: null
    ]

    values.putAll(daylightSavingValues())
    values.putAll(calendarValues())
    values.putAll(leapYearValues())
    values.putAll(moonPhaseValues())
    publishChangedValuesToChild(child, values)
}

private void publishChangedValuesToChild(child, Map values) {
    if (!child || !(values instanceof Map) || values.isEmpty()) {
        return
    }

    Map attributeCache = state.publishedAttributeValueCache instanceof Map
        ? state.publishedAttributeValueCache
        : [:]
    Map changedValues = [:]

    values.each { String name, value ->
        String newCacheValue = normalizePublishedAttributeValue(value)

        if (!attributeCache.containsKey(name)) {
            attributeCache[name] = normalizePublishedAttributeValue(child.currentValue(name))
        }

        if (attributeCache[name] != newCacheValue) {
            changedValues[name] = value
            attributeCache[name] = newCacheValue
        } else {
            logDebug("Skipping unchanged attribute ${name}=${value}")
        }
    }

    state.publishedAttributeValueCache = attributeCache

    if (!changedValues.isEmpty()) {
        child.updateFromParent(changedValues)
    }
}

private String normalizePublishedAttributeValue(Object value) {
    if (value == null) return '__DATEHUB_NULL__'

    if (value instanceof Number) {
        try {
            return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        } catch (Exception ignored) {
            return value.toString()
        }
    }

    if (value instanceof Boolean) {
        return value.toString().toLowerCase()
    }

    return value.toString()
}

private void recordError(String message) {
    String cleanMessage = message ?: 'Unknown error'

    logWarn("DateHub Connector error: ${cleanMessage}")

    state.lastError = cleanMessage
    state.cacheStatus = 'Error'
    state.lastUpdatedMs = now()

    def child = holidayDevice()
    if (child) {
        publishChangedValuesToChild(child, [
            lastError: cleanMessage
        ])
    }
}

//
//    MOON PHASE CALCULATIONS
//

private String addDaysIso(Date startDate, Integer offset, TimeZone hubZone) {
    Calendar calendar = Calendar.getInstance(hubZone)
    calendar.setTime(startDate)
    calendar.add(Calendar.DATE, offset ?: 0)
    return calendar.getTime().format('yyyy-MM-dd', hubZone)
}

private List<String> blueMoonDates(Integer startYear, Integer endYear, TimeZone hubZone) {
    Map<String, List<String>> fullMoonsByMonth = [:].withDefault { [] }

    fullMoonIsoDates(startYear, endYear, hubZone).each { String isoDate ->
        String monthKey = isoDate.substring(0, 7)
        fullMoonsByMonth[monthKey] << isoDate
    }

    List<String> blueMoons = []
    fullMoonsByMonth.keySet().sort().each { String monthKey ->
        List<String> monthFullMoons = fullMoonsByMonth[monthKey].sort().unique()
        if (monthFullMoons.size() >= 2) {
            blueMoons << monthFullMoons[1]
        }
    }

    return blueMoons.sort().unique()
}

private Map blueMoonValues(String todayIso, TimeZone hubZone) {
    String nextBlueMoonIso = nextBlueMoonIso(todayIso, hubZone)

    return [
        isBlueMoon             : nextBlueMoonIso == todayIso,
        daysUntilNextBlueMoon : nextBlueMoonIso ? daysUntil(nextBlueMoonIso) : null,
        nextBlueMoon          : nextBlueMoonIso ? formatHubDate(nextBlueMoonIso) : ''
    ]
}

private Date dateFromJulianDay(Double julianDay) {
    Long millis = Math.round((julianDay - 2440587.5D) * 86400000.0D)
    return new Date(millis)
}

private Integer daysUntilNextPhase(String todayIso, Integer targetPhaseIndex, TimeZone hubZone) {
    String matchingIso = nextMoonPhaseIso(todayIso, targetPhaseIndex, true, hubZone)
    return matchingIso ? daysUntil(matchingIso) : null
}

private List<String> fullMoonIsoDates(Integer startYear, Integer endYear, TimeZone hubZone) {
    Double synodicMonth = 29.530588853D
    Double knownNewMoonJulianDay = 2451550.1D
    Double knownFullMoonJulianDay = knownNewMoonJulianDay + (synodicMonth / 2.0D)

    Double startJulian = julianDayAtLocalNoon(startYear, 1, 1) - 2.0D
    Double endJulian = julianDayAtLocalNoon(endYear, 12, 31) + 2.0D

    Integer firstCycle = Math.floor((startJulian - knownFullMoonJulianDay) / synodicMonth).toInteger() - 1
    Integer lastCycle = Math.ceil((endJulian - knownFullMoonJulianDay) / synodicMonth).toInteger() + 1

    List<String> dates = []
    (firstCycle..lastCycle).each { Integer cycle ->
        Double fullMoonJulian = knownFullMoonJulianDay + (cycle * synodicMonth)
        if (fullMoonJulian >= startJulian && fullMoonJulian <= endJulian) {
            String isoDate = dateFromJulianDay(fullMoonJulian).format('yyyy-MM-dd', hubZone)
            Integer isoYear = isoDate.substring(0, 4).toInteger()
            if (isoYear >= startYear && isoYear <= endYear) {
                dates << isoDate
            }
        }
    }

    return dates.sort().unique()
}

private Double julianDayAtLocalNoon(Integer year, Integer month, Integer day) {
    Integer adjustedYear = year
    Integer adjustedMonth = month

    if (adjustedMonth <= 2) {
        adjustedYear--
        adjustedMonth += 12
    }

    Integer century = Math.floor(adjustedYear / 100.0D) as Integer
    Integer correction = 2 - century + (Math.floor(century / 4.0D) as Integer)

    return Math.floor(365.25D * (adjustedYear + 4716)) +
           Math.floor(30.6001D * (adjustedMonth + 1)) +
           day + correction - 1524.0D
}

private Integer moonPhaseIndex(String isoDate) {
    List parts = isoDate.tokenize('-')*.toInteger()
    Double julianDay = julianDayAtLocalNoon(parts[0] as Integer, parts[1] as Integer, parts[2] as Integer)
    Double synodicMonth = 29.530588853D
    Double knownNewMoonJulianDay = 2451550.1D
    Double age = positiveModulo(julianDay - knownNewMoonJulianDay, synodicMonth)
    return (Math.floor((age / synodicMonth) * 8.0D + 0.5D) as Integer) % 8
}

private String moonPhaseName(Integer phaseIndex) {
    List<String> phases = [
        'New Moon',
        'Waxing Crescent',
        'First Quarter',
        'Waxing Gibbous',
        'Full Moon',
        'Waning Gibbous',
        'Last Quarter',
        'Waning Crescent'
    ]

    return phases[phaseIndex ?: 0]
}

private Map moonPhaseValues() {
    TimeZone hubZone = location?.timeZone ?: TimeZone.getTimeZone('UTC')
    Date nowValue = new Date()
    String todayIso = nowValue.format('yyyy-MM-dd', hubZone)
    Integer phaseIndex = moonPhaseIndex(todayIso)
    String nextPhaseIso = nextDifferentMoonPhaseIso(todayIso, phaseIndex, hubZone)
    Integer nextPhaseIndex = nextPhaseIso ? moonPhaseIndex(nextPhaseIso) : ((phaseIndex + 1) % 8)

    Map values = [
        moonPhase               : moonPhaseName(phaseIndex),
        nextMoonPhaseName       : moonPhaseName(nextPhaseIndex),
        nextMoonPhaseDate       : nextPhaseIso ? formatHubDate(nextPhaseIso) : null,
        isNewMoon               : phaseIndex == 0,
        isFullMoon              : phaseIndex == 4,
        daysUntilNextNewMoon    : daysUntilNextPhase(todayIso, 0, hubZone),
        daysUntilNextFullMoon   : daysUntilNextPhase(todayIso, 4, hubZone),
        daysUntilNextMoonPhase  : nextPhaseIso ? daysUntil(nextPhaseIso) : null
    ]
    values.putAll(blueMoonValues(todayIso, hubZone))
    return values
}

private String nextBlueMoonIso(String todayIso, TimeZone hubZone) {
    Integer currentYear = todayIso.substring(0, 4).toInteger()
    List<String> blueMoonDates = blueMoonDates(currentYear - 1, currentYear + 10, hubZone)
    return blueMoonDates.find { String isoDate -> isoDate >= todayIso } ?: ''
}

private String nextDifferentMoonPhaseIso(String todayIso, Integer currentPhaseIndex, TimeZone hubZone) {
    Date startDate = Date.parse('yyyy-MM-dd', todayIso)

    for (Integer offset = 1; offset <= 15; offset++) {
        String candidateIso = addDaysIso(startDate, offset, hubZone)
        if (moonPhaseIndex(candidateIso) != currentPhaseIndex) {
            return candidateIso
        }
    }

    return ''
}

private String nextMoonPhaseIso(String todayIso, Integer targetPhaseIndex, Boolean includeToday, TimeZone hubZone) {
    Date startDate = Date.parse('yyyy-MM-dd', todayIso)
    Integer startOffset = includeToday ? 0 : 1

    for (Integer offset = startOffset; offset <= 60; offset++) {
        String candidateIso = addDaysIso(startDate, offset, hubZone)
        if (moonPhaseIndex(candidateIso) == targetPhaseIndex) {
            return candidateIso
        }
    }

    return ''
}

private Double positiveModulo(Double value, Double divisor) {
    Double result = value % divisor
    return result < 0 ? result + divisor : result
}

//
//    LEAP YEAR CALCULATIONS
//

private Boolean isLeapYearNumber(Integer year) {
    return ((year % 4 == 0) && (year % 100 != 0)) || (year % 400 == 0)
}

private Map leapYearValues() {
    TimeZone hubZone = daylightSavingTimeZone()
    Date nowValue = new Date()
    String todayIso = nowValue.format('yyyy-MM-dd', hubZone)
    List todayParts = todayIso.tokenize('-')*.toInteger()

    Integer currentYear = todayParts[0]
    Boolean currentYearIsLeap = isLeapYearNumber(currentYear)
    Boolean todayIsLeapDay = todayParts[1] == 2 && todayParts[2] == 29

    Integer nextLeapYear = nextLeapYearForDate(currentYear, todayIso)
    String nextLeapYearStartIso = String.format('%04d-01-01', nextLeapYear)
    String nextLeapDayIso = String.format('%04d-02-29', nextLeapYear)
    Integer daysUntilLeapYear = (nextLeapYear == currentYear) ? 0 : daysUntil(nextLeapYearStartIso)

    return [
        isLeapYear              : currentYearIsLeap,
        daysUntilNextLeapYear  : daysUntilLeapYear,
        nextLeapYear           : nextLeapYear,
        nextLeapDay            : formatHubDate(nextLeapDayIso),
        daysUntilNextLeapDay   : daysUntil(nextLeapDayIso),
        isLeapDay              : todayIsLeapDay
    ]
}

private Integer nextLeapYearForDate(Integer currentYear, String todayIso) {
    Integer year = currentYear

    while (!isLeapYearNumber(year) || todayIso > String.format('%04d-02-29', year)) {
        year++
    }

    return year
}

//
//    CALENDAR AND SEASON CALCULATIONS
//

private Map calendarValues() {
    TimeZone ukZone = daylightSavingTimeZone()
    Date nowValue = new Date()
    Integer currentYear = nowValue.format('yyyy', ukZone).toInteger()

    List seasonChanges = []
    [currentYear - 1, currentYear, currentYear + 1].each { Integer yr ->
        seasonChanges.addAll(seasonChangeEvents(yr, ukZone))
    }
    seasonChanges = seasonChanges.sort { it.instant.time }

    Map currentSeasonEvent = seasonChanges.findAll { Map event -> !nowValue.before(event.instant) }.last()
    Map nextSeasonEvent = seasonChanges.find { Map event -> nowValue.before(event.instant) }

    String easterIso = nextEasterIso(currentYear, ukZone, nowValue)
    String halloweenIso = nextHalloweenIso(currentYear, ukZone, nowValue)
    Boolean halloweenToday = isIsoToday(halloweenIso)

    return [
        season                        : toTitleCase(currentSeasonEvent?.season ?: ''),
        nextSeasonName          : toTitleCase(nextSeasonEvent?.name ?: ''),
        nextSeasonDate          : nextSeasonEvent?.date ? formatHubDate(nextSeasonEvent.date) : '',
        daysUntilNextSeason     : nextSeasonEvent?.date ? daysUntil(nextSeasonEvent.date) : null,
        nextEasterDate                : easterIso ? formatHubDate(easterIso) : '',
        isEaster                     : easterIso ? isIsoToday(easterIso) : false,
        daysUntilNextEaster               : easterIso ? daysUntil(easterIso) : null,
        daysUntilNextHalloween        : halloweenIso ? daysUntil(halloweenIso) : null,
        isHalloween                   : halloweenToday,
        nextHalloweenDate             : halloweenIso ? formatHubDate(halloweenIso) : ''
    ]
}

private String easterIso(Integer year) {
    Integer a = year % 19
    Integer b = (year / 100) as Integer
    Integer c = year % 100
    Integer d = (b / 4) as Integer
    Integer e = b % 4
    Integer f = ((b + 8) / 25) as Integer
    Integer g = ((b - f + 1) / 3) as Integer
    Integer h = (19 * a + b - d - g + 15) % 30
    Integer i = (c / 4) as Integer
    Integer k = c % 4
    Integer l = (32 + 2 * e + 2 * i - h - k) % 7
    Integer m = ((a + 11 * h + 22 * l) / 451) as Integer
    Integer month = ((h + l - 7 * m + 114) / 31) as Integer
    Integer day = ((h + l - 7 * m + 114) % 31) + 1

    return String.format('%04d-%02d-%02d', year, month, day)
}

private Boolean isIsoToday(String yyyyMmDd) {
    return yyyyMmDd == new Date().format('yyyy-MM-dd', location.timeZone)
}

private String nextEasterIso(Integer currentYear, TimeZone zone, Date nowValue) {
    String thisYear = easterIso(currentYear)
    Date thisYearDate = Date.parse('yyyy-MM-dd', thisYear)

    Calendar endOfEaster = Calendar.getInstance(zone)
    endOfEaster.clear()
    List parts = thisYear.tokenize('-')*.toInteger()
    endOfEaster.set(parts[0], parts[1] - 1, parts[2], 23, 59, 59)
    endOfEaster.set(Calendar.MILLISECOND, 999)

    if (!nowValue.after(endOfEaster.time)) {
        return thisYear
    }

    return easterIso(currentYear + 1)
}

private String nextHalloweenIso(Integer currentYear, TimeZone zone, Date nowValue) {
    String thisYear = String.format('%04d-10-31', currentYear)

    Calendar endOfHalloween = Calendar.getInstance(zone)
    endOfHalloween.clear()
    endOfHalloween.set(currentYear, Calendar.OCTOBER, 31, 23, 59, 59)
    endOfHalloween.set(Calendar.MILLISECOND, 999)

    if (!nowValue.after(endOfHalloween.time)) {
        return thisYear
    }

    return String.format('%04d-10-31', currentYear + 1)
}

private Map seasonChangeEvent(Integer year, String season, String name, String eventKey, TimeZone zone) {
    Date instant = solarSeasonInstant(year, eventKey)
    return [
        season : season,
        name   : name,
        instant: instant,
        date   : instant.format('yyyy-MM-dd', zone)
    ]
}

private List<Map> seasonChangeEvents(Integer year, TimeZone zone) {
    return [
        seasonChangeEvent(year, 'Spring', 'Spring Equinox', 'marchEquinox', zone),
        seasonChangeEvent(year, 'Summer', 'Summer Solstice', 'juneSolstice', zone),
        seasonChangeEvent(year, 'Autumn', 'Autumn Equinox', 'septemberEquinox', zone),
        seasonChangeEvent(year, 'Winter', 'Winter Solstice', 'decemberSolstice', zone)
    ]
}

private Date solarSeasonInstant(Integer year, String eventKey) {
    Double y = (year - 2000) / 1000.0
    Double jde

    switch (eventKey) {
        case 'marchEquinox':
            jde = 2451623.80984 + 365242.37404 * y + 0.05169 * Math.pow(y, 2) - 0.00411 * Math.pow(y, 3) - 0.00057 * Math.pow(y, 4)
            break
        case 'juneSolstice':
            jde = 2451716.56767 + 365241.62603 * y + 0.00325 * Math.pow(y, 2) + 0.00888 * Math.pow(y, 3) - 0.00030 * Math.pow(y, 4)
            break
        case 'septemberEquinox':
            jde = 2451810.21715 + 365242.01767 * y - 0.11575 * Math.pow(y, 2) + 0.00337 * Math.pow(y, 3) + 0.00078 * Math.pow(y, 4)
            break
        case 'decemberSolstice':
            jde = 2451900.05952 + 365242.74049 * y - 0.06223 * Math.pow(y, 2) - 0.00823 * Math.pow(y, 3) + 0.00032 * Math.pow(y, 4)
            break
        default:
            throw new IllegalArgumentException("Unknown season event: ${eventKey}")
    }

    Long millis = Math.round((jde - 2440587.5D) * 86400000D)
    return new Date(millis)
}

//
//    DAYLIGHT SAVING CALCULATIONS
//

private Date clockChangeInstantUtc(Integer year, Integer calendarMonth) {
    String changeDate = lastSundayOfMonth(year, calendarMonth)
    List parts = changeDate.tokenize('-')*.toInteger()

    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone('UTC'))
    cal.clear()
    cal.set(parts[0], parts[1] - 1, parts[2], 1, 0, 0)
    cal.set(Calendar.MILLISECOND, 0)

    return cal.time
}

private TimeZone daylightSavingTimeZone() {
    String tzId = hubTimeZoneId()
    return TimeZone.getTimeZone(isUkCompatibleTimeZone(tzId) ? tzId : 'Europe/London')
}

private Map daylightSavingValues() {
    TimeZone ukZone = daylightSavingTimeZone()
    Date now = new Date()
    Integer year = now.format('yyyy', ukZone).toInteger()

    String forwardDate = lastSundayOfMonth(year, Calendar.MARCH)
    String backDate = lastSundayOfMonth(year, Calendar.OCTOBER)

    Date forwardInstant = clockChangeInstantUtc(year, Calendar.MARCH)
    Date backInstant = clockChangeInstantUtc(year, Calendar.OCTOBER)

    String nextChangeName
    String nextChangeDate
    Integer nextChangeHour
    Integer nextChangeMinute

    if (now.before(forwardInstant)) {
        nextChangeName = 'forward'
        nextChangeDate = forwardDate
        nextChangeHour = 1
        nextChangeMinute = 0
    } else if (now.before(backInstant)) {
        nextChangeName = 'back'
        nextChangeDate = backDate
        nextChangeHour = 2
        nextChangeMinute = 0
    } else {
        Integer nextYear = year + 1
        nextChangeName = 'forward'
        nextChangeDate = lastSundayOfMonth(nextYear, Calendar.MARCH)
        nextChangeHour = 1
        nextChangeMinute = 0
    }

    Boolean active = ukZone.inDaylightTime(now)

    return [
        daylightSaving             : active ? 'true' : 'false',
        daylightSavingPeriod       : toTitleCase(active ? 'British Summer Time' : 'Greenwich Mean Time'),
        clockOffset                : offsetString(ukZone.getOffset(now.time)),
        nextClockChange            : nextChangeName,
        nextClockChangeDate        : formatHubDate(nextChangeDate),
        nextClockChangeTime        : formatHubClockTime(nextChangeHour, nextChangeMinute),
        daysUntilNextClockChange   : daysUntil(nextChangeDate)
    ]
}

private String lastSundayOfMonth(Integer year, Integer calendarMonth) {
    Calendar cal = Calendar.getInstance(daylightSavingTimeZone())
    cal.clear()
    cal.set(year, calendarMonth, 1, 12, 0, 0)
    cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))

    while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) {
        cal.add(Calendar.DAY_OF_MONTH, -1)
    }

    return cal.time.format('yyyy-MM-dd', daylightSavingTimeZone())
}

private String offsetString(Integer offsetMillis) {
    Integer totalMinutes = Math.round(offsetMillis / 60000.0) as Integer
    String sign = totalMinutes < 0 ? '-' : '+'
    Integer absoluteMinutes = Math.abs(totalMinutes)
    Integer hours = (absoluteMinutes / 60) as Integer
    Integer minutes = absoluteMinutes % 60

    return String.format('%s%02d:%02d', sign, hours, minutes)
}

//
//    FORMATTING AND EVENT HELPERS
//

private String clockChangeTimeZoneAbbreviation(Integer hour) {
    // UK clock-change times are displayed in the civil time in effect immediately before the change:
    // forward at 01:00 GMT; back at 02:00 BST.
    return (hour == 2) ? 'BST' : 'GMT'
}

private Integer daysUntil(String yyyyMmDd) {
    Date target = Date.parse('yyyy-MM-dd', yyyyMmDd)
    Date today = Date.parse('yyyy-MM-dd', new Date().format('yyyy-MM-dd', location.timeZone))
    return ((target.time - today.time) / 86400000L).intValue()
}

private List flattenEvents(Map cache, List selectedRegions) {
    List allEvents = []

    selectedRegions.each { String region ->
        List events = cache[region] instanceof List ? cache[region] : []
        events.each { Map event ->
            allEvents << [
                region : region,
                title  : safeString(event.title),
                date   : safeString(event.date),
                notes  : safeString(event.notes),
                bunting: event.bunting == true
            ]
        }
    }

    return allEvents
}

private String formatHubClockTime(Integer hour, Integer minute) {
    // Dedicated formatter to avoid Hubitat formatTime()/pattern issues.
    String renderedTime = String.format('%02d:%02d', hour ?: 0, minute ?: 0)
    return "${renderedTime} ${clockChangeTimeZoneAbbreviation(hour ?: 0)}"
}

private String formatHubDate(String yyyyMmDd) {
    if (!yyyyMmDd) {
        return ''
    }

    Date dateValue
    try {
        dateValue = Date.parse('yyyy-MM-dd', yyyyMmDd)
    } catch (Exception ignored) {
        return safeString(yyyyMmDd)
    }

    try {
        return formatDate(dateValue)
    } catch (Throwable ignored) {
    // Fall through for older platform builds or unsupported helper signatures.
    }

    String pattern = hubDatePattern()
    try {
        return dateValue.format(pattern, location.timeZone)
    } catch (Exception ignored) {
        return yyyyMmDd
    }
}

private String formatHubDateTime(Date value) {
    if (!value) {
        return ''
    }

    try {
        return formatDateTime(value)
    } catch (Throwable ignored) {
    // Fall through for older platform builds or unsupported helper signatures.
    }

    String renderedDate = formatHubDate(value.format('yyyy-MM-dd', location.timeZone))
    String renderedTime
    try {
        renderedTime = value.format(hubClockDisplayPattern(), location.timeZone)
    } catch (Exception ignored) {
        renderedTime = value.format('HH:mm:ss', location.timeZone)
    }

    return "${renderedDate} ${renderedTime}"
}

private String hubClockDisplayPattern() {
    String value = ''
    try {
        value = location?.timeFormat?.toString()
    } catch (Throwable ignored) {
        value = ''
    }

    if (value == '12') {
        return 'h:mm:ss a'
    }

    if (value == '24') {
        return 'HH:mm:ss'
    }

    if (value?.trim()) {
        return value
    }

    return 'HH:mm:ss'
}

private String hubDatePattern() {
    try {
        def value = location?.dateFormat
        if (value) {
            return value.toString()
        }
    } catch (Throwable ignored) {
    // Use deterministic fallback below.
    }

    return 'yyyy-MM-dd'
}

private List normalizedRegions() {
    if (!settings.regions) {
        return ['england-and-wales']
    }

    if (settings.regions instanceof String) {
        return [settings.regions]
    }

    return settings.regions as List
}

private String safeString(value) {
    return value == null ? '' : value.toString()
}

private String toTitleCase(String value) {
    if (!value) return value
    value.toLowerCase().split(/\s+/).collect { w ->
        w ? w[0].toUpperCase() + w.substring(1) : w
    }.join(' ')
}

private String uniqueJoin(Collection values) {
    return values.collect { safeString(it) }
                 .findAll { it }
                 .unique()
                 .join(', ')
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
