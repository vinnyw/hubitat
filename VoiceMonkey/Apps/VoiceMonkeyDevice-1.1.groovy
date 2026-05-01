definition(
    name: 'VoiceMonkeyDevice-1.1',
    namespace: 'vinnyw',
    author: 'Vinny Wadding',
    description: 'Child app for one VoiceMonkey Alexa speaker device.',
    parent: 'vinnyw:VoiceMonkey Integration',
    category: 'Convenience',
    importUrl: 'https://raw.githubusercontent.com/vinnyw/hubitat/master/VoiceMonkey/Apps/VoiceMonkeyDevice-1.1.groovy',
    documentationLink: 'https://github.com/vinnyw/hubitat/blob/master/README.md',
    iconUrl: 'https://raw.githubusercontent.com/hubitat/HubitatPublic/master/resources/images/App%20Icons/Convenience.png',
    iconX2Url: 'https://raw.githubusercontent.com/hubitat/HubitatPublic/master/resources/images/App%20Icons/Convenience.png',
    iconX3Url: 'https://raw.githubusercontent.com/hubitat/HubitatPublic/master/resources/images/App%20Icons/Convenience.png'
)

//
//    UI PREFERENCES
//

preferences {
    page(name: 'mainPage')
}

//
//    VERSION
//

def getVersion() {
    return getParentVersionValue() ?: 'unknown'
}

private String extractShortVersion(String version) {
    if (!version) return null

    def matcher = version =~ /(\d+\.\d+)/
    return matcher.find() ? matcher.group(1) : null
}

private String formatDisplayVersion(Object versionValue) {
    String version = versionValue?.toString()?.trim()
    return version ? "v${version}" : 'unknown'
}

private String getChildDriverName() {
    String shortVersion = extractShortVersion(getParentVersionValue())
    return shortVersion ? "VoiceMonkeyDevice-${shortVersion}" : null
}

private String getParentVersionValue() {
    String version = parent?.getVersion()?.toString()?.trim()
    return version ?: null
}

//
//    UI PAGES
//

def mainPage() {
    migrateLegacyPersonalitySettings()
    sanitizeConfiguredSettingsIfNeeded()
    synchronizeAppLabelFromExistingDevice(true)
    synchronizePersonalityLanguageFromVoiceSelection()

    dynamicPage(name: 'mainPage', install: true, uninstall: true) {
        if (!state?.setupComplete) {
            section('Important') {
                paragraph '⚠️ Setup is not complete yet. Press <b>Done</b> to create or update the virtual device.'
            }
        }

        section {
            label title: 'Virtual Device Label ', submitOnChange: true, required: true

            input 'voiceMonkeyDeviceId', 'text',
                title: 'Device ID ',
                submitOnChange: true,
                required: true
        }

        section(hideable: true, hidden: false, title: 'Logging') {
            paragraph "Debug logging automatically turns off after ${debugAutoDisableMinutes()} minutes."

            input name: 'txtEnable', type: 'bool',
                  title: 'Enable descriptionText logging',
                  defaultValue: true

            input name: 'debugEnable', type: 'bool',
                  title: 'Enable debug logging',
                  defaultValue: false,
                  submitOnChange: true
        }

        section(
            hideable: true,
            hidden: shouldHidePersonalitySection(),
            title: 'Personality'
        ) {
            input 'personalityVoice', 'enum', title: 'Voice', options: voiceOptions(), defaultValue: '', required: false, submitOnChange: true
            input 'personalityLanguage', 'enum', title: 'Locale / Language', options: languageOptions(), defaultValue: '', required: false, submitOnChange: true
            input 'personalityChime', 'enum', title: 'Chime', options: chimeOptions(), defaultValue: '', required: false, submitOnChange: true
        }

        if (app.getInstallationState() == 'COMPLETE') {
            section {
                String Version = formatDisplayVersion(getVersion())
                paragraph "<div style='font-size: 10px; color: #888; width: 100%; text-align: right;'>${htmlEncode(Version)}</div>"
            }
        }
    }
}

//
//    SETTINGS VALIDATION
//

private String getConfiguredVoiceMonkeyDeviceId() {
    String deviceId = settings?.voiceMonkeyDeviceId?.toString()?.trim()
    return deviceId ?: null
}

private void sanitizeConfiguredSettingsIfNeeded() {
}
//
//    UI STATE & DISPLAY HELPERS
//

private Boolean hasConfiguredPersonality() {
    return normalizeOptionalString(settings?.personalityVoice) != null ||
           normalizeOptionalString(settings?.personalityLanguage) != null ||
           normalizeOptionalString(settings?.personalityChime) != null
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

private void migrateLegacyPersonalitySetting(String legacyName, String newName, String settingType) {
    String currentValue = normalizeOptionalString(settings?."${newName}")
    if (currentValue) return

    String legacyValue = normalizeOptionalString(settings?."${legacyName}")
    if (!legacyValue) return

    app.updateSetting(newName, [value: legacyValue, type: settingType])
    state.personalitySectionExpanded = true
    logDebug("Migrated legacy setting ${legacyName} to ${newName}")
}

private void migrateLegacyPersonalitySettings() {
    migrateLegacyPersonalitySetting('defaultVoice', 'personalityVoice', 'enum')
    migrateLegacyPersonalitySetting('defaultLanguage', 'personalityLanguage', 'enum')
    migrateLegacyPersonalitySetting('defaultChime', 'personalityChime', 'enum')

    if (hasConfiguredPersonality()) {
        state.personalitySectionExpanded = true
    }
}

private Boolean shouldHidePersonalitySection() {
    if (state?.personalitySectionExpanded == true) {
        return false
    }

    if (hasConfiguredPersonality()) {
        state.personalitySectionExpanded = true
        return false
    }

    return true
}

private void synchronizePersonalityLanguageFromVoiceSelection() {
    String selectedVoice = normalizeOptionalString(settings?.personalityVoice)
    if (!selectedVoice) return

    String mappedLanguage = resolveLanguageForVoice(selectedVoice)
    if (!mappedLanguage) return

    String currentLanguage = normalizeOptionalString(settings?.personalityLanguage)
    if (currentLanguage != mappedLanguage) {
        app.updateSetting('personalityLanguage', [value: mappedLanguage, type: 'enum'])
        logDebug("Auto-selected locale ${mappedLanguage} for voice ${selectedVoice}")
    }
}

//
//    LIFECYCLE
//

def initialize() {
    migrateLegacyPersonalitySettings()
    sanitizeConfiguredSettingsIfNeeded()
    scheduleDebugAutoDisableIfNeeded()
    createOrUpdateChildDevice(false)
    syncChildSettings()
    initializeQueueState()
    syncLocalQueueState(getSpeakerDevice(), 'idle', null)
}

def installed() {
    logDebug("Installed child app ${app?.label}")
    initialize()
}

def uninstalled() {
    logDebug("Uninstalled child app ${app?.label}")
    unsubscribe()
    unschedule()

    deleteManagedChildDevice()

    state.queue = []
    state.activeItem = null
    state.processing = false
}

def updated() {
    logDebug("Updated child app ${app?.label}")
    unsubscribe()
    unschedule()
    initialize()
}

//
//    CHILD DEVICE MANAGEMENT
//

private void createOrUpdateChildDevice(Boolean preferDeviceLabel) {
    String dni = getChildDni()
    if (!dni) return

    String childDriverName = getChildDriverName()
    if (!childDriverName) {
        state.setupComplete = false
        log.warn "${app.label}: Unable to determine child driver name from parent version."
        return
    }

    def child = getChildDevice(dni)
    String desiredLabel = resolveSynchronizedLabel(child, preferDeviceLabel)

    if (!child) {
        try {
            child = addChildDevice(
                'vinnyw',
                childDriverName,
                dni,
                [
                    name: childDriverName,
                    label: desiredLabel,
                    isComponent: false
                ]
            )
            logInfo("Created child device ${desiredLabel}")
        } catch (Exception ex) {
            state.setupComplete = false
            log.warn "${app.label}: Unable to create child device ${desiredLabel}: ${ex.message}"
            return
        }
    }

    synchronizeChildDeviceMetadata(child, desiredLabel)

    state.lastSyncedLabel = desiredLabel
    state.setupComplete = true
}

private void deleteManagedChildDevice() {
    String dni = getChildDni()
    if (!dni) return

    def child = getChildDevice(dni)
    if (!child) return

    try {
        deleteChildDevice(dni)
        logInfo("Deleted child device ${dni}")
    } catch (Exception ex) {
        log.warn "${app.label}: Unable to delete child device ${dni}: ${ex.message}"
    }
}

private void initializeQueueState() {
    state.queue = (state.queue instanceof List) ? state.queue : []
    state.processing = false
    state.activeItem = null
    if (!(state.nextDispatchAllowedEpoch instanceof Number)) {
        state.nextDispatchAllowedEpoch = 0L
    }
}

//
//    LOGGING CONFIGURATION & SYNC
//

def getChildDriverLoggingConfig() {
    return [
        txtEnable              : descriptionTextLoggingEnabled(),
        debugEnable            : debugLoggingEnabled(),
        debugAutoDisableSeconds: getDebugAutoDisableSeconds(),
        debugAutoDisableMinutes: debugAutoDisableMinutes()
    ]
}

def getDebugAutoDisableSeconds() {
    return normalizePositiveInteger(parent?.getDebugAutoDisableSeconds(), 1800)
}

def getDefaultFirstMessageDelaySeconds() {
    return normalizePositiveInteger(parent?.getDefaultFirstMessageDelaySeconds(), 2)
}

def getDefaultMinimumSpacingSeconds() {
    return normalizePositiveInteger(parent?.getDefaultMinimumSpacingSeconds(), 3)
}

def getDefaultQueueBufferSeconds() {
    return normalizePositiveInteger(parent?.getDefaultQueueBufferSeconds(), 3)
}

def getDefaultVoiceMonkeyHttpTimeoutSeconds() {
    return normalizePositiveInteger(parent?.getDefaultVoiceMonkeyHttpTimeoutSeconds(), 30)
}

def syncChildSettings() {
    def child = getSpeakerDevice()
    if (!child) return

    try {
        child.configure()
    } catch (Exception ex) {
        log.warn "${app.label}: Unable to configure child device from app settings: ${ex.message}"
    }
}

def updateLoggingFromDriver(txtEnableValue, debugEnableValue) {
    Boolean descEnabled = normalizeBoolean(txtEnableValue, true)
    Boolean debugEnabled = normalizeBoolean(debugEnableValue, false)

    app.updateSetting('txtEnable', [value: descEnabled, type: 'bool'])
    app.updateSetting('debugEnable', [value: debugEnabled, type: 'bool'])

    syncChildSettings()
}

//
//    METADATA & LABEL HELPERS
//

private String getAppLabelValue() {
    return normalizeLabelValue(app?.getLabel())
}

String getChildDni() {
    return "VoiceMonkeyDevice-${app.id}"
}

private String getDefaultChildLabel() {
    return "VoiceMonkey Device ${app.id}"
}

private getSpeakerDevice() {
    return getChildDevice(getChildDni())
}

private String normalizeLabelValue(Object value) {
    String text = value?.toString()?.trim()
    return text ? text : null
}

private String resolveSynchronizedLabel(child, Boolean preferDeviceLabel) {
    String appLabel = getAppLabelValue()
    String deviceLabel = normalizeLabelValue(child?.getLabel())
    String lastSyncedLabel = normalizeLabelValue(state?.lastSyncedLabel)
    String defaultLabel = getDefaultChildLabel()

    if (!appLabel && !deviceLabel) return defaultLabel
    if (!appLabel) return deviceLabel ?: defaultLabel
    if (!deviceLabel) return appLabel
    if (appLabel == deviceLabel) return appLabel

    if (lastSyncedLabel) {
        if (appLabel == lastSyncedLabel && deviceLabel != lastSyncedLabel) return deviceLabel
        if (deviceLabel == lastSyncedLabel && appLabel != lastSyncedLabel) return appLabel
    }

    return preferDeviceLabel ? deviceLabel : appLabel
}

private void synchronizeAppLabelFromExistingDevice(Boolean preferDeviceLabel) {
    def child = getSpeakerDevice()
    if (!child) return

    String desiredLabel = resolveSynchronizedLabel(child, preferDeviceLabel)
    if (!desiredLabel) return

    synchronizeChildDeviceMetadata(child, desiredLabel)
    state.lastSyncedLabel = desiredLabel
}

private void synchronizeChildDeviceMetadata(child, String desiredLabel) {
    if (!child) return

    String childDriverName = getChildDriverName()

    try {
        if (childDriverName && normalizeLabelValue(child?.getName()) != childDriverName) {
            child.setName(childDriverName)
        }
    } catch (Exception ex) {
        log.warn "${app.label}: Unable to update child device name: ${ex.message}"
    }

    try {
        if (normalizeLabelValue(child?.getLabel()) != desiredLabel) {
            child.setLabel(desiredLabel)
        }
    } catch (Exception ex) {
        log.warn "${app.label}: Unable to update child device label: ${ex.message}"
    }

    try {
        if (getAppLabelValue() != desiredLabel) {
            app.updateLabel(desiredLabel)
        }
    } catch (Exception ex) {
        log.warn "${app.label}: Unable to update child app label: ${ex.message}"
    }
}

//
//    LOGGING HELPERS
//

private Integer debugAutoDisableMinutes() {
    return (getDebugAutoDisableSeconds() / 60) as Integer
}

private Boolean debugLoggingEnabled() {
    return normalizeBoolean(settings?.debugEnable, false)
}

private Boolean descriptionTextLoggingEnabled() {
    return normalizeBoolean(settings?.txtEnable, true)
}

private void logDebug(String message) {
    if (debugLoggingEnabled()) {
        log.debug "${app.label}: ${message}"
    }
}

private void logInfo(String message) {
    if (descriptionTextLoggingEnabled()) {
        log.info "${app.label}: ${message}"
    }
}

def logsOff() {
    if (!debugLoggingEnabled()) return

    app.updateSetting('debugEnable', [value: false, type: 'bool'])
    syncChildSettings()
    log.warn "${app.label}: Debug logging disabled automatically after ${debugAutoDisableMinutes()} minutes"
}

private void scheduleDebugAutoDisableIfNeeded() {
    unschedule('logsOff')

    if (debugLoggingEnabled()) {
        runIn(getDebugAutoDisableSeconds(), 'logsOff')
        logDebug("Debug logging will automatically turn off in ${debugAutoDisableMinutes()} minutes")
    }
}

//
//    OPTIONS
//

private List<Map<String, String>> chimeOptions() {
    return [
        ['': 'None'],
        ['soundbank://soundlibrary/alarms/air_horns/air_horn_01': 'Air Horn #1'],
        ['soundbank://soundlibrary/alarms/beeps_and_bloops/boing_01': 'Boing #1'],
        ['soundbank://soundlibrary/alarms/beeps_and_bloops/bell_01': 'Bell #1'],
        ['soundbank://soundlibrary/alarms/beeps_and_bloops/bell_02': 'Bell #2'],
        ['soundbank://soundlibrary/alarms/chimes_and_bells/chimes_bells_05': 'Bell #3'],
        ['soundbank://soundlibrary/alarms/buzzers/buzzers_01': 'Buzzer #1'],
        ['soundbank://soundlibrary/alarms/buzzers/buzzers_04': 'Buzzer #2'],
        ['soundbank://soundlibrary/alarms/chimes_and_bells/chimes_bells_04': 'Chimes'],
        ['soundbank://soundlibrary/alarms/beeps_and_bloops/bell_03': 'Ding #1'],
        ['soundbank://soundlibrary/alarms/beeps_and_bloops/bell_04': 'Ding #2'],
        ['soundbank://soundlibrary/home/amzn_sfx_doorbell_01': 'Doorbell #1'],
        ['soundbank://soundlibrary/home/amzn_sfx_doorbell_chime_02': 'Doorbell #2'],
        ['soundbank://soundlibrary/musical/amzn_sfx_electronic_beep_01': 'Electronic Beep #1'],
        ['soundbank://soundlibrary/musical/amzn_sfx_electronic_beep_02': 'Electronic Beep #2'],
        ['soundbank://soundlibrary/scifi/amzn_sfx_scifi_timer_beep_01': 'Electronic Beep #3'],
        ['soundbank://soundlibrary/alarms/beeps_and_bloops/intro_02': 'Intro #1'],
        ['soundbank://soundlibrary/scifi/amzn_sfx_scifi_alarm_01': 'Siren #1'],
        ['soundbank://soundlibrary/alarms/beeps_and_bloops/buzz_03': 'Siren #2'],
        ['soundbank://soundlibrary/musical/amzn_sfx_test_tone_01': 'Tone #1'],
        ['soundbank://soundlibrary/alarms/beeps_and_bloops/tone_02': 'Tone #2'],
        ['soundbank://soundlibrary/alarms/beeps_and_bloops/tone_05': 'Tone #3'],
        ['soundbank://soundlibrary/alarms/beeps_and_bloops/woosh_02': 'Woosh']
    ]
}

private List<Map<String, String>> languageOptions() {
    return [
        ['': 'Default'],
        ['de-DE': 'German (Germany)'],
        ['en-AU': 'English (Australia)'],
        ['en-CA': 'English (Canada)'],
        ['en-GB': 'English (United Kingdom)'],
        ['en-IN': 'English (India)'],
        ['en-US': 'English (United States)'],
        ['es-ES': 'Spanish (Spain)'],
        ['es-MX': 'Spanish (Mexico)'],
        ['es-US': 'Spanish (United States)'],
        ['fr-CA': 'French (Canada)'],
        ['fr-FR': 'French (France)'],
        ['hi-IN': 'Hindi (India)'],
        ['it-IT': 'Italian (Italy)'],
        ['ja-JP': 'Japanese (Japan)'],
        ['pt-BR': 'Portuguese (Brazil)']
    ]
}

private Map<String, String> voiceOptions() {
    Map<String, String> options = new LinkedHashMap<String, String>()
    options[''] = 'Alexa (default)'
    options['Nicole'] = 'English Australian (F) - Nicole'
    options['Russell'] = 'English Australian (M) - Russell'
    options['Amy'] = 'English British (F) - Amy'
    options['Emma'] = 'English British (F) - Emma'
    options['Brian'] = 'English British (M) - Brian'
    options['Raveena'] = 'English Indian (F) - Raveena'
    options['Aditi'] = 'Hindi (F) - Aditi'
    options['Ivy'] = 'English US (F) - Ivy'
    options['Joanna'] = 'English US (F) - Joanna'
    options['Kendra'] = 'English US (F) - Kendra'
    options['Kimberly'] = 'English US (F) - Kimberly'
    options['Salli'] = 'English US (F) - Salli'
    options['Joey'] = 'English US (M) - Joey'
    options['Justin'] = 'English US (M) - Justin'
    options['Matthew'] = 'English US (M) - Matthew'
    options['Geraint'] = 'English Welsh (M) - Geraint'
    options['Celine'] = 'French (F) - Céline'
    options['Lea'] = 'French (F) - Léa'
    options['Mathieu'] = 'French (M) - Mathieu'
    options['Chantal'] = 'French Canadian (F) - Chantal'
    options['Marlene'] = 'German (F) - Marlene'
    options['Vicki'] = 'German (F) - Vicki'
    options['Hans'] = 'German (M) - Hans'
    options['Bianca'] = 'Italian (F) - Bianca'
    options['Carla'] = 'Italian (F) - Carla'
    options['Giorgio'] = 'Italian (M) - Giorgio'
    options['Takumi'] = 'Japanese (M) - Takumi'
    options['Mizuki'] = 'Japanese (F) - Mizuki'
    options['Camila'] = 'Portugese Brazilian (F) - Camila'
    options['Vitoria'] = 'Portugese Brazilian (F) - Vitória'
    options['Ricardo'] = 'Portugese Brazilian (M) - Ricardo'
    options['Conchita'] = 'Spanish European (F) - Conchita'
    options['Lucia'] = 'Spanish European (F) - Lucia'
    options['Enrique'] = 'Spanish European (M) - Enrique'
    options['Mia'] = 'Spanish Mexican (F) - Mia'
    options['Penelope'] = 'Spanish US (F) - Penélope'
    options['Lupe'] = 'Spanish US (F) - Lupe'
    options['Miguel'] = 'Spanish US (M) - Miguel'
    return options
}

//
//    VOICE & TOKEN HELPERS
//

private String getEffectiveDefaultLanguage() {
    String voiceLanguage = resolveLanguageForVoice(settings?.personalityVoice)
    return voiceLanguage ?: settings?.personalityLanguage?.toString()?.trim()
}

private String getEffectiveToken() {
    String token = parent?.getEffectiveToken()?.toString()?.trim()
    return token ?: null
}

private String resolveLanguageForVoice(String voiceName) {
    switch (voiceName?.toString()?.trim()) {
        case 'Nicole':
        case 'Russell':
            return 'en-AU'
        case 'Amy':
        case 'Emma':
        case 'Brian':
        case 'Geraint':
            return 'en-GB'
        case 'Raveena':
            return 'en-IN'
        case 'Aditi':
            return 'hi-IN'
        case 'Ivy':
        case 'Joanna':
        case 'Kendra':
        case 'Kimberly':
        case 'Salli':
        case 'Joey':
        case 'Justin':
        case 'Matthew':
            return 'en-US'
        case 'Celine':
        case 'Lea':
        case 'Mathieu':
            return 'fr-FR'
        case 'Chantal':
            return 'fr-CA'
        case 'Marlene':
        case 'Vicki':
        case 'Hans':
            return 'de-DE'
        case 'Bianca':
        case 'Carla':
        case 'Giorgio':
            return 'it-IT'
        case 'Takumi':
        case 'Mizuki':
            return 'ja-JP'
        case 'Camila':
        case 'Vitoria':
        case 'Ricardo':
            return 'pt-BR'
        case 'Conchita':
        case 'Lucia':
        case 'Enrique':
            return 'es-ES'
        case 'Mia':
            return 'es-MX'
        case 'Penelope':
        case 'Lupe':
        case 'Miguel':
            return 'es-US'
        default:
            return null
    }
}

//
//    DRIVER CALLBACKS
//

def clearQueueFromDevice(String deviceNetworkId) {
    if (deviceNetworkId != getChildDni()) return

    state.queue = []
    state.activeItem = null
    state.processing = false
    state.nextDispatchAllowedEpoch = 0L

    unschedule('kickQueue')
    unschedule('completePlayback')
    unschedule('queueRecoveryCheck')
    unschedule('voiceMonkeyAcceptanceTimeout')

    scheduleDeferredQueueStateRefresh()

    logDebug('Queue clear requested from device')
}

def deferredQueueStateRefresh() {
    def child = getSpeakerDevice()
    if (!child) return

    if (getLocalPendingCount() == 0 && state.processing != true) {
        syncLocalQueueState(child, 'idle', null, null)
    } else if (state.processing == true) {
        syncLocalQueueState(child, null, null, null)
    } else {
        syncLocalQueueState(child, 'waiting', null, null)
    }
}

def enqueueSpeakFromDevice(String deviceNetworkId, String text, String voice = null, String chime = null) {
    if (deviceNetworkId != getChildDni()) return

    String message = sanitizeAnnouncementText(text)
    if (!message) {
        logDebug('Ignoring empty or formatting-only TTS request from device')
        return
    }

    initializeQueueState()

    String cleanVoice = normalizeOptionalString(voice)
    String cleanChime = normalizeOptionalString(chime)
    Long enqueueEpoch = now()
    Long queueId = nextQueueItemId(enqueueEpoch)

    Map item = [
        id          : queueId,
        enqueueEpoch: enqueueEpoch,
        text        : message,
        voice       : cleanVoice,
        chime       : cleanChime
    ]

    List queue = getQueuedItems()
    Boolean wasIdleBeforeEnqueue = (queue.size() == 0 && state.processing != true && !(state.activeItem instanceof Map))

    queue << item
    state.queue = queue

    logMessageEnqueued(item.text?.toString(), getLocalPendingCount(), item.id)
    logDebug("Accepted message '${item.text}' from device, local queue size ${queue.size()}")

    scheduleDeferredQueueStateRefresh()
    scheduleKickQueue(wasIdleBeforeEnqueue ? getDefaultFirstMessageDelaySeconds() : 1)
}

private void scheduleDeferredQueueStateRefresh() {
    unschedule('deferredQueueStateRefresh')
    runInMillis(100, 'deferredQueueStateRefresh', [overwrite: true])
}

//
//    QUEUE ORCHESTRATION
//

def completePlayback() {
    unschedule('completePlayback')
    unschedule('queueRecoveryCheck')
    unschedule('voiceMonkeyAcceptanceTimeout')

    def child = getSpeakerDevice()
    Map item = (state.activeItem instanceof Map) ? state.activeItem : null

    state.activeItem = null
    state.processing = false

    Boolean hasMoreLocalItems = (getLocalQueueCount() > 0)

    Integer bufferSeconds = hasMoreLocalItems ? getDefaultQueueBufferSeconds() : 0
    state.nextDispatchAllowedEpoch = (hasMoreLocalItems && bufferSeconds > 0) ? (now() + (bufferSeconds * 1000L)) : 0L

    if (child) {
        syncLocalQueueState(child, hasMoreLocalItems ? 'waiting' : 'idle', null, null)
    }

    if (hasMoreLocalItems) {
        logDebug("Queue waiting ${bufferSeconds} seconds before dispatching next message")
        scheduleKickQueue(bufferSeconds)
    }
}

private List getQueuedItems() {
    if (state.queue instanceof List) {
        return new ArrayList(state.queue)
    }

    return []
}

private Integer getVoiceMonkeyAcceptanceTimeoutSeconds() {
    return 30
}

def kickQueue() {
    if (recoverStaleProcessingIfNeeded()) return

    List queue = getQueuedItems()

    if (state.processing == true) {
        syncLocalQueueState(getSpeakerDevice(), null, null, null)
        logDebug('kickQueue ignored because processing is active')
        return
    }

    Long nextAllowedEpoch = safeLong(state.nextDispatchAllowedEpoch, 0L)
    Long currentEpoch = now()
    if (nextAllowedEpoch > currentEpoch) {
        Integer remainingSeconds = Math.max(1, (((nextAllowedEpoch - currentEpoch) + 999L) / 1000L).intValue())
        syncLocalQueueState(getSpeakerDevice(), 'waiting', null, null)
        logDebug("Queue waiting ${remainingSeconds} more seconds before next dispatch")
        scheduleKickQueue(remainingSeconds)
        return
    }

    state.nextDispatchAllowedEpoch = 0L

    if (queue.size() == 0) {
        syncLocalQueueState(getSpeakerDevice(), 'idle', null, null)
        return
    }

    Map nextItem = queue[0] as Map

    queue.remove(0)
    state.queue = queue

    Integer delaySeconds = estimateDurationSeconds(nextItem)
    Long requestEpoch = now()
    nextItem.duration = delaySeconds
    nextItem.requestStartedEpoch = requestEpoch
    nextItem.acceptanceDueEpoch = requestEpoch + (getVoiceMonkeyAcceptanceTimeoutSeconds() * 1000L)
    nextItem.queueStage = 'dispatching'

    state.activeItem = nextItem
    state.processing = true

    def child = getSpeakerDevice()
    if (child) {
        syncLocalQueueState(child, 'dispatching', nextItem?.text?.toString(), nextItem?.id)
    }

    scheduleAcceptanceTimeoutForActiveItem(nextItem)

    Boolean requestSubmitted = speakViaVoiceMonkey(nextItem)
    if (requestSubmitted != true) {
        return
    }
}

private Integer normalizeCompletionDelay(Object value) {
    Integer delay = safeWholeNumber(value, getDefaultMinimumSpacingSeconds())
    if (delay < 1) delay = 1
    return delay
}

def queueRecoveryCheck() {
    recoverStaleProcessingIfNeeded()

    if (state.processing != true && getLocalQueueCount() > 0) {
        scheduleKickQueue(1)
    }
}

private Boolean recoverStaleProcessingIfNeeded() {
    if (state.processing != true) return false

    Map activeItem = (state.activeItem instanceof Map) ? (state.activeItem as Map) : null

    if (!activeItem) {
        state.processing = false
        log.warn "${app.label}: Recovered queue state with processing=true and no active item"
        if (getLocalQueueCount() > 0) {
            scheduleKickQueue(1)
        }
        return true
    }

    Long completionDueEpoch = safeLong(activeItem?.completionDueEpoch, null)
    if (completionDueEpoch != null && now() > (completionDueEpoch + 30000L)) {
        log.warn "${app.label}: Recovered stale active queue item ${activeItem?.id}"
        completePlayback()
        return true
    }

    Long acceptanceDueEpoch = safeLong(activeItem?.acceptanceDueEpoch, null)
    if (completionDueEpoch == null && acceptanceDueEpoch != null && now() > acceptanceDueEpoch) {
        log.warn "${app.label}: VoiceMonkey acceptance timed out for queue item ${activeItem?.id}"
        markFailureForItem(safeLong(activeItem?.id, null), 'VoiceMonkey acceptance timeout')
        return true
    }

    return false
}

private void scheduleAcceptanceTimeoutForActiveItem(Map item) {
    Integer timeoutSeconds = getVoiceMonkeyAcceptanceTimeoutSeconds()

    unschedule('voiceMonkeyAcceptanceTimeout')
    unschedule('completePlayback')
    unschedule('queueRecoveryCheck')

    runIn(timeoutSeconds, 'voiceMonkeyAcceptanceTimeout', [overwrite: true])

    logDebug("Scheduled VoiceMonkey acceptance timeout in ${timeoutSeconds} seconds for item ${item?.id}")
}

private void scheduleCompletionForActiveItem(Map item) {
    Integer delaySeconds = normalizeCompletionDelay(item?.duration)

    unschedule('voiceMonkeyAcceptanceTimeout')
    unschedule('completePlayback')
    unschedule('queueRecoveryCheck')

    runIn(delaySeconds, 'completePlayback', [overwrite: true])
    runIn(delaySeconds + 30, 'queueRecoveryCheck', [overwrite: true])

    logDebug("Scheduled queue completion in ${delaySeconds} seconds for item ${item?.id}")
}

private void scheduleKickQueue(Integer delaySeconds = 1) {
    Integer delay = safeWholeNumber(delaySeconds, 1)

    unschedule('kickQueue')

    if (delay <= 0) {
        runInMillis(100, 'kickQueue', [overwrite: true])
    } else {
        runIn(delay, 'kickQueue', [overwrite: true])
    }
}

def voiceMonkeyAcceptanceTimeout() {
    Map activeItem = (state.activeItem instanceof Map) ? (state.activeItem as Map) : null
    if (!activeItem) return

    if (activeItem.acceptedEpoch != null || activeItem.completionDueEpoch != null) {
        return
    }

    markFailureForItem(safeLong(activeItem?.id, null), 'VoiceMonkey acceptance timeout')
}

//
//    DRIVER STATE SYNC
//

private Boolean applyDriverEnqueueLog(dev, String message, Integer queueSize) {
    if (!dev) return false

    try {
        dev.queueMessageEnqueuedFromChild(message, queueSize)
        return true
    } catch (Exception ex) {
        logDebug("Unable to log enqueued message through driver: ${ex.message}")
        return false
    }
}

private Boolean applyDriverQueueState(dev, Integer queueSize, String queueStatus) {
    if (!dev) return false

    try {
        dev.queueStateFromChild(queueSize, queueStatus)
        return true
    } catch (Exception ex) {
        logDebug("Unable to sync queue state through driver: ${ex.message}")
        return false
    }
}

private Boolean applyDriverQueueStatus(dev, String queueStatus) {
    if (!dev) return false

    try {
        dev.queueStatusFromChild(queueStatus)
        return true
    } catch (Exception ex) {
        logDebug("Unable to sync queue status through driver: ${ex.message}")
        return false
    }
}

private String deriveLocalQueueStatus(String desiredStatus = null) {
    String status = normalizeQueueStatus(desiredStatus, null)
    if (status) return status

    if (state.processing == true || state.activeItem instanceof Map) {
        Map activeItem = (state.activeItem instanceof Map) ? (state.activeItem as Map) : null
        String activeStage = normalizeQueueStatus(activeItem?.queueStage, null)
        return activeStage ?: 'dispatching'
    }

    if (getLocalQueueCount() > 0) return 'waiting'
    return 'idle'
}

private void logMessageEnqueued(String message, Object queueSizeValue = null, Object itemId = null) {
    String cleanMessage = normalizeOptionalString(message)
    Integer queueSize = safeWholeNumber(queueSizeValue, getLocalPendingCount())

    def child = getSpeakerDevice()
    if (!child) return

    if (applyDriverEnqueueLog(child, cleanMessage, queueSize) != true) {
        Boolean sizeChanged = sendChildEventIfChanged(child, 'queueSize', queueSize)
        if (sizeChanged == true) {
            sendChildEventIfChanged(child, 'lastActivity', now().toString())
        }
    }
}

private String normalizeQueueStatus(Object value, String fallbackValue) {
    String text = value?.toString()?.trim()?.toLowerCase()
    if (!text) return fallbackValue

    switch (text) {
        case 'idle':
            return 'idle'
        case 'queued':
        case 'enqueueing':
            return 'enqueueing'
        case 'waiting':
            return 'waiting'
        case 'dispatching':
        case 'submitted':
            return 'dispatching'
        case 'accepted':
        case 'confirmed':
            return 'confirmed'
        case 'completed':
            return 'idle'
        case 'failed':
            return 'failed'
        default:
            return fallbackValue
    }
}

private Boolean sendChildEventIfChanged(dev, String attributeName, value) {
    if (!dev || !attributeName) return false

    try {
        if (dev.currentValue(attributeName)?.toString() != value?.toString()) {
            dev.sendEvent(name: attributeName, value: value, type: 'digital')
            return true
        }
    } catch (Exception ex) {
        logDebug("Unable to update device attribute ${attributeName}: ${ex.message}")
    }

    return false
}

private void syncLocalQueueState(child = null, String desiredStatus = null, String message = null, Object itemId = null) {
    def target = child ?: getSpeakerDevice()
    if (!target) return

    Integer queueSize = getLocalPendingCount()
    String queueStatus = deriveLocalQueueStatus(desiredStatus)

    if (applyDriverQueueState(target, queueSize, queueStatus) != true) {
        Boolean sizeChanged = sendChildEventIfChanged(target, 'queueSize', queueSize)
        Boolean statusChanged = sendChildEventIfChanged(target, 'queueStatus', queueStatus)

        if (sizeChanged == true || statusChanged == true) {
            sendChildEventIfChanged(target, 'lastActivity', now().toString())
        }
    }
}

//
//    VOICEMONKEY REQUEST HANDLING
//

private void markAcceptedForItem(Long itemId, String text) {
    Map activeItem = (state.activeItem instanceof Map) ? new LinkedHashMap(state.activeItem as Map) : null
    if (!activeItem || (itemId != null && safeLong(activeItem.id, null) != itemId)) {
        logDebug("Ignoring acceptance for non-active item ${itemId}")
        return
    }

    Long acceptedEpoch = now()
    activeItem.acceptedEpoch = acceptedEpoch
    activeItem.startedEpoch = acceptedEpoch
    activeItem.completionDueEpoch = acceptedEpoch + (normalizeCompletionDelay(activeItem?.duration) * 1000L)
    activeItem.queueStage = 'confirmed'
    state.activeItem = activeItem

    syncLocalQueueState(getSpeakerDevice(), 'confirmed', text ?: activeItem?.text?.toString(), activeItem?.id)
    scheduleCompletionForActiveItem(activeItem)

    logDebug("VoiceMonkey accepted active queue item ${activeItem?.id}")
}

private void markFailure(String message) {
    unschedule('voiceMonkeyAcceptanceTimeout')
    unschedule('completePlayback')
    unschedule('queueRecoveryCheck')

    def child = getSpeakerDevice()
    Map item = (state.activeItem instanceof Map) ? state.activeItem : null

    state.activeItem = null
    state.processing = false

    Boolean hasMoreLocalItems = (getLocalQueueCount() > 0)
    Integer bufferSeconds = hasMoreLocalItems ? getDefaultQueueBufferSeconds() : 0
    state.nextDispatchAllowedEpoch = (hasMoreLocalItems && bufferSeconds > 0) ? (now() + (bufferSeconds * 1000L)) : 0L

    if (child) {
        syncLocalQueueState(child, 'failed', message ?: 'Unknown error', item?.id)
    }

    log.error "${app.label}: ${message}"

    if (hasMoreLocalItems) {
        scheduleKickQueue(bufferSeconds)
    }
}

private void markFailureForItem(Long itemId, String message) {
    Map activeItem = (state.activeItem instanceof Map) ? (state.activeItem as Map) : null
    if (!activeItem || (itemId != null && safeLong(activeItem.id, null) != itemId)) {
        log.warn "${app.label}: Ignoring error for non-active queue item ${itemId}: ${message}"
        return
    }

    markFailure(message)
}

private Boolean speakViaVoiceMonkey(Map item) {
    String token = getEffectiveToken()
    if (!token) {
        markFailure('Missing VoiceMonkey token')
        return false
    }

    String deviceId = getConfiguredVoiceMonkeyDeviceId()
    if (!deviceId) {
        markFailure('Missing VoiceMonkey device ID')
        return false
    }

    String cleanText = sanitizeAnnouncementText(item?.text)
    if (!cleanText) {
        markFailure('Empty announcement text after sanitization')
        return false
    }

    Map body = [
        token : token,
        device: deviceId,
        text  : cleanText
    ]

    String selectedVoice = item.voice?.toString()?.trim() ?: settings?.personalityVoice?.toString()?.trim()
    if (selectedVoice) {
        body.voice = selectedVoice
    }

    String resolvedLanguage = resolveLanguageForVoice(selectedVoice) ?: getEffectiveDefaultLanguage()
    if (resolvedLanguage) {
        body.language = resolvedLanguage
    }

    String selectedChime = item.chime?.toString()?.trim() ?: settings?.personalityChime?.toString()?.trim()
    if (selectedChime) {
        body.chime = selectedChime
    }

    Map params = [
        uri               : 'https://api-v2.voicemonkey.io',
        path              : '/announcement',
        requestContentType: 'application/json',
        contentType       : 'application/json',
        body              : body,
        timeout           : getDefaultVoiceMonkeyHttpTimeoutSeconds()
    ]

    logDebug("Sending announcement to VoiceMonkey for device ${deviceId}")

    try {
        asynchttpPost('voiceMonkeyResponseHandler', params, [queuedText: cleanText, itemId: item.id?.toString()])
        return true
    } catch (Exception ex) {
        markFailure("HTTP send failed: ${ex.message}")
        return false
    }
}

def voiceMonkeyResponseHandler(response, data) {
    logDebug("VoiceMonkey response status ${response?.status}")

    Long callbackItemId = safeLong(data?.itemId, null)

    if (response?.hasError()) {
        markFailureForItem(callbackItemId, "VoiceMonkey error: ${response.getErrorMessage()}")
        return
    }

    Integer statusCode = response?.status as Integer
    if (statusCode != null && (statusCode < 200 || statusCode > 299)) {
        markFailureForItem(callbackItemId, "VoiceMonkey HTTP status ${statusCode}")
        return
    }

    markAcceptedForItem(callbackItemId, data?.queuedText?.toString())
}

//
//    DURATION ESTIMATION
//

private Integer countSpeechCharacters(String text) {
    return (text ?: '').replaceAll(/\s+/, '').size()
}

private Integer countSpeechWords(String text) {
    String normalized = (text ?: '').replaceAll(/[^A-Za-z0-9]+/, ' ').trim()
    if (!normalized) return 0
    return normalized.split(/\s+/).size()
}

private Integer estimateConservativeSpeechSeconds(String text, Map item) {
    String cleanText = stripSsmlForDuration(text)
    Integer words = countSpeechWords(cleanText)
    Integer chars = countSpeechCharacters(cleanText)

    Double wordSeconds = words * 0.65d
    Double charSeconds = chars / 12.0d
    Double punctuationSeconds = estimatePunctuationPauseSeconds(cleanText)
    Double startupSeconds = 2.0d
    Double chimeSeconds = normalizeOptionalString(item?.chime) ? 1.5d : 0.0d

    Double seconds = Math.max(wordSeconds, charSeconds) + punctuationSeconds + startupSeconds + chimeSeconds
    Integer rounded = Math.ceil(seconds).intValue()

    return Math.max(1, rounded)
}

private Integer estimateDurationSeconds(Map item) {
    String text = item?.text?.toString() ?: ''
    String selectedVoice = item?.voice?.toString()?.trim() ?: settings?.personalityVoice?.toString()?.trim()
    Integer minimum = getDefaultMinimumSpacingSeconds()
    Integer interMessageBuffer = getDefaultQueueBufferSeconds()

    Integer hubitatSeconds = estimateHubitatTtsSeconds(text, selectedVoice)
    Integer conservativeSeconds = estimateConservativeSpeechSeconds(text, item)
    Integer duration = Math.max(hubitatSeconds, conservativeSeconds)

    if (duration < minimum) {
        duration = minimum
    }

    logDebug("Estimated playback duration ${duration}s for item ${item?.id}; hubitat=${hubitatSeconds}s, conservative=${conservativeSeconds}s, interMessageBuffer=${interMessageBuffer}s")
    return duration
}

private Integer estimateHubitatTtsSeconds(String text, String selectedVoice) {
    try {
        def tts = selectedVoice ? textToSpeech(text, selectedVoice) : textToSpeech(text)
        if (tts?.duration != null) {
            return safeWholeNumber(tts.duration, 0)
        }
    } catch (Exception ex) {
        logDebug("Hubitat textToSpeech duration estimate unavailable: ${ex.message}")
    }

    return 0
}

private Double estimatePunctuationPauseSeconds(String text) {
    String cleanText = text ?: ''
    Double pauseSeconds = 0.0d

    def ellipsisMatcher = cleanText =~ /\.{3,}/
    while (ellipsisMatcher.find()) {
        pauseSeconds += 1.2d
    }

    String withoutEllipsis = cleanText.replaceAll(/\.{3,}/, ' ')

    def sentenceMatcher = withoutEllipsis =~ /[.!?]/
    while (sentenceMatcher.find()) {
        pauseSeconds += 0.6d
    }

    def minorMatcher = withoutEllipsis =~ /[,;:]/
    while (minorMatcher.find()) {
        pauseSeconds += 0.35d
    }

    def dashMatcher = withoutEllipsis =~ /[\u2013\u2014-]/
    while (dashMatcher.find()) {
        pauseSeconds += 0.35d
    }

    return pauseSeconds
}

private String sanitizeAnnouncementText(Object value) {
    String text = value?
    if (text == null) return null

    String sanitized = text
        .replaceAll(/[\r\n\t\f]+/, ' ')
        .replaceAll(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/, ' ')
        .replaceAll(/\p{Cf}+/, '')
        .replaceAll(/[\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]+/, ' ')
        .replaceAll(/\s+/, ' ')
        .trim()

    return sanitized ? sanitized : null
}

private String stripSsmlForDuration(String text) {
    return (sanitizeAnnouncementText(text) ?: '').replaceAll(/<[^>]+>/, ' ').replaceAll(/\s+/, ' ').trim()
}

//
//    QUEUE STATE HELPERS
//

private Integer getLocalPendingCount() {
    Integer queued = getLocalQueueCount()
    Integer active = (state.activeItem instanceof Map) ? 1 : 0
    return queued + active
}

private Integer getLocalQueueCount() {
    return getQueuedItems().size()
}

private Long nextQueueItemId(Long enqueueEpoch) {
    Long epoch = safeLong(enqueueEpoch, now())
    Long counter = safeLong(state.lastQueueCounter, 0L) + 1L
    state.lastQueueCounter = counter
    return (epoch * 1000L) + counter
}

//
//    GENERAL UTILITIES
//

private Boolean normalizeBoolean(value, Boolean defaultValue) {
    if (value == null) return defaultValue
    if (value instanceof Boolean) return value

    String s = value.toString().trim().toLowerCase()
    if (s == 'true') return true
    if (s == 'false') return false

    return defaultValue
}

private String normalizeOptionalString(Object value) {
    String text = value?.toString()?.trim()
    return text ? text : null
}

private Integer normalizePositiveInteger(Object value, Integer fallbackValue) {
    Integer normalized = safeWholeNumber(value, fallbackValue)
    return normalized > 0 ? normalized : fallbackValue
}

private Long safeLong(value, Long fallbackValue) {
    try {
        if (value == null) return fallbackValue
        return value.toString().toLong()
    } catch (Exception ignored) {
        return fallbackValue
    }
}

private Integer safeWholeNumber(Object value, Integer fallbackValue) {
    try {
        if (value == null) return fallbackValue
        if (value instanceof Number) return ((Number) value).intValue()

        String text = value.toString().trim()
        if (!text) return fallbackValue
        return text.toBigDecimal().intValue()
    } catch (Exception ignored) {
        return fallbackValue
    }
}
