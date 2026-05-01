definition(
    name: 'VoiceMonkey Integration',
    namespace: 'vinnyw',
    author: 'Vinny Wadding',
    description: 'Manage VoiceMonkey TTS Devices',
    category: 'Convenience',
    importUrl: 'https://raw.githubusercontent.com/vinnyw/hubitat/master/VoiceMonkey/Apps/VoiceMonkey.groovy',
    documentationLink: 'https://github.com/vinnyw/hubitat/blob/master/README.md',
    iconUrl: 'https://raw.githubusercontent.com/hubitat/HubitatPublic/master/resources/images/App%20Icons/Convenience.png',
    iconX2Url: 'https://raw.githubusercontent.com/hubitat/HubitatPublic/master/resources/images/App%20Icons/Convenience.png',
    iconX3Url: 'https://raw.githubusercontent.com/hubitat/HubitatPublic/master/resources/images/App%20Icons/Convenience.png',
    singleInstance: true,
    singleThreaded: true,
    installOnOpen: true
)

import groovy.transform.Field

@Field static final Integer DEBUG_AUTO_DISABLE_SECONDS = 1800
@Field static final Integer DEFAULT_FIRST_MESSAGE_DELAY_SECONDS = 2
@Field static final Integer DEFAULT_QUEUE_BUFFER_SECONDS = 3
@Field static final Integer DEFAULT_MINIMUM_SPACING_SECONDS = 3
@Field static final Integer DEFAULT_VOICEMONKEY_HTTP_TIMEOUT_SECONDS = 5

//
//    UI PREFERENCES
//

preferences {
    page(name: 'mainPage')
    page(name: 'credentialsPage')
}

//
//    VERSION & DEFAULTS
//

def getVersion() {
    return '1.1.32'
}

def getDebugAutoDisableSeconds() {
    return DEBUG_AUTO_DISABLE_SECONDS
}

def getDefaultFirstMessageDelaySeconds() {
    return DEFAULT_FIRST_MESSAGE_DELAY_SECONDS
}

def getDefaultMinimumSpacingSeconds() {
    return DEFAULT_MINIMUM_SPACING_SECONDS
}

def getDefaultQueueBufferSeconds() {
    return DEFAULT_QUEUE_BUFFER_SECONDS
}

def getDefaultVoiceMonkeyHttpTimeoutSeconds() {
    return DEFAULT_VOICEMONKEY_HTTP_TIMEOUT_SECONDS
}

//
//    VERSION HELPERS
//

private String extractShortVersion(String version) {
    String raw = version?.toString()?.trim() ?: '0.0'
    def matcher = raw =~ /(\d+\.\d+)/
    return matcher.find() ? matcher.group(1) : raw
}

def getShortVersion() {
    return extractShortVersion(getVersion())
}

//
//    UI PAGES
//

def credentialsPage() {
    reconcileCredentialCaptureState()

    dynamicPage(name: 'credentialsPage', nextPage: 'mainPage', install: false, uninstall: false) {
        section {
            paragraph 'Enter your Voice Monkey credentials from https://console.voicemonkey.io/credentials.'
        }

        section('Credentials') {
            input 'apiToken', 'text', title: 'Token ', required: true
            input 'apiSecret', 'password', title: 'Secret ', required: false
        }

        section {
            paragraph 'Enter your Voice Monkey credentials and tap Done to return to the main page.'
        }

        section {
            String Version = formatDisplayVersion(getVersion())
            paragraph "<div style='font-size: 10px; color: #888; width: 100%; text-align: right;'>${htmlEncode(Version)}</div>"
        }
    }
}

def mainPage() {
    reconcileCredentialCaptureState()
    if (shouldShowCredentialsFirst()) {
        return credentialsPage()
    }

    dynamicPage(name: 'mainPage', install: true, uninstall: true) {
        section {
            app(
                name: 'childApps',
                appName: "VoiceMonkeyDevice-${getShortVersion()}",
                namespace: 'vinnyw',
                title: 'Add New Device',
                multiple: true
            )
        }

        section(hideable: true, hidden: false, title: 'Logging') {
            paragraph "Debug logging automatically turns off after ${debugAutoDisableMinutes()} minutes."

            input name: 'debugEnable', type: 'bool',
                  title: 'Enable debug logging',
                  defaultValue: false
        }

        section(hideable: true, hidden: true, title: 'Advanced') {
            input 'thisName', 'text', title: 'Custom name for this app', submitOnChange: true
        }

        section {
            String statusText = credentialsConfigured() ? 'Saved' : 'Required'
            href(
                name: 'credentialsPageLink',
                title: 'Credentials',
                page: 'credentialsPage',
                description: statusText
            )
        }

        section {
            String Version = formatDisplayVersion(getVersion())
            paragraph "<div style='font-size: 10px; color: #888; width: 100%; text-align: right;'>${htmlEncode(Version)}</div>"
        }
    }
}

//
//    UI STATE & DISPLAY HELPERS
//

private Boolean credentialsConfigured() {
    String token = settings?.apiToken?.toString()?.trim()
    return token ? true : false
}

private Integer debugAutoDisableMinutes() {
    return (int) (DEBUG_AUTO_DISABLE_SECONDS / 60)
}

private String formatDisplayVersion(Object versionValue) {
    String version = versionValue?.toString()?.trim()
    return version ? "v${version}" : 'unknown'
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

private void reconcileCredentialCaptureState() {
    Boolean configured = credentialsConfigured()
    if (configured) {
        state.credentialsCaptured = true
    } else if (!(state.credentialsCaptured in [true, false])) {
        state.credentialsCaptured = false
    }
}

private Boolean shouldShowCredentialsFirst() {
    Boolean captured = normalizeBoolean(state?.credentialsCaptured, false)
    return !captured || !credentialsConfigured()
}

//
//    LIFECYCLE
//

def initialize() {
    reconcileCredentialCaptureState()
    scheduleDebugAutoDisableIfNeeded()
}

def installed() {
    initialize()
}

def uninstalled() {
    unsubscribe()
    unschedule()
}

def updated() {
    unsubscribe()
    unschedule()
    initialize()
}

//
//    CREDENTIALS & SETTINGS
//

String getEffectiveToken() {
    String token = settings?.apiToken?.toString()?.trim()
    String secret = settings?.apiSecret?.toString()?.trim()

    if (token && secret) return "${token}_${secret}"
    if (token) return token

    return null
}

//
//    LOGGING
//

private Boolean debugLoggingEnabled() {
    return normalizeBoolean(settings?.debugEnable, false)
}

private void logDebug(String msg) {
    if (debugLoggingEnabled()) log.debug "${app.label}: ${msg}"
}

def logsOff() {
    if (!debugLoggingEnabled()) return

    app.updateSetting('debugEnable', [value: false, type: 'bool'])
    log.warn "${app.label}: Debug logging disabled automatically after ${debugAutoDisableMinutes()} minutes"
}

private void scheduleDebugAutoDisableIfNeeded() {
    unschedule('logsOff')

    if (debugLoggingEnabled()) {
        runIn(DEBUG_AUTO_DISABLE_SECONDS, 'logsOff')
        logDebug("Debug logging will automatically turn off in ${debugAutoDisableMinutes()} minutes")
    }
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
