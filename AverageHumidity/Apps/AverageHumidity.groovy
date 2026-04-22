definition(
    name: 'Average Humidity',
    namespace: 'vinnyw',
    author: 'Vinny Wadding',
    description: 'Manage multiple averaged humidity virtual sensors',
    category: 'Convenience',
    importUrl: 'https://raw.githubusercontent.com/vinnyw/hubitat/master/AverageHumidity/Apps/AverageHumidity.groovy',
    documentationLink: 'https://github.com/vinnyw/hubitat/blob/master/README.md',
    iconUrl: 'https://raw.githubusercontent.com/hubitat/HubitatPublic/master/resources/images/App%20Icons/Convenience.png',
    iconX2Url: 'https://raw.githubusercontent.com/hubitat/HubitatPublic/master/resources/images/App%20Icons/Convenience.png',
    iconX3Url: 'https://raw.githubusercontent.com/hubitat/HubitatPublic/master/resources/images/App%20Icons/Convenience.png',
    singleInstance: true,
    singleThreaded: false,
    installOnOpen: true
)

import groovy.transform.Field

@Field static final Integer DEBUG_AUTO_DISABLE_SECONDS = 1800


//
//    VERSION
//

def getVersion() {
    return '2.7.51'
}


//
//    VERSION HELPERS
//

private String extractShortVersion(String version) {
    String raw = version?.toString()?.trim() ?: '0.0'
    def matcher = raw =~ /(\d+\.\d+)/
    return matcher.find() ? matcher.group(1) : raw
}

private String getDisplayVersionValue(Object versionValue) {
    String version = versionValue?.toString()?.trim()
    return version ? "v${version}" : 'unknown'
}

def getShortVersion() {
    return extractShortVersion(getVersion())
}


//
//    UI / PREFERENCES
//

preferences {
    page(name: 'mainPage')
}

def mainPage() {
    enforceLabel()

    dynamicPage(name: 'mainPage', install: true, uninstall: true) {
        section() {
            app(
                title: "Add New Sensor",
                name: 'childApps',
                appName: "Humidity-${getShortVersion()}",
                namespace: 'vinnyw',
                multiple: true
            )
        }

        section(hideable: true, hidden: true, title: 'Advanced') {
            input 'thisName', 'text', title: 'Custom name for this app', submitOnChange: true
        }

        section() {
            String Version = getDisplayVersionValue(getVersion())
            paragraph "<div style='font-size: 10px; color: #888; width: 100%; text-align: right;'>${htmlEncode(Version)}</div>"
        }

    }
}


//
//    UI DEFAULTS & VALIDATION HELPERS
//

private void enforceLabel() {
    String defaultName = 'Average Humidity'
    String customName = settings?.thisName?.toString()?.trim()
    String newLabel = customName ? customName : defaultName

    if (app?.label != newLabel) {
        app.updateLabel(newLabel)
    }
}

private Integer getDebugAutoDisableMinutes() {
    return (int) (DEBUG_AUTO_DISABLE_SECONDS / 60)
}

private Integer getDebugAutoDisableSeconds() {
    return DEBUG_AUTO_DISABLE_SECONDS
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
//    LIFECYCLE
//

def initialize() {
    // ⚠️ NOTE: ParentApp should NOT manage devices or subscriptions.
}

def installed() {
    enforceLabel()
    initialize()
}

def uninstalled() {
    getChildApps()?.each { childApp ->
        try {
            childApp.deleteManagedChildDevice()
        } catch (Exception e) {
            log.warn "Failed cleanup for childapp '${childApp?.label}': ${e.message}"
        }
    }
}

def updated() {
    unsubscribe()
    unschedule()
    enforceLabel()
    initialize()
}
