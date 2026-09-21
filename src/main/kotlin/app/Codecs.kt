package app

object Codecs {
    fun runToJson(run: Run): JsonValue = JsonCodec.obj(
        "id" to run.id,
        "fixtureId" to run.fixtureId,
        "fixtureParams" to run.fixtureParams,
        "createdAtMs" to run.createdAtMs,
        "pressure" to JsonCodec.obj(
            "t0Ms" to run.pressure.t0Ms,
            "dtMs" to run.pressure.dtMs,
            "bar" to run.pressure.bar,
            "saturated" to run.pressure.saturated,
        ),
        "teeth" to JsonCodec.obj("tMs" to run.teeth.tMs),
        "markers" to JsonCodec.obj(
            "referenceGapMs" to run.markers.referenceGapMs,
            "tdcMs" to run.markers.tdcMs,
            "uncertainGapMs" to run.markers.uncertainGapMs,
        ),
        "condition" to JsonCodec.obj(
            "tMs" to run.condition.tMs,
            "rpm" to run.condition.rpm,
        ),
    )

    fun runFromJson(v: JsonValue): Run {
        val o = v as JsonValue.Obj
        val pressure = o.field("pressure") as JsonValue.Obj
        val teeth = o.field("teeth") as JsonValue.Obj
        val markers = o.field("markers") as JsonValue.Obj
        val cond = o.field("condition") as JsonValue.Obj
        val params = LinkedHashMap<String, Double>()
        (o.field("fixtureParams") as JsonValue.Obj).entries.forEach { (k, jv) ->
            params[k] = (jv as JsonValue.Num).value
        }
        return Run(
            id = o.field("id").asString,
            fixtureId = o.field("fixtureId").asString,
            fixtureParams = params,
            createdAtMs = o.field("createdAtMs").asLong,
            pressure = PressureSignal(
                t0Ms = pressure.field("t0Ms").asDouble,
                dtMs = pressure.field("dtMs").asDouble,
                bar = pressure.field("bar").asArray.mapToDouble(),
                saturated = pressure.field("saturated").asArray.mapToBoolean(),
            ),
            teeth = ToothEdges(teeth.field("tMs").asArray.mapToDouble()),
            markers = MarkerEvents(
                referenceGapMs = markers.field("referenceGapMs").asArray.mapToDouble(),
                tdcMs = markers.field("tdcMs").asArray.mapToDouble(),
                uncertainGapMs = (markers["uncertainGapMs"]?.asArray ?: emptyList()).mapToDouble(),
            ),
            condition = ConditionChannel(
                tMs = cond.field("tMs").asArray.mapToDouble(),
                rpm = cond.field("rpm").asArray.mapToDouble(),
            ),
        )
    }

    fun stateToJson(state: AnalysisState): JsonValue = JsonCodec.obj(
        "runId" to state.runId,
        "patternConfirmed" to state.patternConfirmed,
        "patternCandidateId" to state.patternCandidateId,
        "trustedTdcIndex" to state.trustedTdcIndex,
        "excludedCycleIndexes" to state.excludedCycleIndexes,
        "cycleOffsetsDeg" to state.cycleOffsetsDeg.mapKeys { (k, _) -> k.toString() }
            .mapValues { (_, v2) -> v2 as Any },
    )

    fun stateFromJson(v: JsonValue): AnalysisState {
        val o = v as JsonValue.Obj
        val excluded = o.field("excludedCycleIndexes").asArray.map { it.asInt }
        val offsets = LinkedHashMap<Int, Double>()
        (o.field("cycleOffsetsDeg") as? JsonValue.Obj)?.entries?.forEach { (k, jv) ->
            offsets[k.toInt()] = jv.asDouble
        }
        return AnalysisState(
            runId = o.field("runId").asString,
            patternConfirmed = (o.field("patternConfirmed") as? JsonValue.Bool)?.value ?: false,
            patternCandidateId = (o["patternCandidateId"] as? JsonValue.Str)?.value,
            trustedTdcIndex = if (o["trustedTdcIndex"] is JsonValue.Null) null
            else (o["trustedTdcIndex"] as? JsonValue.Num)?.asInt,
            excludedCycleIndexes = excluded,
            cycleOffsetsDeg = offsets,
        )
    }

    private fun List<JsonValue>.mapToDouble(): DoubleArray =
        DoubleArray(size) { this[it].asDouble }

    private fun List<JsonValue>.mapToBoolean(): BooleanArray =
        BooleanArray(size) { this[it].asBoolean }
}
