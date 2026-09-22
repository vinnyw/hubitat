definition(
    name: 'VirtualBlindGroup-0.0',
    namespace: 'vinnyw',
    author: 'Vinny Wadding',
    description: 'Owns one room/group and its aggregate blind state.',
    category: 'Convenience',
    parent: 'vinnyw:Virtual Blind',
    iconUrl: '',
    iconX2Url: '',
    iconX3Url: '',
    singleInstance: false,
    singleThreaded: true,
)

preferences {
    page(
        name: 'groupPage',
        install: true,
        uninstall: true
    ) {
        // Seed only a brand-new/untouched group label while the page is being built.
        // Do not auto-install the group; nested child creation must remain available.
        if (!app.label?.toString()?.trim() ||
            app.label?.toString() == "VirtualBlindGroup-${getShortVersion()}") {
            app.updateLabel('Virtual Blind Group')
            }

        // Keep the presentation device label synchronized immediately when
        // the app label is changed via submitOnChange.
        def existingGroupDevice = getGroupDevice()
        if (existingGroupDevice) {
            String desiredLabel = (app.label ?: 'Virtual Blind Group').toString().trim()
            synchronizeGroupDeviceLabel(existingGroupDevice, desiredLabel)
        }

        if (!state?.setupComplete) {
            section() {
                paragraph '⚠️ Setup is not complete yet. Press <b>Done</b> to create or update the Virtual Blind Group or Room.'
            }
        }

        section() {
            label title: 'Room / Group Name ',
                  required: true,
                  submitOnChange: true

            if (state?.setupComplete) {
                app(
                    name: 'blindApps',
                    appName: "VirtualBlindDevice-${getShortVersion()}",
                    namespace: 'vinnyw',
                    title: 'Add New Blind',
                    multiple: true,
                    required: true,
                    showFilter: true,
                    submitOnChange: true
                )
            }
        }

        section(
            hideable: true,
            hidden: false,
            title: 'Logging'
        ) {
            Integer debugMinutes = getDebugAutoDisableMinutes()
            paragraph debugMinutes != null ?
                "Debug logging automatically turns off after ${debugMinutes} minutes." :
                'Debug logging auto-disable duration is provided by the parent app.'

            input name: 'txtEnable', type: 'bool',
                  title: 'Enable descriptionText logging',
                  defaultValue: true

            input name: 'debugEnable', type: 'bool',
                  title: 'Enable debug logging',
                  defaultValue: false
        }

        section {
            String Version = formatDisplayVersion(getVersion())
            paragraph "<div style='font-size: 10px; color: #888; width: 100%; text-align: right;'>${htmlEncode(Version)}</div>"
        }
    }
}

//
//    LIFECYCLE
//

private void initialize() {
    def groupDevice = ensureGroupDevice()
    groupDevice.configure()
    syncGroupDriverLoggingConfiguration()
    synchronizeBlindApps()
    recalculateGroupState()

    logDebug("Initialized group presentation and ${getChildApps()?.size() ?: 0} blind app(s)")
}

def installed() {
    state.setupComplete = true
    applyLoggingDefaults()
    initialize()
}

def updated() {
    state.setupComplete = true
    applyLoggingDefaults()
    initialize()
}

//
//    DEVICE MANAGEMENT
//

private void ensureBlindPresentation(String blindAppId, String blindLabel) {
    def groupDevice = ensureGroupDevice()
    groupDevice.ensureBlindPresentation(
        blindAppId.toString(),
        blindLabel ?: 'Virtual Blind'
    )
}

private def ensureGroupDevice() {
    String dni = groupDni()
    String desiredLabel = (app.label ?: 'Virtual Blind Group').toString().trim()

    def existing = getChildDevice(dni)
    if (existing) {
        synchronizeGroupDeviceLabel(existing, desiredLabel)
        return existing
    }

    def created = addChildDevice(
        'vinnyw',
        "VirtualBlindGroup-${getShortVersion()}",
        dni,
        [
            name: "VirtualBlindGroup-${getShortVersion()}",
            label: desiredLabel,
            isComponent: true
        ]
    )

    logDebug("Created group presentation device with label '${desiredLabel}'")
    return created
}

private def findBlindApp(String blindAppId) {
    return getChildApps().find { it.id.toString() == blindAppId.toString() }
}

private def getGroupDevice() {
    return getChildDevice(groupDni())
}

private String groupDni() {
    return "vbg-${app.id}"
}

private boolean isKnownBlindApp(String blindAppId) {
    return getChildApps().any { it.id.toString() == blindAppId.toString() }
}

private void synchronizeBlindApps() {
    List childApps = getChildApps()
    Set<String> liveIds = childApps.collect { it.id.toString() } as Set<String>

    Map registry = registryCopy()
    registry.keySet().findAll { !liveIds.contains(it.toString()) }.each {
        registry.remove(it.toString())
    }
    state.blinds = registry

    childApps.each { childApp ->
        String id = childApp.id.toString()
        ensureBlindPresentation(id, childApp.label ?: 'Virtual Blind')

        Map snapshot = childApp.getBlindSnapshot()
        updateRegistry(id, snapshot)
        presentBlind(id, eventsForSnapshot(snapshot))
    }

    def groupDevice = getGroupDevice()
    if (groupDevice) {
        groupDevice.removeOrphanBlindPresentations(liveIds as List<String>)
    }
}

private void synchronizeGroupDeviceLabel(groupDevice, String desiredLabel) {
    if (!groupDevice || !desiredLabel) return

    String currentLabel = groupDevice.label?.toString()
    if (currentLabel != desiredLabel) {
        logDebug("Updating group presentation label from '${currentLabel}' to '${desiredLabel}'")
        groupDevice.setLabel(desiredLabel)
    }
}

//
//    REGISTRATION
//

void registerBlind(String blindAppId, String blindLabel, Map snapshot) {
    if (!isKnownBlindApp(blindAppId)) {
        logWarn("Ignoring registration from unknown Blind App ${blindAppId}.")
        return
    }

    ensureBlindPresentation(blindAppId, blindLabel)
    updateRegistry(blindAppId, snapshot)
    presentBlind(blindAppId, eventsForSnapshot(snapshot))
    recalculateGroupState()
}

void unregisterBlind(String blindAppId) {
    Map registry = registryCopy()
    registry.remove(blindAppId.toString())
    state.blinds = registry

    def groupDevice = getGroupDevice()
    if (groupDevice) {
        groupDevice.removeBlindPresentation(blindAppId.toString())
    }

    recalculateGroupState()
}

//
//    COMMANDS
//

void commandBlindClose(String blindAppId) {
    def blindApp = findBlindApp(blindAppId)
    if (!blindApp) {
        logWarn("Blind App ${blindAppId} was not found.")
        return
    }
    blindApp.commandClose()
}

void commandBlindOpen(String blindAppId) {
    def blindApp = findBlindApp(blindAppId)
    if (!blindApp) {
        logWarn("Blind App ${blindAppId} was not found.")
        return
    }
    blindApp.commandOpen()
}

void commandBlindSetPosition(String blindAppId, BigDecimal requestedPosition) {
    def blindApp = findBlindApp(blindAppId)
    if (!blindApp) {
        logWarn("Blind App ${blindAppId} was not found.")
        return
    }
    blindApp.commandSetPosition(clampPosition(requestedPosition))
}

void commandBlindStartPositionChange(String blindAppId, String direction) {
    def blindApp = findBlindApp(blindAppId)
    if (!blindApp) {
        logWarn("Blind App ${blindAppId} was not found.")
        return
    }

    String normalized = direction?.toLowerCase()

    if (normalized == 'open') {
        blindApp.commandOpen()
    } else if (normalized == 'close') {
        blindApp.commandClose()
    } else {
        logWarn("Unsupported shade direction '${direction}' for Blind App ${blindAppId}.")
    }
}

void commandBlindStop(String blindAppId) {
    def blindApp = findBlindApp(blindAppId)
    if (!blindApp) {
        logWarn("Blind App ${blindAppId} was not found.")
        return
    }
    blindApp.commandStop()
}

void commandBlindStopPositionChange(String blindAppId) {
    commandBlindStop(blindAppId)
}

void commandGroupClose() {
    logDebug('Group close requested')
    getChildApps().each { it.commandClose() }
}

void commandGroupOpen() {
    logDebug('Group open requested')
    getChildApps().each { it.commandOpen() }
}

void commandGroupStop() {
    logDebug('Group stop requested')
    getChildApps().each { it.commandStop() }
}

//
//    STATE MANAGEMENT
//

void blindStateChanged(String blindAppId, Map freshSnapshot) {
    if (!isKnownBlindApp(blindAppId)) {
        logWarn("Ignoring state update from unknown Blind App ${blindAppId}.")
        return
    }

    Map normalized = normalizeSnapshot(freshSnapshot)

    updateRegistry(blindAppId, normalized)
    presentBlind(blindAppId, eventsForSnapshot(normalized))
    recalculateGroupState()
}

private List<Map> eventsForSnapshot(Map snapshot) {
    Map normalized = normalizeSnapshot(snapshot)

    List<Map> events = [
        [name: 'position', value: normalized.position, unit: '%'],
        [name: 'windowShade', value: normalized.windowShade]
    ]

    if (normalized.lastMovement in ['up', 'down']) {
        events << [
            name: 'lastMovement',
            value: normalized.lastMovement,
            isStateChange: false
        ]
    }

    return events
}

private Map normalizeSnapshot(Map snapshot) {
    Integer position = clampPosition(snapshot?.position ?: 0)
    String shade = snapshot?.windowShade?.toString()

    if (!(shade in ['opening', 'closing', 'open', 'closed', 'partially open', 'unknown'])) {
        shade = restingShadeState(position)
    }

    String lastMovement = snapshot?.lastMovement?.toString()
    if (!(lastMovement in ['up', 'down'])) {
        lastMovement = null
    }

    return [
        position: position,
        windowShade: shade,
        lastMovement: lastMovement
    ]
}

private void presentBlind(String blindAppId, List<Map> events) {
    def groupDevice = getGroupDevice()
    if (groupDevice) {
        groupDevice.presentBlind(blindAppId.toString(), events)
    }
}

private void publishGroupState(String aggregateStatus) {
    def groupDevice = getGroupDevice()
    if (!groupDevice) return

    logDebug("Publishing aggregate state=${aggregateStatus}")
    groupDevice.presentGroup([
        [name: 'windowShade', value: aggregateStatus]
    ])
}

private void recalculateGroupState() {
    List<String> liveIds = getChildApps().collect { it.id.toString() }

    if (!liveIds) {
        publishGroupState('empty')
        return
    }

    Map registry = registryCopy()

    /*
     * If an app was just created and has not yet registered, initialize it
     * synchronously once. Runtime state changes thereafter are push-driven.
     */
    liveIds.each { id ->
        if (!registry.containsKey(id)) {
            def childApp = findBlindApp(id)
            if (childApp) {
                registry[id] = normalizeSnapshot(childApp.getBlindSnapshot())
            }
        }
    }
    state.blinds = registry

    List<Map> snapshots = liveIds.collect { id ->
        normalizeSnapshot(registry[id] ?: [:])
    }

    List<String> shades = snapshots.collect { it.windowShade.toString() }
    List<Integer> positions = snapshots.collect { clampPosition(it.position) }

    Integer count = snapshots.size()
    Integer openingCount = shades.count { it == 'opening' }
    Integer closingCount = shades.count { it == 'closing' }
    if (openingCount == count) {
        publishGroupState('allOpening')
        return
    }

    if (closingCount == count) {
        publishGroupState('allClosing')
        return
    }

    if (openingCount > 0 && closingCount > 0) {
        publishGroupState('mixedMoving')
        return
    }

    if (openingCount > 0) {
        publishGroupState('someOpening')
        return
    }

    if (closingCount > 0) {
        publishGroupState('someClosing')
        return
    }

    if (positions.every { it == 0 }) {
        publishGroupState('allOpen')
        return
    }

    if (positions.every { it == 100 }) {
        publishGroupState('allClosed')
        return
    }

    publishGroupState('someOpen')
}

private Map registryCopy() {
    Map source = state.blinds ?: [:]
    Map copy = [:]
    source.each { key, value ->
        copy[key.toString()] = value instanceof Map ? new LinkedHashMap(value) : value
    }
    return copy
}

private String restingShadeState(Integer position) {
    if (position <= 0) return 'open'
    if (position >= 100) return 'closed'
    return 'partially open'
}

private void updateRegistry(String blindAppId, Map snapshot) {
    Map registry = registryCopy()
    registry[blindAppId.toString()] = normalizeSnapshot(snapshot)
    state.blinds = registry
}

//
//    LOGGING
//

void applyBlindPresentationLogging(
    String blindAppId,
    txtEnableValue,
    debugEnableValue,
    debugAutoDisableSecondsValue
) {
    def groupDevice = getGroupDevice()
    if (!groupDevice) return

    try {
        groupDevice.applyBlindPresentationLogging(
            blindAppId?.toString(),
            txtEnableValue,
            debugEnableValue,
            debugAutoDisableSecondsValue
        )
    } catch (Exception e) {
        logWarn("Unable to synchronise logging settings for Blind App ${blindAppId}: ${e.message}")
    }
}

private void applyLoggingDefaults() {
    ensureBooleanSetting('txtEnable', true)
    ensureBooleanSetting('debugEnable', false)
}

private Boolean debugLoggingEnabled() {
    return normalizeBoolean(settings?.debugEnable, false)
}

private Boolean descriptionTextLoggingEnabled() {
    return normalizeBoolean(settings?.txtEnable, true)
}

private void ensureBooleanSetting(String name, Boolean defaultValue) {
    if (settings?."${name}" == null) {
        app?.updateSetting(name, [type: 'bool', value: defaultValue])
    }
}

Map getBlindDriverLoggingConfig(String blindAppId) {
    def blindApp = findBlindApp(blindAppId)
    if (!blindApp) return null

    try {
        return blindApp.getChildDriverLoggingConfig()
    } catch (Exception e) {
        logWarn("Unable to read logging settings for Blind App ${blindAppId}: ${e.message}")
        return null
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
    Integer seconds = getDebugAutoDisableSeconds()
    return seconds != null ? (int) (seconds / 60) : null
}

Integer getDebugAutoDisableSeconds() {
    try {
        return parent?.getDebugAutoDisableSeconds() as Integer
    } catch (Exception e) {
        logWarn("Unable to read debug logging timeout from parent app: ${e.message}")
        return null
    }
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

private void syncGroupDriverLoggingConfiguration() {
    def groupDevice = getGroupDevice()
    if (!groupDevice) return

    try {
        groupDevice.applyPresentationConfiguration(
            descriptionTextLoggingEnabled(),
            debugLoggingEnabled(),
            getDebugAutoDisableSeconds()
        )
    } catch (Exception e) {
        logWarn("Unable to synchronise group driver logging settings: ${e.message}")
    }
}

void updateBlindLoggingFromDriver(String blindAppId, txtEnableValue, debugEnableValue) {
    def blindApp = findBlindApp(blindAppId)
    if (!blindApp) {
        logWarn("Unable to update logging because Blind App ${blindAppId} was not found.")
        return
    }

    try {
        blindApp.updateLoggingFromDriver(txtEnableValue, debugEnableValue)
    } catch (Exception e) {
        logWarn("Unable to update logging for Blind App ${blindAppId}: ${e.message}")
    }
}

def updateLoggingFromDriver(txtEnableValue, debugEnableValue) {
    Boolean descEnabled = normalizeBoolean(txtEnableValue, true)
    Boolean debugEnabled = normalizeBoolean(debugEnableValue, false)

    app?.updateSetting('txtEnable', [value: descEnabled, type: 'bool'])
    app?.updateSetting('debugEnable', [value: debugEnabled, type: 'bool'])

    syncGroupDriverLoggingConfiguration()
}

//
//    VERSION
//

private String formatDisplayVersion(Object versionValue) {
    String version = versionValue?.toString()?.trim()
    return version ? "v${version}" : 'unknown'
}

private String getParentVersionValue() {
    String version = parent?.getVersion()?.toString()?.trim()
    return version ?: null
}

def getShortVersion() {
    return parent?.getShortVersion() ?: 'unknown'
}

def getVersion() {
    return getParentVersionValue() ?: 'unknown'
}

//
//    HELPERS
//

private Integer clampPosition(value) {
    Integer position = safeInteger(value, 0)
    return Math.max(0, Math.min(100, position))
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

private Boolean normalizeBoolean(value, Boolean defaultValue) {
    if (value == null) return defaultValue
    if (value instanceof Boolean) return value

    String s = value.toString().trim().toLowerCase()
    if (s == 'true') return true
    if (s == 'false') return false
    return defaultValue
}

private Integer safeInteger(value, Integer fallback) {
    try {
        return value == null ? fallback : value.toString().toBigDecimal().intValue()
    } catch (ignored) {
        return fallback
    }
}
