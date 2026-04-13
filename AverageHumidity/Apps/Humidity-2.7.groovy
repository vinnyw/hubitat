 definition(
    name: 'Humidity-2.7',
    namespace: 'vinnyw',
    author: 'Vinny Wadding',
    description: 'Manage multiple averaged humidity virtual sensors',
    parent: 'vinnyw:Average Humidity',
    category: 'Convenience',
    importUrl: 'https://raw.githubusercontent.com/vinnyw/hubitat/master/AverageHumidity/Apps/Humidity-2.7.groovy',
    documentationLink: 'https://github.com/vinnyw/hubitat/blob/master/README.md',
    iconUrl: 'https://raw.githubusercontent.com/hubitat/HubitatPublic/master/resources/images/App%20Icons/Convenience.png',
    iconX2Url: 'https://raw.githubusercontent.com/hubitat/HubitatPublic/master/resources/images/App%20Icons/Convenience.png',
    iconX3Url: 'https://raw.githubusercontent.com/hubitat/HubitatPublic/master/resources/images/App%20Icons/Convenience.png',
)

import groovy.transform.Field
import java.math.RoundingMode


//
//    VERSION
//

def getVersion() {
    return parent?.getVersion() ?: 'unknown'
}


//
//    VERSION HELPERS
//

def getChildAppVersion() {
    return getVersion()
}

private String getDisplayVersionValue(Object versionValue) {
    String version = versionValue?.toString()?.trim()
    return version ? "v${version}" : 'unknown'
}


//
//    UI / PREFERENCES
//

preferences {
    page(name: 'mainPage')
}

def mainPage() {
    applyDefaultSettings()
    prepareAdvancedUiSession()

    def child = getChildDevice(childDni())
    String versionLabel = getDisplayVersionValue(getVersion())

    dynamicPage(name: 'mainPage', install: true, uninstall: true) {

        if (!state?.setupComplete) {
            section('Important') {
                paragraph '⚠️ Setup is not complete yet. Press <b>Done</b> to create or update the virtual device.'
            }
        }

        section() {
            label title: 'Virtual Device Name ', submitOnChange: true, required: true

            input(
                name: 'sensors',
                type: 'capability.relativeHumidityMeasurement',
                title: 'Sensors',
                multiple: true,
                required: true
            )
        }

        section(
            hideable: true,
            hidden: false,
            title: 'Logging'
        ) {
            paragraph "Debug logging automatically turns off after ${getDebugAutoDisableMinutes()} minutes."

            input name: 'txtEnable', type: 'bool',
                  title: 'Enable description text logging',
                  defaultValue: true

            input name: 'debugEnable', type: 'bool',
                  title: 'Enable debug logging',
                  defaultValue: false
        }
        
        section(
            hideable: true,
            hidden: !(state?.advancedExpanded == true),
            title: 'Advanced Options'
        ) {
            paragraph 'Optional tuning settings for display precision, units, and trend analysis. Most users can leave these at their defaults and only expand this section when finer control is needed.'

            input(
                name: 'unitPrecision',
                type: 'enum',
                title: 'Precision',
                options: ['0': '0', '1': '0.0', '2': '0.00'],
                defaultValue: selectedUnitPrecision(),
                required: true
            )

            input(
                name: 'unitDisplay',
                type: 'enum',
                title: 'Unit',
                options: ['none': 'None', '%': '%', '%RH': '%RH'],
                defaultValue: selectedUnitDisplay(),
                required: true
            )

            input(
                name: 'trendDisplay',
                type: 'enum',
                title: 'Trend',
                options: ['off': 'Off', 'simple': 'Simple', 'detailed': 'Detailed'],
                defaultValue: selectedTrendDisplay(),
                submitOnChange: true,
                required: true
            )

            if (selectedTrendDisplay() != 'off') {
                input(
                    name: 'trendWindow',
                    type: 'enum',
                    title: 'Trend Evaluation Window',
                    options: trendWindowOptions(),
                    defaultValue: selectedTrendWindow(),
                    required: true
                )

                input(
                    name: 'trendHistory',
                    type: 'enum',
                    title: 'Trend History',
                    options: ['auto': 'Auto (recommended)', 'manual': 'Manual'],
                    defaultValue: selectedTrendHistory(),
                    submitOnChange: true,
                    required: true
                )

                if (selectedTrendHistory() == 'manual') {
                    input(
                        name: 'trendDepth',
                        type: 'number',
                        title: 'Trend Depth', //number of averaged values to keep
                        defaultValue: configuredTrendDepth(),
                        range: '3..100',
                        required: true
                    )
                }

                paragraph 'Trend is calculated from the averaged values inside the selected elapsed-time window.'
            } else {
                paragraph 'Trend tracking is disabled.'
            }
        }

        section() {
            paragraph "<div style='font-size: 10px; color: #888; width: 100%; text-align: right;'>${htmlEncode(versionLabel)}</div>"
        }

    }
}


//
//    UI DEFAULTS & VALIDATION HELPERS
//

private void applyDefaultSettings() {
    ensureEnumSetting('unitPrecision', ['0', '1', '2'], '0')
    ensureEnumSetting('unitDisplay', ['none', '%', '%RH'], '%')
    ensureEnumSetting('trendDisplay', ['off', 'simple', 'detailed'], 'off')
    ensureEnumSetting('trendWindow', trendWindowOptions().keySet() as List, '30')
    ensureEnumSetting('trendHistory', ['auto', 'manual'], 'auto')
    ensureBooleanSetting('txtEnable', true)
    ensureBooleanSetting('debugEnable', false)
}

private void ensureBooleanSetting(String name, Boolean defaultValue) {
    if (settings?."${name}" == null) {
        app?.updateSetting(name, [type: 'bool', value: defaultValue])
    }
}

private void ensureEnumSetting(String name, List allowedValues, String defaultValue) {
    String current = settings?."${name}"?.toString()
    if (!current || !allowedValues*.toString().contains(current)) {
        app?.updateSetting(name, [type: 'enum', value: defaultValue])
    }
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

private void prepareAdvancedUiSession() {
    boolean freshPageLoad = true
    boolean advancedSubmitRefresh = false

    try {
        Map p = (params instanceof Map) ? (Map) params : [:]
        freshPageLoad = p.isEmpty()

        advancedSubmitRefresh =
            p.containsKey('unitPrecision') ||
            p.containsKey('unitDisplay') ||
            p.containsKey('trendDisplay') ||
            p.containsKey('trendWindow') ||
            p.containsKey('trendHistory') ||
            p.containsKey('trendDepth') ||
            p.containsKey('txtEnable') ||
            p.containsKey('debugEnable')
    } catch (Exception ignored) {
        freshPageLoad = true
        advancedSubmitRefresh = false
    }

    if (freshPageLoad) {
        state.advancedExpanded = false
    } else if (advancedSubmitRefresh) {
        state.advancedExpanded = true
    }
}


//
//    DISPLAY HELPERS
//

private Integer configuredTrendDepth() {
    if (selectedTrendHistory() == 'auto') {
        return recommendedTrendDepth()
    }

    Integer size = 12
    try {
        size = (settings?.trendDepth ?: 12) as Integer
    } catch (Exception ignored) {
        size = 12
    }
    return Math.max(3, Math.min(size, 100))
}

private Integer configuredTrendWindowMinutes() {
    Map<String, String> options = trendWindowOptions()
    Integer minutes = 30
    try {
        minutes = (settings?.trendWindow ?: '30') as Integer
    } catch (Exception ignored) {
        minutes = 30
    }
    return options.containsKey(minutes.toString()) ? minutes : 30
}

private Integer decimalPlaces() {
    try {
        return selectedUnitPrecision() as Integer
    } catch (Exception ignored) {
        return 0
    }
}

private String formatWithUnit(BigDecimal value) {
    String u = selectedUnitDisplay()
    return u == 'none' ? "${value}" : "${value}${u}"
}

private Integer recommendedTrendDepth() {
    Integer windowMinutes = configuredTrendWindowMinutes()
    Integer targetSamples = (int) Math.ceil(windowMinutes / 5.0d)
    Integer buffered = (int) Math.ceil(targetSamples * 1.2d)
    return Math.max(12, Math.min(buffered, 100))
}

private String selectedTrendDisplay() {
    String mode = settings?.trendDisplay?.toString() ?: settings?.trendMode?.toString() ?: 'off'
    return ['off', 'simple', 'detailed'].contains(mode) ? mode : 'off'
}

private String selectedTrendHistory() {
    String mode = settings?.trendHistory?.toString() ?: 'auto'
    return ['auto', 'manual'].contains(mode) ? mode : 'auto'
}

private String selectedTrendWindow() {
    return configuredTrendWindowMinutes().toString()
}

private String selectedUnitDisplay() {
    String unit = settings?.unitDisplay?.toString() ?: '%'
    return ['none', '%', '%RH'].contains(unit) ? unit : '%'
}

private String selectedUnitPrecision() {
    String decimals = settings?.unitPrecision?.toString() ?: '0'
    return ['0', '1', '2'].contains(decimals) ? decimals : '0'
}

private Map<String, String> trendWindowOptions() {
    return [
        '5': '5 minutes',
        '15': '15 minutes',
        '30': '30 minutes',
        '60': '1 hour',
        '180': '3 hours',
        '360': '6 hours',
        '720': '12 hours',
        '1440': '24 hours',
        '4320': '3 days',
        '10080': '7 days'
    ]
}


//
//    LIFECYCLE
//

def initialize() {

    if (!parent) {
        log.warn 'Parent app is not available. Cleaning up managed child device.'
        deleteManagedChildDevice()
        return
    }

    trimHistoryToConfiguredSize()

    if (!ensureManagedChildDevice()) {
        log.warn "Unable to create managed child device for ${app?.label ?: childDni()}"
        return
    }

    if (selectedTrendDisplay() == 'off') {
        clearTrendStateAndAttribute()
    }

    syncChildSettings()
    scheduleDebugAutoDisableIfNeeded()

    logDebug("Raw settings.sensors = ${settings?.sensors}")
    List configured = getSelectedDevices()
    logDebug("Selected humidity devices after filtering: ${configured*.displayName}")

    if (configured) {
        subscribe(configured, 'humidity', 'humidityHandler')
        logDebug("Subscribed to humidity events for ${configured*.displayName}")
    } else {
        log.warn 'No valid devices selected for humidity averaging.'
    }

    refresh()
}

def installed() {
    state.setupComplete = true
    state.remove('advancedExpanded')
    if (!parent) {
        log.warn 'Child app install is incomplete because the parent app was not finalized.'
        return
    }
    initialize()
}

def uninstalled() {
    deleteManagedChildDevice()
}

def updated() {
    state.setupComplete = true
    unsubscribe()
    unschedule()
    state.remove('advancedExpanded')
    initialize()
}


//
//    EVENT HANDLERS
//

def childRefreshRequest() {
    if (!getChildDevice(childDni())) {
        ensureManagedChildDevice()
    }
    refresh()
}

def humidityHandler(evt) {
    if (!evt) return

    try {
        if (evt.isStateChange() == false) return
    } catch (Exception ignored) {
    }

    logDebug("Humidity event from ${evt.device?.displayName}: ${evt.value}")
    refresh()
}

def logsOff() {
    app?.updateSetting('debugEnable', [value: false, type: 'bool'])
    syncChildSettings()
    log.warn "${app.label}: Debug logging disabled automatically after ${getDebugAutoDisableMinutes()} minutes"
}

def refresh() {
    List<BigDecimal> values = getValidHumidityValues()
    if (!values) {
        log.warn 'No valid humidity values found from selected sensors.'
        return
    }

    Integer places = decimalPlaces()
    BigDecimal average = calculateAverage(values)
    BigDecimal rounded = average.setScale(places, RoundingMode.HALF_UP)

    def child = getChildDevice(childDni())
    if (!child) {
        log.warn "Managed child device is missing for ${app?.label ?: childDni()}, attempting recreation"
        if (!ensureManagedChildDevice()) {
            log.warn "Managed child device could not be recreated for ${app?.label ?: childDni()}"
            return
        }
        child = getChildDevice(childDni())
        if (!child) {
            log.warn "Managed child device is still unavailable for ${app?.label ?: childDni()}"
            return
        }
    }

    Map trendData = updateTrendAndGetValues(rounded)

    logDebug("Humidity values=${values}, average=${average}, rounded=${rounded}, trend=${trendData?.trend}, trendDisplay=${trendData?.trendDisplay}")
    child.setHumidity(rounded, places, selectedUnitDisplay(), trendData?.trend, trendData?.trendDisplay)
}


//
//    CHILD DEVICE MANAGEMENT
//

private String childDni() {
    return "avg-humidity-${app.id}"
}

def deleteManagedChildDevice() {
    def child = getChildDevice(childDni())
    if (child) {
        deleteChildDevice(child.deviceNetworkId)
    }
}

private Boolean ensureManagedChildDevice() {
    String dni = childDni()
    def child = getChildDevice(dni)
    String desiredLabel = app?.label?.toString()?.trim()

    if (!child) {
        try {
            Map options = [isComponent: false]
            if (desiredLabel) {
                options.name = desiredLabel
                options.label = desiredLabel
            }

            addChildDevice(
                'vinnyw',
                'Humidity-2.7',
                dni,
                options
            )
        } catch (Exception e) {
            log.warn "Unable to create managed child device. Verify driver 'Humidity-2.7' (namespace 'vinnyw') is installed. ${e.message}"
            return false
        }
        child = getChildDevice(dni)
    }

    if (child && desiredLabel && child.label != desiredLabel) {
        child.setLabel(desiredLabel)
    }

    return child != null
}


//
//    LOGGING CONFIGURATION & SYNC
//

def getChildDriverLoggingConfig() {
    return [
        txtEnable              : descriptionTextLoggingEnabled(),
        debugEnable            : debugLoggingEnabled(),
        debugAutoDisableSeconds: getDebugAutoDisableSeconds(),
        debugAutoDisableMinutes: getDebugAutoDisableMinutes()
    ]
}

def syncChildSettings() {
    def child = getChildDevice(childDni())
    if (!child) return

    try {
        child.configure()
    } catch (Exception e) {
        logWarn("Unable to configure child device from app settings: ${e.message}")
    }
}

def updateLoggingFromDriver(txtEnableValue, debugEnableValue) {
    Boolean descEnabled = normalizeBoolean(txtEnableValue, true)
    Boolean debugEnabled = normalizeBoolean(debugEnableValue, false)

    app?.updateSetting('txtEnable', [value: descEnabled, type: 'bool'])
    app?.updateSetting('debugEnable', [value: debugEnabled, type: 'bool'])

    syncChildSettings()
}


//
//    SENSOR HELPERS
//

private BigDecimal calculateAverage(List<BigDecimal> values) {
    BigDecimal sum = values.inject(0.0G) { BigDecimal acc, BigDecimal item -> acc + item }
    return sum.divide(new BigDecimal(values.size()), 6, RoundingMode.HALF_UP)
}

private List getSelectedDevices() {
    def selected = settings?.sensors
    if (!selected) return []

    if (!(selected instanceof List)) {
        selected = [selected]
    }

    return selected.findAll { dev ->
        dev && dev.deviceNetworkId != childDni()
    }
}

private List getValidHumidityDevices() {
    return getSelectedDevices().findAll { dev ->
        try {
            def v = dev.currentValue('humidity')
            return v != null && v.toString() != ''
        } catch (Exception e) {
            logDebug("Skipping ${dev?.displayName}: unable to read humidity: ${e.message}")
            return false
        }
    }
}

private List<BigDecimal> getValidHumidityValues() {
    List<BigDecimal> values = []

    getValidHumidityDevices().each { dev ->
        try {
            Object raw = dev.currentValue('humidity')
            if (raw != null && raw.toString() != '') {
                values << new BigDecimal(raw.toString())
            }
        } catch (Exception e) {
            logDebug("Skipping value for ${dev?.displayName}: ${e.message}")
        }
    }

    return values
}


//
//    LOGGING SCHEDULER
//

Integer getDebugAutoDisableMinutes() {
    return (int) (getDebugAutoDisableSeconds() / 60)
}

Integer getDebugAutoDisableSeconds() {
    try {
        Integer seconds = parent?.getDebugAutoDisableSeconds()
        return seconds ?: 600
    } catch (Exception ignored) {
        return 600
    }
}

private void scheduleDebugAutoDisableIfNeeded() {
    unschedule('logsOff')

    if (debugLoggingEnabled()) {
        runIn(getDebugAutoDisableSeconds(), 'logsOff')
        log.debug "${app.label}: Debug logging will automatically turn off in ${getDebugAutoDisableMinutes()} minutes"
    }
}


//
//    LOGGING HELPERS
//

private Boolean debugLoggingEnabled() {
    return normalizeBoolean(settings?.debugEnable, false)
}

private Boolean descriptionTextLoggingEnabled() {
    return settings?.txtEnable != false
}

private void logDebug(String msg) {
    if (debugLoggingEnabled()) log.debug "${app.label}: ${msg}"
}

private void logError(String msg) {
    log.error "${app.label}: ${msg}"
}

private void logWarn(String msg) {
    log.warn "${app.label}: ${msg}"
}

private Boolean normalizeBoolean(value, Boolean defaultValue) {
    if (value == null) return defaultValue
    if (value instanceof Boolean) return value
    String s = value.toString().trim().toLowerCase()
    if (s == 'true') return true
    if (s == 'false') return false
    return defaultValue
}


//
//    TREND STATE
//

private void clearTrendStateAndAttribute() {
    state.remove('humidityHistory')
    def child = getChildDevice(childDni())
    child?.clearTrend()
}

private void trimHistoryToConfiguredSize() {
    if (selectedTrendDisplay() == 'off') {
        state.remove('humidityHistory')
        return
    }

    List<Map> history = getHistoryEntries()
    while (history.size() > configuredTrendDepth()) {
        history.remove(0)
    }
    saveHistoryEntries(history)
}

private Map updateTrendAndGetValues(BigDecimal roundedHumidity) {
    if (selectedTrendDisplay() == 'off') {
        clearTrendStateAndAttribute()
        return [trend: null, trendDisplay: null]
    }

    List<Map> history = getHistoryEntries()

    BigDecimal lastValue = null
    if (history) {
        try {
            lastValue = new BigDecimal(history.last().value.toString())
        } catch (Exception ignored) {
            lastValue = null
        }
    }

    if (lastValue == null || lastValue.compareTo(roundedHumidity) != 0) {
        history << [ts: now(), value: roundedHumidity.toString()]
        while (history.size() > configuredTrendDepth()) {
            history.remove(0)
        }
        saveHistoryEntries(history)
    }

    return calculateTrend(history, selectedTrendDisplay())
}


//
//    TREND STORAGE
//

private List<Map> getHistoryEntries() {
    List raw = state?.humidityHistory instanceof List ? state.humidityHistory as List : []
    List<Map> history = []

    raw.each { item ->
        try {
            if (item instanceof Map && item.ts != null && item.value != null) {
                history << [ts: (item.ts as Long), value: new BigDecimal(item.value.toString()).toString()]
            } else if (item != null && item.toString() != '') {
                history << [ts: now(), value: new BigDecimal(item.toString()).toString()]
            }
        } catch (Exception ignored) {
        }
    }

    return history.sort { a, b -> (a.ts as Long) <=> (b.ts as Long) }
}

private void saveHistoryEntries(List<Map> history) {
    state.humidityHistory = history.collect { entry ->
        [ts: (entry.ts as Long), value: entry.value.toString()]
    }
}


//
//    TREND CALCULATION
//

private Map calculateTrend(List<Map> history, String mode) {
    if (!history || history.size() < 2) {
        return [trend: 'steady', trendDisplay: 'steady']
    }

    long nowTs = now()
    long windowMs = configuredTrendWindowMinutes().toLong() * 60L * 1000L
    long cutoff = nowTs - windowMs

    List<Map> inWindow = history.findAll { (it.ts as Long) >= cutoff }
    if (inWindow.size() < 2) {
        inWindow = history.takeRight(Math.min(history.size(), configuredTrendDepth()))
    }
    if (inWindow.size() < 2) {
        return [trend: 'steady', trendDisplay: 'steady']
    }

    Map firstEntry = inWindow.first()
    Map lastEntry = inWindow.last()

    BigDecimal first = new BigDecimal(firstEntry.value.toString())
    BigDecimal last = new BigDecimal(lastEntry.value.toString())
    BigDecimal delta = last - first
    long elapsedMs = (lastEntry.ts as Long) - (firstEntry.ts as Long)

    if (elapsedMs <= 0L) {
        return [trend: 'steady', trendDisplay: 'steady']
    }

    BigDecimal elapsedHours = new BigDecimal(elapsedMs).divide(new BigDecimal(3600000L), 6, RoundingMode.HALF_UP)
    if (elapsedHours.compareTo(0G) <= 0) {
        return [trend: 'steady', trendDisplay: 'steady']
    }

    BigDecimal ratePerHour = delta.divide(elapsedHours, 6, RoundingMode.HALF_UP)
    BigDecimal absRatePerHour = ratePerHour.abs()

    // Deadband to avoid noise / tiny oscillations
    if (absRatePerHour < 0.10G) {
        return [trend: 'steady', trendDisplay: 'steady']
    }

    String direction = ratePerHour.signum() > 0 ? 'up' : 'down'
    String displayDirection = direction == 'up' ? 'rising' : 'lowering'

    if (mode == 'simple') {
        return [trend: direction, trendDisplay: displayDirection]
    }

    String speed
    if (absRatePerHour < 0.25G) {
        speed = 'very slowly'
    } else if (absRatePerHour < 0.50G) {
        speed = 'slowly'
    } else if (absRatePerHour < 1.00G) {
        speed = 'gently'
    } else if (absRatePerHour < 2.00G) {
        speed = 'moderately'
    } else if (absRatePerHour < 3.50G) {
        speed = 'moderately fast'
    } else if (absRatePerHour < 5.00G) {
        speed = 'quickly'
    } else if (absRatePerHour < 7.50G) {
        speed = 'very quickly'
    } else {
        speed = 'sharply'
    }

    return [trend: direction, trendDisplay: "${displayDirection} ${speed}"]
}