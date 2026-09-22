definition(
    name: 'Virtual Blind',
    namespace: 'vinnyw',
    author: 'Vinny Wadding',
    description: 'Create, group and manage Virtual Blind Devices.',
    category: 'Convenience',
    iconUrl: '',
    iconX2Url: '',
    iconX3Url: '',
    singleInstance: true,
    singleThreaded: true,
    installOnOpen: true
)

import groovy.transform.Field

@Field static final Integer DEBUG_AUTO_DISABLE_SECONDS = 1800

preferences {
    page(
        name: 'mainPage',
        // title: 'VirtualBlind',
        install: true,
        uninstall: true
    )
}

//
//    UI
//

def mainPage() {
    enforceLabel()

    dynamicPage(name: 'mainPage') {
        section() {
            app(
                // name: 'groupApps',
                appName: "VirtualBlindGroup-${getShortVersion()}",
                namespace: 'vinnyw',
                title: 'Add New Room / Group',
                multiple: true
            )
        }

        section(hideable: true, hidden: true, title: 'Advanced') {
            input 'thisName', 'text', title: 'Custom name for this app', submitOnChange: true
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

def initialize() {
// Parent app should not manage devices or subscriptions.
}

def installed() {
    enforceLabel()
    initialize()
}

def updated() {
    unsubscribe()
    unschedule()
    enforceLabel()
    initialize()
}

//
//    VERSION
//

private String extractShortVersion(String version) {
    String raw = version?.toString()?.trim() ?: '0.0'
    def matcher = raw =~ /(\d+\.\d+)/
    return matcher.find() ? matcher.group(1) : raw
}

private String formatDisplayVersion(Object versionValue) {
    String version = versionValue?.toString()?.trim()
    return version ? "v${version}" : 'unknown'
}

def getShortVersion() {
    return extractShortVersion(getVersion())
}

def getVersion() {
    return '0.0.1'
}

//
//    LOGGING
//

Integer getDebugAutoDisableMinutes() {
    return (int) (DEBUG_AUTO_DISABLE_SECONDS / 60)
}

Integer getDebugAutoDisableSeconds() {
    return DEBUG_AUTO_DISABLE_SECONDS
}

//
//    HELPERS
//

private void enforceLabel() {
    String defaultName = 'Virtual Blind'
    String customName = settings?.thisName?.toString()?.trim()
    String newLabel = customName ? customName : defaultName

    if (app?.label != newLabel) {
        app.updateLabel(newLabel)
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
