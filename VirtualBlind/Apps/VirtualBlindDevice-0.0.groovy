definition(
    name: 'VirtualBlindDevice-0.0',
    namespace: 'vinnyw',
    author: 'Vinny Wadding',
    description: 'Owns one timed virtual blind and its movement logic.',
    category: 'Convenience',
    parent: 'vinnyw:VirtualBlindGroup-0.0',
    iconUrl: '',
    iconX2Url: '',
    iconX3Url: '',
    singleInstance: false,
    singleThreaded: true
)

preferences {
    page(
        name: 'blindPage',
        // title: 'Virtual Blind',
        install: true,
        uninstall: true
    )
}

//
//    UI
//

def blindPage() {
    prepareAdvancedUiSession()

    dynamicPage(name: 'blindPage') {
        // Seed only a brand-new/untouched blind label while the page is being built.
        // Do not auto-install the blind; nested child creation must remain available.
        if (!app.label?.toString()?.trim() ||
            app.label?.toString() == "VirtualBlindDevice-${getVersion()}") {
            app.updateLabel('Virtual Blind Device')
            }

        if (!state?.setupComplete) {
            section() {
                paragraph '⚠️ Setup is not complete yet. Press <b>Done</b> to create or update the Virtual Blind Device.'
            }
        }

        section() {
            label title: 'Virtual Blind Name ',
                required: true,
                submitOnChange: true
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

        section(
            hideable: true,
            hidden: !(state?.advancedExpanded == true),
            title: 'Advanced'
        ) {
            paragraph 'Optional tuning settings for blind movement. Most users can leave these at their defaults and only expand this section when finer control is needed.'

            paragraph 'To set the full travel time, use a stopwatch to measure how many seconds the blind takes to move from fully closed to fully open, then enter that time below. This allows the virtual blind to estimate intermediate positions based on how long the blind has been moving.'
            paragraph 'If Travel Time is set to 0, movement is treated as instantaneous and the virtual blind immediately changes to the requested position without simulating travel time.'

            input(
                name: 'fullTravelSeconds',
                type: 'number',
                title: 'Travel Time (seconds) ',
                width: 4,
                description: '0 = instantaneous; maximum 90 seconds.',
                range: '0..90',
                defaultValue: 0,
                submitOnChange: true,
                required: true
            )
        }

        section {
            String Version = formatDisplayVersion(getVersion())
            paragraph "<div style='font-size: 10px; color: #888; width: 100%; text-align: right;'>${htmlEncode(Version)}</div>"
        }
    }
}

private String currentAdvancedUiSignature() {
    return [
        settings?.fullTravelSeconds?.toString() ?: '0'
    ].join('|')
}

private void prepareAdvancedUiSession() {
    String currentSignature = currentAdvancedUiSignature()
    String previousSignature = state?.advancedUiSignature?.toString()

    boolean advancedSubmitRefresh = previousSignature != null && currentSignature != previousSignature

    // Latch the Advanced panel open for the remainder of this UI session.
    // updated() clears this state when Done is clicked.
    state.advancedExpanded = (state?.advancedExpanded == true) || advancedSubmitRefresh
    state.advancedUiSignature = currentSignature
}

//
//    LIFECYCLE
//

private void initialize() {
    normalizeTravelTime()

    state.currentPosition = 0
    state.remove('movement')

    parent.registerBlind(
        app.id.toString(),
        app.label ?: 'Virtual Blind Device',
        getBlindSnapshot()
    )

    syncChildPresentationConfiguration()
    logDebug("Initialized blind at position=${currentPosition()}, travelSeconds=${travelSeconds()}")
}

def installed() {
    state.setupComplete = true
    state.remove('advancedExpanded')
    state.remove('advancedUiSignature')
    applyLoggingDefaults()
    initialize()
}

def uninstalled() {
    unschedule()
    parent.unregisterBlind(app.id.toString())
}

def updated() {
    state.setupComplete = true
    state.remove('advancedExpanded')
    state.remove('advancedUiSignature')
    applyLoggingDefaults()

    /*
     * A calibration change while moving deterministically stops the virtual
     * blind at its estimated current position before applying the new timing.
     */
    if (state.movement) {
        Integer current = estimatedCurrentPosition()
        unschedule('finishMovement')
        state.currentPosition = current
        state.remove('movement')
    }

    normalizeTravelTime()
    ensureStateInitialized()

    parent.registerBlind(
        app.id.toString(),
        app.label ?: 'Virtual Blind Device',
        getBlindSnapshot()
    )

    syncChildPresentationConfiguration()
}

//
//    STATE MANAGEMENT
//

private void ensureStateInitialized() {
    if (state.currentPosition == null) {
        state.currentPosition = 0
    }
}

//
//    COMMANDS
//

void commandClose() {
    logDebug('Close requested')
    beginMove(100)
}

void commandOpen() {
    logDebug('Open requested')
    beginMove(0)
}

void commandSetPosition(BigDecimal requestedPosition) {
    Integer target = clampPosition(requestedPosition)
    logDebug("Set position requested target=${target}")
    beginMove(target)
}

void commandStop() {
    Map movement = state.movement

    if (!movement) {
        logDebug('Stop requested while blind is stationary')
        return
    }

    /*
     * Freeze the blind at an estimated physical position derived from the
     * recorded start position and elapsed movement time.  Calculate this
     * before clearing movement state because the estimator requires that
     * movement context.
     */
    Integer stoppedPosition = estimatedCurrentPosition()

    /*
     * A stopped movement must never be allowed to reach its original target
     * later.  Remove the single pending completion job before committing and
     * publishing the terminal state.
     */
    unschedule('finishMovement')

    state.currentPosition = stoppedPosition
    state.remove('movement')

    /*
     * Publish the estimated position explicitly for BOTH opening and closing.
     * Do not derive or suppress the position from the final shade state.
     */
    String stoppedDirection = movement.direction?.toString() ?: state.lastMovement?.toString()

    Map stoppedSnapshot = [
        position: stoppedPosition,
        windowShade: restingShadeState(stoppedPosition),
        lastMovement: stoppedDirection
    ]

    logDebug("Movement stopped direction=${stoppedDirection}, estimated position=${stoppedPosition}")
    notifyGroup(stoppedSnapshot)
}

//
//    MOVEMENT
//

private void beginMove(Integer target) {
    ensureStateInitialized()

    Integer current = state.movement ?
        estimatedCurrentPosition() :
        currentPosition()

    /*
     * One blind owns at most one scheduled completion job.
     * Every new command replaces the previous movement deterministically.
     */
    unschedule('finishMovement')

    state.currentPosition = current
    state.remove('movement')

    if (current == target) {
        logDebug("Movement skipped because current position already equals target=${target}")
        notifyGroup()
        return
    }

    /*
     * Record the most recently commanded movement direction.
     * Position 0 is fully open and 100 is fully closed:
     * moving toward a smaller position is "up"; larger is "down".
     */
    state.lastMovement = target < current ? 'up' : 'down'

    Integer fullTravel = travelSeconds()

    if (fullTravel == 0) {
        state.currentPosition = target
        logDebug("Instantaneous move completed at position=${target}")
        notifyGroup()
        return
    }

    Integer distance = Math.abs(target - current)
    Integer durationSeconds = Math.max(
        1,
        Math.round(fullTravel * (distance / 100.0d)) as Integer
    )

    state.movement = [
        startPosition: current,
        targetPosition: target,
        startedAtMs: now(),
        durationMs: durationSeconds * 1000L,
        direction: target < current ? 'opening' : 'closing'
    ]

    logDebug("Movement started current=${current}, target=${target}, durationSeconds=${durationSeconds}, direction=${state.movement.direction}")

    /*
     * Push the fresh moving snapshot immediately.
     */
    notifyGroup()

    runIn(
        durationSeconds,
        'finishMovement',
        [overwrite: true]
    )
}

private Integer currentPosition() {
    return clampPosition(state.currentPosition ?: 0)
}

private Integer estimatedCurrentPosition() {
    Map movement = state.movement
    if (!movement) {
        return clampPosition((state.currentPosition ?: 0) as Integer)
    }

    Integer startPosition = clampPosition((movement.startPosition != null ? movement.startPosition : 0) as Integer)
    Integer targetPosition = clampPosition((movement.targetPosition != null ? movement.targetPosition : startPosition) as Integer)
    Long startedAtMs = (movement.startedAtMs ?: now()) as Long
    Long elapsedMs = Math.max(0L, now() - startedAtMs)

    /*
     * Calculate distance travelled from elapsed time against FULL travel time,
     * then apply that distance according to the actual direction.  This makes
     * stop estimation symmetric for both opening and closing and does not rely
     * on interpolation through the scheduled partial-move duration.
     */
    BigDecimal fullTravelSeconds = travelSeconds() as BigDecimal
    if (fullTravelSeconds <= 0G) {
        return targetPosition
    }

    BigDecimal travelledPercent =
        (elapsedMs as BigDecimal) / (fullTravelSeconds * 1000G) * 100G

    Integer estimate

    /*
     * Numeric position determines the sign of travel; movement.direction is
     * retained for diagnostics/presentation only.  This keeps the estimator
     * correct regardless of whether the active command is opening or closing.
     */
    if (targetPosition > startPosition) {
        estimate = Math.min(
            targetPosition,
            (startPosition + travelledPercent).setScale(0, BigDecimal.ROUND_HALF_UP).intValue()
        )
    } else if (targetPosition < startPosition) {
        estimate = Math.max(
            targetPosition,
            (startPosition - travelledPercent).setScale(0, BigDecimal.ROUND_HALF_UP).intValue()
        )
    } else {
        estimate = startPosition
    }

    logDebug("Position estimate direction=${movement.direction}, start=${startPosition}, target=${targetPosition}, elapsedMs=${elapsedMs}, travelled=${travelledPercent}, estimate=${estimate}")

    return clampPosition(estimate)
}

void finishMovement() {
    Map movement = state.movement
    if (!movement) return

    Integer target = clampPosition(movement.targetPosition)

    /*
     * Re-check the governing movement context before committing terminal state.
     * If a newer command had replaced this move, its unschedule/overwrite path
     * means this invocation will not have the old context.
     */
    state.currentPosition = target
    state.remove('movement')

    logDebug("Movement completed at position=${target}")

    /*
     * Crucial fix: send the freshly-computed terminal snapshot directly to the
     * Group App. The group never depends on an immediate read-back of this
     * Blind App's just-modified persistent state.
     */
    notifyGroup([
        position: target,
        windowShade: restingShadeState(target),
        lastMovement: state.lastMovement?.toString()
    ])
}

Map getBlindSnapshot() {
    ensureStateInitialized()

    if (state.movement) {
        return [
            position: estimatedCurrentPosition(),
            windowShade: state.movement.direction?.toString(),
            lastMovement: state.lastMovement?.toString()
        ]
    }

    Integer position = currentPosition()

    return [
        position: position,
        windowShade: restingShadeState(position),
        lastMovement: state.lastMovement?.toString()
    ]
}

private void normalizeTravelTime() {
    Integer normalized = clampTravel(settings.fullTravelSeconds ?: 0)

    /*
     * The app state is authoritative at runtime. The preference remains only
     * the user-facing configuration input.
     */
    state.travelTimeSeconds = normalized

    if (settings.fullTravelSeconds == null ||
        settings.fullTravelSeconds.toString() != normalized.toString()) {
        app.updateSetting(
            'fullTravelSeconds',
            [
                type: 'number',
                value: normalized
            ]
        )
        }
}

private void notifyGroup(Map explicitSnapshot = null) {
    Map snapshot = explicitSnapshot ?: getBlindSnapshot()

    parent.blindStateChanged(
        app.id.toString(),
        snapshot
    )
}

private String restingShadeState(Integer position) {
    if (position <= 0) return 'open'
    if (position >= 100) return 'closed'
    return 'partially open'
}

private Integer travelSeconds() {
    ensureStateInitialized()
    return clampTravel(state.travelTimeSeconds ?: 0)
}

//
//    LOGGING
//

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

Map getChildDriverLoggingConfig() {
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

private void syncChildPresentationConfiguration() {
    try {
        parent?.applyBlindPresentationLogging(
            app.id.toString(),
            descriptionTextLoggingEnabled(),
            debugLoggingEnabled(),
            getDebugAutoDisableSeconds()
        )
    } catch (Exception e) {
        logWarn("Unable to synchronise blind driver logging settings: ${e.message}")
    }
}

void updateLoggingFromDriver(txtEnableValue, debugEnableValue) {
    Boolean descEnabled = normalizeBoolean(txtEnableValue, true)
    Boolean debugEnabled = normalizeBoolean(debugEnableValue, false)

    app?.updateSetting('txtEnable', [value: descEnabled, type: 'bool'])
    app?.updateSetting('debugEnable', [value: debugEnabled, type: 'bool'])

    syncChildPresentationConfiguration()
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

private Integer clampTravel(value) {
    Integer seconds = safeInteger(value, 0)
    return Math.max(0, Math.min(90, seconds))
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
