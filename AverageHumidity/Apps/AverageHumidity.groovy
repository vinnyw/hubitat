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


//
//    __   _____ _ __ ___(_) ___  _ __
//    \ \ / / _ \ '__/ __| |/ _ \| '_ \
//     \ V /  __/ |  \__ \ | (_) | | | |
//      \_/ \___|_|  |___/_|\___/|_| |_|
//

def getVersion() {
    return '2.7.47'
}

def getShortVersion() {
    return extractShortVersion(getVersion())
}

private String extractShortVersion(String version) {
    String raw = version?.toString()?.trim() ?: '0.0'
    def matcher = raw =~ /(\d+\.\d+)/
    return matcher.find() ? matcher.group(1) : raw
}

private String getDisplayVersionValue(Object versionValue) {
    String version = versionValue?.toString()?.trim()
    return version ? "v${version}" : 'Unknown'
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


//      _       _             __
//     (_)_ __ | |_ ___ _ __ / _| __ _  ___ ___
//     | | '_ \| __/ _ \ '__| |_ / _` |/ __/ _ \
//     | | | | | ||  __/ |  |  _| (_| | (_|  __/
//     |_|_| |_|\__\___|_|  |_|  \__,_|\___\___|
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

        section(hideable: true, hidden: true, title: 'Advanced...') {
            input 'thisName', 'text', title: 'App Name (Optional)', submitOnChange: true
        }

        String Version = getDisplayVersionValue(getVersion())

        // section() {
        //     paragraph "<hr style='background-color:#9E9E9E; height: 1px; border: 0; margin: 0.5rem 0 0.75rem 0;'><div style='font-size: 10px; color: #888; width: 100%; text-align: right;'>App ${htmlEncode(Version)}</div>"
        // }

        section() {
        	paragraph "<div style='font-size: 10px; color: #888; width: 100%; text-align: right;'>App ${htmlEncode(Version)}</div>"
        }

    }
}

private void enforceLabel() {
    String defaultName = 'Average Humidity'
    String customName = settings?.thisName?.toString()?.trim()
    String newLabel = customName ? customName : defaultName

    if (app?.label != newLabel) {
        app.updateLabel(newLabel)
    }
}


//           _     _ _     _                
//       ___| |__ (_) | __| |_ __ ___ _ __  
//      / __| '_ \| | |/ _` | '__/ _ \ '_ \ 
//     | (__| | | | | | (_| | | |  __/ | | |
//      \___|_| |_|_|_|\__,_|_|  \___|_| |_|
//                                   

private String getInstalledChildVersionsSummary() {
    List<String> versions = []

    (getChildApps() ?: []).each { childApp ->
        try {
            def version = childApp?.respondsTo('getVersion') ? childApp.getVersion() : null
            String value = version?.toString()?.trim()
            if (value) {
                versions << "v${value}"
            }
        } catch (Exception e) {
            log.warn "Unable to read child version for '${childApp?.label ?: childApp?.name ?: 'unknown'}': ${e.message}"
        }
    }

    versions = versions.unique()
    return versions ? versions.join(', ') : null
}


//                      __  __       _     _ 
//       ___  ___ __ _ / _|/ _| ___ | | __| |
//      / __|/ __/ _` | |_| |_ / _ \| |/ _` |
//      \__ \ (_| (_| |  _|  _| (_) | | (_| |
//      |___/\___\__,_|_| |_|  \___/|_|\__,_|
//                                           

def initialize() {
    // ⚠️ NOTE: ParentApp should NOT manage devices or subscriptions.
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

def uninstalled() {
    getChildApps()?.each { childApp ->
        try {
            childApp.deleteManagedChildDevice()
        } catch (Exception e) {
            log.warn "Failed cleanup for child app '${childApp?.label}': ${e.message}"
        }
    }
}
