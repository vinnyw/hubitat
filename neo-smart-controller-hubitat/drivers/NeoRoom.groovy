
metadata {
    definition(name: "Neo Room", namespace: "vinny", author: "Vinny") {
        capability "WindowShade"
        capability "Actuator"

        command "open"
        command "close"
        command "stop"
    }
}

def open() {
    parent?.componentOpen(device)
    sendEvent(name: "windowShade", value: "opening")
}

def close() {
    parent?.componentClose(device)
    sendEvent(name: "windowShade", value: "closing")
}

def stop() {
    parent?.componentStop(device)
    sendEvent(name: "windowShade", value: "partially open")
}
