definition(
    name: 'Average Humidity ',
    namespace: 'vinnyw',
    author: 'vinny wadding',
    description: 'Manage multiple averaged humidity virtual sensors',
    category: 'Convenience',
    iconUrl: 'https://raw.githubusercontent.com/hubitat/HubitatPublic/master/resources/images/App%20Icons/Convenience.png',
    iconX2Url: 'https://raw.githubusercontent.com/hubitat/HubitatPublic/master/resources/images/App%20Icons/Convenience.png',
    iconX3Url: 'https://raw.githubusercontent.com/hubitat/HubitatPublic/master/resources/images/App%20Icons/Convenience.png',
    singleInstance: true,
    singleThreaded: false,
    installOnOpen: true
)

def getVersion() {
    return '2.7.0'
}

def childVersions = childApps.collect {
    it.respondsTo('getVersion') ? it.getVersion() : 'unknown'
}.unique().join(', ')

preferences {
    page(name: 'mainPage')
}

def mainPage() {
    def childApps = getChildApps() ?: []
    def childVersions = childApps.collect {
        it.respondsTo('getVersion') ? it.getVersion() : 'unknown'
    }.unique().join(', ')

    dynamicPage(name: 'mainPage', title: 'Humidity (Average)', install: true, uninstall: true) {
        section('Virtual Humidity Sensors') {
            app(
                name: 'childApps',
                appName: 'Average Humidity Child',
                namespace: 'vinnyw',
                multiple: true
            )
        }

        section {
            input 'thisName', 'text', title: 'App Name (Optional)', submitOnChange: true
        }

        section {
            paragraph "<div style='font-size: 10px; color: #888;'>Parent v${getVersion()}<br>Child v${childVersions ?: 'N/A'}</div>"
        }
    }
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

def initialize() {
    // ⚠️ NOTE: ParentApp should NOT manage devices or subscriptions.
}

def uninstalled() {
    getChildApps()?.each { childApp ->
        try {
            childApp.deleteManagedChildDevice()
        } catch (Exception e) {
            log.warn "Failed cleanup for child app '${childApp?.label}': ${e.message}"
        }
    }
}

def enforceLabel() {
    def defaultName = 'Average Humidity'

    def newLabel = (thisName && thisName.trim()) ? thisName.trim() : defaultName

    if (app.label != newLabel) {
        app.updateLabel(newLabel)
    }
}
