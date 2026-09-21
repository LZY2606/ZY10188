package app

object DtoJson {

    fun analysis(dto: AnalysisDto): JsonValue = JsonCodec.obj(
        "run" to runSummary(dto.run),
        "state" to state(dto.state),
        "toothIntervals" to dto.toothIntervals.map(::toothInterval),
        "candidates" to dto.candidates.map(::candidate),
        "patternUnique" to dto.patternUnique,
        "cycles" to dto.cycles.map(::cycle),
        "envelope" to envelope(dto.envelope),
        "versions" to dto.versions,
    )

    fun runSummary(r: RunSummaryDto) = JsonCodec.obj(
        "id" to r.id,
        "fixtureId" to r.fixtureId,
        "fixtureParams" to r.fixtureParams,
        "sampleRateHz" to r.sampleRateHz,
        "sampleCount" to r.sampleCount,
        "durationMs" to r.durationMs,
        "startTimeMs" to r.startTimeMs,
    )

    fun raw(r: RawSignalsDto) = JsonCodec.obj(
        "pressure" to r.pressure.map { JsonCodec.toJson(it) },
        "toothEdgesMs" to r.toothEdgesMs,
        "referenceMarkersMs" to r.referenceMarkersMs,
        "tdcMarkersMs" to r.tdcMarkersMs,
        "uncertainGapMs" to r.uncertainGapMs,
        "condition" to r.condition.map { JsonCodec.toJson(it) },
    )

    private fun state(s: AnalysisState) = Codecs.stateToJson(s)

    private fun toothInterval(v: ToothIntervalDto) = JsonCodec.obj(
        "tMs" to v.tMs,
        "dtMs" to v.dtMs,
        "span" to v.span,
        "gap" to v.gap,
        "rpmInstant" to v.rpmInstant,
    )

    private fun candidate(c: CandidateDto) = JsonCodec.obj(
        "id" to c.id,
        "label" to c.label,
        "firingTdcMs" to c.firingTdcMs,
    )

    private fun metric(m: MetricDto) = JsonCodec.obj(
        "key" to m.key,
        "value" to m.value,
        "unit" to m.unit,
        "phaseResolved" to m.phaseResolved,
        "lowerBoundOnly" to m.lowerBoundOnly,
        "note" to m.note,
        "algorithmVersion" to m.algorithmVersion,
        "calibrationVersion" to m.calibrationVersion,
    )

    private fun segment(s: SegmentDto) = JsonCodec.obj(
        "startAngleDeg" to s.startAngleDeg,
        "endAngleDeg" to s.endAngleDeg,
        "sampleCount" to s.sampleCount,
    )

    private fun cycle(c: CycleDto) = JsonCodec.obj(
        "cycleIndex" to c.cycleIndex,
        "candidateId" to c.candidateId,
        "tdcMs" to c.tdcMs,
        "startMs" to c.startMs,
        "endMs" to c.endMs,
        "userOffsetDeg" to c.userOffsetDeg,
        "trace" to c.trace.map { JsonCodec.toJson(it) },
        "saturatedAngles" to c.saturatedAngles,
        "longSpanInterpolation" to c.longSpanInterpolation,
        "peggingWindow" to c.peggingWindow,
        "peggingOffsetBar" to c.peggingOffsetBar,
        "metrics" to c.metrics.map(::metric),
        "saturationSegments" to c.saturationSegments.map(::segment),
        "excluded" to c.excluded,
    )

    private fun envelope(e: EnvelopeDto) = JsonCodec.obj(
        "angle" to e.angle,
        "min" to e.min,
        "max" to e.max,
        "mean" to e.mean,
        "saturated" to e.saturated,
    )

    fun actions(items: List<ActionRecord>) = JsonCodec.toJson(
        items.map {
            JsonCodec.obj(
                "id" to it.id,
                "atMs" to it.atMs,
                "type" to it.type,
                "payload" to it.payload,
            )
        }
    )

    fun exportBundle(bundle: ExportBundle) = JsonCodec.obj(
        "run" to Codecs.runToJson(bundle.run),
        "state" to Codecs.stateToJson(bundle.state),
        "actions" to bundle.actions.map {
            JsonCodec.obj(
                "id" to it.id,
                "atMs" to it.atMs,
                "type" to it.type,
                "payload" to it.payload,
            )
        },
    )

    fun exportBundleFrom(v: JsonValue): ExportBundle {
        val o = v as JsonValue.Obj
        val run = Codecs.runFromJson(o.field("run"))
        val state = Codecs.stateFromJson(o.field("state"))
        val actions = o.field("actions").asArray.map { jv ->
            val a = jv as JsonValue.Obj
            ActionDto(
                id = a.field("id").asLong,
                atMs = a.field("atMs").asLong,
                type = a.field("type").asString,
                payload = a.field("payload").asString,
            )
        }
        return ExportBundle(run, state, actions)
    }

    fun analysisFrom(v: JsonValue): AnalysisDto = DtoJsonParser.analysis(v)

    fun confirmPattern(v: JsonValue): ConfirmPatternRequest =
        ConfirmPatternRequest((v as JsonValue.Obj).field("candidateId").asString)

    fun trustTdc(v: JsonValue): TrustTdcRequest =
        TrustTdcRequest((v as JsonValue.Obj).field("tdcIndex").asInt)

    fun offset(v: JsonValue): OffsetRequest {
        val o = v as JsonValue.Obj
        return OffsetRequest(o.field("cycleIndex").asInt, o.field("offsetDeg").asDouble)
    }

    fun exclude(v: JsonValue): ExcludeRequest {
        val o = v as JsonValue.Obj
        return ExcludeRequest(o.field("cycleIndex").asInt, (o.field("excluded") as? JsonValue.Bool)?.value ?: false)
    }
}

object DtoJsonParser {
    private fun metric(v: JsonValue): MetricDto {
        val o = v as JsonValue.Obj
        return MetricDto(
            key = o.field("key").asString,
            value = (o["value"] as? JsonValue.Num)?.value,
            unit = o.field("unit").asString,
            phaseResolved = o.field("phaseResolved").asBoolean,
            lowerBoundOnly = o.field("lowerBoundOnly").asBoolean,
            note = o.field("note").asString,
            algorithmVersion = o.field("algorithmVersion").asString,
            calibrationVersion = o.field("calibrationVersion").asString,
        )
    }

    private fun segment(v: JsonValue): SegmentDto {
        val o = v as JsonValue.Obj
        return SegmentDto(
            startAngleDeg = o.field("startAngleDeg").asDouble,
            endAngleDeg = o.field("endAngleDeg").asDouble,
            sampleCount = o.field("sampleCount").asInt,
        )
    }

    private fun cycle(v: JsonValue): CycleDto {
        val o = v as JsonValue.Obj
        return CycleDto(
            cycleIndex = o.field("cycleIndex").asInt,
            candidateId = o.field("candidateId").asString,
            tdcMs = o.field("tdcMs").asDouble,
            startMs = o.field("startMs").asDouble,
            endMs = o.field("endMs").asDouble,
            userOffsetDeg = o.field("userOffsetDeg").asDouble,
            trace = o.field("trace").asArray.map {
                val a = it as JsonValue.Arr
                val second = (a.items[1] as? JsonValue.Num)?.value ?: Double.NaN
                listOf(a.items[0].asDouble, second)
            },
            saturatedAngles = o.field("saturatedAngles").asArray.map { it.asDouble },
            longSpanInterpolation = o.field("longSpanInterpolation").asBoolean,
            peggingWindow = o.field("peggingWindow").asArray.map { it.asDouble },
            peggingOffsetBar = o.field("peggingOffsetBar").asDouble,
            metrics = o.field("metrics").asArray.map(::metric),
            saturationSegments = o.field("saturationSegments").asArray.map(::segment),
            excluded = o.field("excluded").asBoolean,
        )
    }

    private fun candidate(v: JsonValue): CandidateDto {
        val o = v as JsonValue.Obj
        return CandidateDto(
            id = o.field("id").asString,
            label = o.field("label").asString,
            firingTdcMs = o.field("firingTdcMs").asArray.map { it.asDouble },
        )
    }

    fun analysis(v: JsonValue): AnalysisDto {
        val o = v as JsonValue.Obj
        val ro = o.field("run") as JsonValue.Obj
        val summary = RunSummaryDto(
            id = ro.field("id").asString,
            fixtureId = ro.field("fixtureId").asString,
            fixtureParams = (ro.field("fixtureParams") as JsonValue.Obj).entries
                .mapValues { it.value.asDouble },
            sampleRateHz = ro.field("sampleRateHz").asDouble,
            sampleCount = ro.field("sampleCount").asInt,
            durationMs = ro.field("durationMs").asDouble,
            startTimeMs = ro.field("startTimeMs").asDouble,
        )
        return AnalysisDto(
            run = summary,
            state = Codecs.stateFromJson(o.field("state")),
            toothIntervals = o.field("toothIntervals").asArray.map { jv ->
                val x = jv as JsonValue.Obj
                ToothIntervalDto(
                    tMs = x.field("tMs").asDouble,
                    dtMs = x.field("dtMs").asDouble,
                    span = x.field("span").asInt,
                    gap = x.field("gap").asBoolean,
                    rpmInstant = x.field("rpmInstant").asDouble,
                )
            },
            candidates = o.field("candidates").asArray.map(::candidate),
            patternUnique = o.field("patternUnique").asBoolean,
            cycles = o.field("cycles").asArray.map(::cycle),
            envelope = EnvelopeDto(
                angle = o.field("envelope").let { it as JsonValue.Obj }.field("angle").asArray.map { it.asDouble },
                min = o.field("envelope").let { it as JsonValue.Obj }.field("min").asArray.map { it.asDouble },
                max = o.field("envelope").let { it as JsonValue.Obj }.field("max").asArray.map { it.asDouble },
                mean = o.field("envelope").let { it as JsonValue.Obj }.field("mean").asArray.map { it.asDouble },
                saturated = o.field("envelope").let { it as JsonValue.Obj }.field("saturated").asArray.map { it.asBoolean },
            ),
            versions = (o.field("versions") as JsonValue.Obj).entries.mapValues { it.value.asString },
        )
    }
}
